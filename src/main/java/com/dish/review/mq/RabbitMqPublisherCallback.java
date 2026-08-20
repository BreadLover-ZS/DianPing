package com.dish.review.mq;

import com.dish.review.service.SeckillOrderEventService;
import com.dish.review.service.SeckillVoucherLuaExecutor;
import com.dish.review.utils.RabbitMqConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * 处理生产者 Confirm 和 Return 回调，并在确定发布失败时回滚 Redis 预扣。
 */
@Slf4j
@Component
public class RabbitMqPublisherCallback
        implements RabbitTemplate.ConfirmCallback,
        RabbitTemplate.ReturnCallback {

    private final RabbitTemplate rabbitTemplate;

    private final SeckillVoucherLuaExecutor luaExecutor;

    private final SeckillOrderEventService eventService;

    /**
     * 注入 RabbitTemplate、Redis Lua 执行器和事件状态服务。
     */
    public RabbitMqPublisherCallback(
            RabbitTemplate rabbitTemplate,
            SeckillVoucherLuaExecutor luaExecutor,
            SeckillOrderEventService eventService) {
        this.rabbitTemplate = rabbitTemplate;
        this.luaExecutor = luaExecutor;
        this.eventService = eventService;
    }

    /**
     * Bean 初始化后向 RabbitTemplate 注册唯一的 Confirm 和 Return 回调。
     */
    @PostConstruct
    public void init() {
        rabbitTemplate.setConfirmCallback(this);
        rabbitTemplate.setReturnCallback(this);
    }

    /**
     * 处理交换机确认：ACK 标记 CONFIRMED；NACK 先标记 FAILED，再回滚 Redis。
     */
    @Override
    public void confirm(
            CorrelationData correlationData,
            boolean ack,
            String cause) {

        String eventId = correlationData == null
                ? "unknown"
                : correlationData.getId();

        if (ack) {
            log.debug("RabbitMQ 消息发布确认成功，eventId={}", eventId);
            markEventConfirmed(eventId);
            return;
        }

        String errorMessage = cause == null
                ? "confirm_nack"
                : "confirm_nack: " + cause;

        boolean failedRecorded = markEventFailed(
                eventId,
                errorMessage
        );

        if (failedRecorded) {
            rollbackReservation(
                    correlationData,
                    "confirm_nack"
            );
        }
        log.error(
                "RabbitMQ 消息发布确认失败，eventId={}, cause={}",
                eventId,
                cause
        );
    }

    /**
     * 处理不可路由消息：记录 Return 详情，标记 FAILED 后恢复 Redis 预扣。
     */
    @Override
    public void returnedMessage(
            Message message,
            int replyCode,
            String replyText,
            String exchange,
            String routingKey) {


        String eventId = headerAsString(
                message,
                RabbitMqConstants.SECKILL_ORDER_EVENT_ID_HEADER
        );

        Long orderId = headerAsLong(
                message,
                RabbitMqConstants.SECKILL_ORDER_ID_HEADER
        );

        Long userId = headerAsLong(
                message,
                RabbitMqConstants.SECKILL_ORDER_USER_ID_HEADER
        );

        Long voucherId = headerAsLong(
                message,
                RabbitMqConstants.SECKILL_ORDER_VOUCHER_ID_HEADER
        );

        String errorMessage =
                "returned: code=" + replyCode
                        + ", text=" + replyText
                        + ", exchange=" + exchange
                        + ", routingKey=" + routingKey;

        boolean failedRecorded = markEventFailed(
                eventId,
                errorMessage
        );

        if (failedRecorded) {
            rollbackReservation(
                    eventId,
                    orderId,
                    voucherId,
                    userId,
                    "returned"
            );
        }

        log.error(
                "RabbitMQ 消息无法路由，eventId={}, replyCode={}, replyText={}, exchange={}, routingKey={}",
                eventId,
                replyCode,
                replyText,
                exchange,
                routingKey
        );
    }

    /**
     * 从自定义 CorrelationData 读取业务 ID，并转交统一回滚方法。
     */
    private void rollbackReservation(
            CorrelationData correlationData,
            String reason) {

        if (!(correlationData instanceof SeckillOrderCorrelationData)) {
            log.error(
                    "无法回滚 Redis 秒杀预扣，缺少关联数据，reason={}",
                    reason
            );
            return;
        }

        SeckillOrderCorrelationData seckillData =
                (SeckillOrderCorrelationData) correlationData;

        rollbackReservation(
                seckillData.getId(),
                seckillData.getOrderId(),
                seckillData.getVoucherId(),
                seckillData.getUserId(),
                reason
        );
    }

    /**
     * 执行幂等回滚 Lua：移除用户预扣标记，且只在移除成功时恢复库存。
     */
    private void rollbackReservation(
            Object eventId,
            Object orderId,
            Long voucherId,
            Long userId,
            String reason) {
        if (voucherId == null || userId == null) {
            log.error(
                    "无法回滚 Redis 秒杀预扣，Header 或关联数据不完整，eventId={}，reason={}",
                    eventId,
                    reason
            );
            return;
        }

        try {
            Long result = luaExecutor.rollback(voucherId, userId);

            if (Long.valueOf(1L).equals(result)) {
                log.warn(
                        "Redis 秒杀预扣已回滚，eventId={}，orderId={}，reason={}",
                        eventId,
                        orderId,
                        reason
                );
            } else if (Long.valueOf(0L).equals(result)) {
                log.info(
                        "Redis 秒杀预扣无需重复回滚，eventId={}，reason={}",
                        eventId,
                        reason
                );
            } else {
                log.error(
                        "Redis 秒杀预扣回滚失败，库存 Key 不存在，eventId={}，reason={}",
                        eventId,
                        reason
                );
            }
        } catch (Exception exception) {
            log.error(
                    "Redis 秒杀预扣回滚异常，eventId={}，reason={}",
                    eventId,
                    reason,
                    exception
            );
        }
    }

    /**
     * 将数值型或字符串型 Header 安全转换为 Long。
     */
    private Long headerAsLong(
            Message message,
            String headerName) {

        Object value = message.getMessageProperties()
                .getHeaders()
                .get(headerName);

        if (value instanceof Number) {
            return ((Number) value).longValue();
        }

        if (value instanceof String) {
            try {
                return Long.valueOf((String) value);
            } catch (NumberFormatException exception) {
                return null;
            }
        }

        return null;
    }

    /**
     * 将指定 Header 转换为字符串；不存在时返回 null。
     */
    private String headerAsString(
            Message message,
            String headerName) {

        Object value = message.getMessageProperties()
                .getHeaders()
                .get(headerName);

        return value == null ? null : value.toString();
    }

    /**
     * 尽力把事件标记为 CONFIRMED；失败只记录日志，等待初始租约触发补偿。
     */
    private void markEventConfirmed(String eventId) {
        try {
            boolean marked = eventService.markConfirmed(eventId);

            if (!marked) {
                log.error(
                        "RabbitMQ Confirm ACK，但事件状态无法更新，eventId={}",
                        eventId
                );
            }
        } catch (Exception exception) {
            log.error(
                    "RabbitMQ Confirm ACK，更新事件状态异常，eventId={}",
                    eventId,
                    exception
            );
        }
    }

    /**
     * 尽力持久化 FAILED；只有保存成功时，调用方才允许回滚 Redis。
     */
    private boolean markEventFailed(
            String eventId,
            String errorMessage) {
        try {
            boolean marked = eventService.markFailed(
                    eventId,
                    errorMessage
            );

            if (!marked) {
                log.error(
                        "RabbitMQ 发布失败，但事件状态无法更新，eventId={}",
                        eventId
                );
            }

            return marked;
        } catch (Exception exception) {
            log.error(
                    "RabbitMQ 发布失败，更新事件状态异常，eventId={}",
                    eventId,
                    exception
            );
            return false;
        }
    }
}
