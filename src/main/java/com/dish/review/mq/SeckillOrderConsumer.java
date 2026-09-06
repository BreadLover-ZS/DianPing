package com.dish.review.mq;

import com.dish.review.dto.SeckillOrderMessage;
import com.dish.review.exception.SeckillConsistencyException;
import com.dish.review.exception.SeckillPermanentMessageException;
import com.dish.review.service.SeckillOrderEventService;
import com.dish.review.service.SeckillVoucherLuaExecutor;
import com.dish.review.service.VoucherOrderHandler;
import com.dish.review.utils.RabbitMqConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * 消费秒杀订单消息，调用事务处理器落库，并把重复投递转换为幂等成功（规格第 9.2、10 节）。
 *
 * <p>异常分类由监听重试模板完成：临时故障有限重试；
 * 永久消息错误和一致性冲突不重试，由 Recoverer 持久化失败记录后进入 DLQ 或人工核对。</p>
 */
@Slf4j
@Component
public class SeckillOrderConsumer {

    private final VoucherOrderHandler voucherOrderHandler;

    private static final int CURRENT_MESSAGE_VERSION = 1;

    private final SeckillOrderEventService eventService;

    private final SeckillVoucherLuaExecutor luaExecutor;

    /**
     * 注入订单事务处理器和事件状态服务。
     */
    public SeckillOrderConsumer(
            VoucherOrderHandler voucherOrderHandler,
            SeckillOrderEventService eventService,
            SeckillVoucherLuaExecutor luaExecutor) {
        this.voucherOrderHandler = voucherOrderHandler;
        this.eventService = eventService;
        this.luaExecutor = luaExecutor;
    }

    /**
     * 校验消息后创建订单；正常返回由 Spring 自动 ACK，异常交给分类重试和死信策略。
     */
    @RabbitListener(
            queues = RabbitMqConstants.SECKILL_ORDER_QUEUE,
            autoStartup =
                    "${dish-review.seckill.rabbit-consumer-enabled:false}"
    )
    public void consume(SeckillOrderMessage message) {
        validate(message);

        log.info(
                "收到秒杀订单消息，eventId={}，orderId={}",
                message.getEventId(),
                message.getOrderId()
        );

        try {
            voucherOrderHandler.createOrder(message);
        } catch (DuplicateKeyException exception) {
            // 唯一索引冲突：订单已存在时按幂等成功收敛事件状态
            if (voucherOrderHandler.orderAlreadyExists(message)) {
                boolean marked = eventService.markConsumed(
                        message.getEventId()
                );

                if (!marked) {
                    throw new SeckillConsistencyException(
                            "重复订单已存在，但事件无法标记为 CONSUMED，eventId="
                                    + message.getEventId(),
                            exception
                    );
                }

                log.info(
                        "秒杀订单重复投递，按幂等成功处理，eventId={}，orderId={}",
                        message.getEventId(),
                        message.getOrderId()
                );
            } else {
                throw exception;
            }
        }

        // 订单事务已经提交；正常路径即时清理 Redis，定时对账只处理清理失败的少量异常。
        completeReservationAfterCommit(message);

        log.info(
                "秒杀订单处理完成，eventId={}，orderId={}",
                message.getEventId(),
                message.getOrderId()
        );
    }

    /**
     * 订单已提交后执行幂等的 Redis 预留清理。
     *
     * <p>清理失败不能回滚已提交订单，也不能让 RabbitMQ 重投已成功消息；
     * 保留 {@code reservation_completed_at} 为空，由对账任务继续兜底。</p>
     */
    private void completeReservationAfterCommit(SeckillOrderMessage message) {
        try {
            Long result = luaExecutor.completeReservation(
                    message.getVoucherId(),
                    message.getUserId(),
                    message.getEventId(),
                    message.getOrderId()
            );

            if (Long.valueOf(0L).equals(result)
                    || Long.valueOf(1L).equals(result)) {
                if (!eventService.markReservationCompleted(message.getEventId())) {
                    log.error(
                            "秒杀订单 Redis 预留已清理但无法写入完成标记，eventId={}",
                            message.getEventId()
                    );
                }
                return;
            }

            if (Long.valueOf(-2L).equals(result)) {
                log.error(
                        "秒杀订单 Redis 预留清理发生事件冲突，交由对账任务处理，eventId={}",
                        message.getEventId()
                );
                return;
            }

            log.warn(
                    "秒杀订单 Redis 预留清理返回未知结果，交由对账任务处理，eventId={}，result={}",
                    message.getEventId(),
                    result
            );
        } catch (Exception exception) {
            log.warn(
                    "秒杀订单 Redis 预留即时清理失败，交由对账任务重试，eventId={}",
                    message.getEventId(),
                    exception
            );
        }
    }

    /**
     * 拒绝字段缺失、非法 ID 或不受支持版本的永久性错误消息（不重试，直接进 DLQ）。
     */
    private void validate(SeckillOrderMessage message) {
        if (message == null
                || isBlank(message.getEventId())
                || message.getOrderId() == null
                || message.getOrderId() <= 0
                || message.getUserId() == null
                || message.getUserId() <= 0
                || message.getVoucherId() == null
                || message.getVoucherId() <= 0
                || message.getCreatedAt() == null
                || !Integer.valueOf(CURRENT_MESSAGE_VERSION)
                .equals(message.getVersion())) {
            throw new SeckillPermanentMessageException(
                    "秒杀订单消息格式或版本不受支持"
            );
        }
    }

    /** 判断字符串是否为 null、空串或纯空白。 */
    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
