package com.dish.review.mq;

import com.dish.review.dto.SeckillOrderMessage;
import com.dish.review.utils.RabbitMqConstants;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 将秒杀订单事件转换为持久化 RabbitMQ 消息并发送到主交换机。
 */
@Component
public class SeckillOrderPublisher {

    private final RabbitTemplate rabbitTemplate;

    /**
     * 注入统一的 RabbitTemplate，复用项目配置的 JSON 转换和发布确认。
     */
    public SeckillOrderPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 校验消息后发布，并写入回调定位所需的 CorrelationData 和 Header。
     */
    public void publish(SeckillOrderMessage message) {
        validate(message);

        SeckillOrderCorrelationData correlationData =
                new SeckillOrderCorrelationData(message);

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
                    amqpMessage.getMessageProperties().setDeliveryMode(
                            MessageDeliveryMode.PERSISTENT
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
                    return amqpMessage;
                },
                correlationData
        );


    }

    /**
     * 阻止缺少业务主键、事件 ID 或版本的消息进入队列。
     */
    private void validate(SeckillOrderMessage message) {
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
