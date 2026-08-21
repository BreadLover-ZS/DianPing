package com.dish.review.mq;

import com.dish.review.dto.SeckillOrderMessage;
import com.dish.review.exception.SeckillConsistencyException;
import com.dish.review.exception.SeckillPermanentMessageException;
import com.dish.review.service.SeckillOrderEventService;
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

    /**
     * 注入订单事务处理器和事件状态服务。
     */
    public SeckillOrderConsumer(
            VoucherOrderHandler voucherOrderHandler,
            SeckillOrderEventService eventService) {
        this.voucherOrderHandler = voucherOrderHandler;
        this.eventService = eventService;
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

                return;
            }

            throw exception;
        }

        log.info(
                "秒杀订单处理完成，eventId={}，orderId={}",
                message.getEventId(),
                message.getOrderId()
        );
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
