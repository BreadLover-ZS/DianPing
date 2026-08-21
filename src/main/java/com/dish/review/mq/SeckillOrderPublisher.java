package com.dish.review.mq;

import com.dish.review.dto.SeckillOrderMessage;
import com.dish.review.entity.SeckillPublishAttempt;
import com.dish.review.utils.RabbitMqConstants;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 将秒杀订单事件转换为持久化 RabbitMQ 消息并发送到主交换机。
 *
 * <p>每次调用只执行一次 convertAndSend，不做任何重试；
 * 重试统一由 Outbox 发布任务控制。发送前后也不修改事件状态，
 * Confirm/Return 证据由回调结果处理器落库。</p>
 */
@Component
public class SeckillOrderPublisher {

    private final RabbitTemplate rabbitTemplate;

    private final SeckillPublishConfirmHandler confirmHandler;

    /**
     * 注入 RabbitTemplate 和 Confirm 结果处理器；模板内部重试已在配置中关闭。
     */
    public SeckillOrderPublisher(
            RabbitTemplate rabbitTemplate,
            SeckillPublishConfirmHandler confirmHandler) {

        this.rabbitTemplate = rabbitTemplate;
        this.confirmHandler = confirmHandler;
    }

    /**
     * 发送一次消息：attemptId 作为 CorrelationData 关联 ID，
     * 业务 ID 只写入 Header，发送失败由调用方记录证据。
     */
    public void send(
            SeckillPublishAttempt attempt,
            SeckillOrderMessage message) {

        validate(attempt, message);

        SeckillOrderCorrelationData correlationData =
                new SeckillOrderCorrelationData(
                        attempt.getAttemptId(),
                        message.getEventId(),
                        message.getOrderId()
                );

        rabbitTemplate.convertAndSend(
                RabbitMqConstants.SECKILL_ORDER_EXCHANGE,
                RabbitMqConstants.SECKILL_ORDER_ROUTING_KEY,
                message,
                amqpMessage -> {
                    amqpMessage.getMessageProperties().setMessageId(
                            message.getEventId()
                    );
                    amqpMessage.getMessageProperties().setHeader(
                            RabbitMqConstants.SECKILL_ORDER_EVENT_ID_HEADER,
                            message.getEventId()
                    );
                    amqpMessage.getMessageProperties().setHeader(
                            RabbitMqConstants.SECKILL_ORDER_ATTEMPT_ID_HEADER,
                            attempt.getAttemptId()
                    );
                    amqpMessage.getMessageProperties().setHeader(
                            RabbitMqConstants.SECKILL_ORDER_ID_HEADER,
                            message.getOrderId()
                    );
                    amqpMessage.getMessageProperties().setHeader(
                            RabbitMqConstants.SECKILL_ORDER_USER_ID_HEADER,
                            message.getUserId()
                    );
                    amqpMessage.getMessageProperties().setHeader(
                            RabbitMqConstants.SECKILL_ORDER_VOUCHER_ID_HEADER,
                            message.getVoucherId()
                    );
                    amqpMessage.getMessageProperties().setDeliveryMode(
                            MessageDeliveryMode.PERSISTENT
                    );
                    return amqpMessage;
                },
                correlationData
        );

        // 监听本次发送的 Confirm Future；已完成时回调立即执行
        confirmHandler.attach(correlationData);
    }

    /**
     * 阻止缺少业务主键、事件 ID、尝试 ID 或版本的消息进入队列。
     */
    private void validate(
            SeckillPublishAttempt attempt,
            SeckillOrderMessage message) {

        if (attempt == null
                || attempt.getAttemptId() == null
                || attempt.getAttemptId().trim().isEmpty()) {
            throw new IllegalArgumentException("秒杀订单发布尝试 ID 不能为空");
        }

        if (message == null
                || message.getEventId() == null
                || message.getEventId().trim().isEmpty()
                || message.getOrderId() == null
                || message.getUserId() == null
                || message.getVoucherId() == null
                || message.getCreatedAt() == null
                || message.getVersion() == null) {
            throw new IllegalArgumentException("秒杀订单消息关键字段不能为空");
        }
    }
}
