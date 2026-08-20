package com.dish.review.config;

import com.dish.review.service.SeckillOrderEventService;
import com.dish.review.utils.RabbitMqConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.autoconfigure.amqp.RabbitRetryTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.policy.SimpleRetryPolicy;

import java.util.HashMap;
import java.util.Map;

/**
 * 声明秒杀订单的 RabbitMQ 拓扑、序列化、消费重试和失败恢复策略。
 */
@Configuration
@Slf4j
public class RabbitMqConfig {
    /**
     * 创建持久化 Direct 主交换机，按精确路由键分发秒杀订单。
     */
    @Bean
    public DirectExchange seckillOrderExchange() {
        return ExchangeBuilder
                .directExchange(RabbitMqConstants.SECKILL_ORDER_EXCHANGE)
                .durable(true)
                .build();
    }

    /**
     * 创建持久化死信交换机，接收主队列拒绝的消息。
     */
    @Bean
    public DirectExchange seckillOrderDeadLetterExchange() {
        return ExchangeBuilder
                .directExchange(RabbitMqConstants.SECKILL_ORDER_DEAD_LETTER_EXCHANGE)
                .durable(true)
                .build();
    }


    /**
     * 秒杀订单主队列。
     * <p>
     * 消息被拒绝且不重新入队时，转发到死信交换机。
     */
    @Bean
    public Queue seckillOrderQueue() {
        return QueueBuilder
                .durable(RabbitMqConstants.SECKILL_ORDER_QUEUE)
                .deadLetterExchange(
                        RabbitMqConstants.SECKILL_ORDER_DEAD_LETTER_EXCHANGE
                )
                .deadLetterRoutingKey(
                        RabbitMqConstants.SECKILL_ORDER_DEAD_LETTER_ROUTING_KEY
                )
                .build();
    }

    /**
     * 创建持久化死信队列，保留最终无法处理的秒杀订单消息。
     */
    @Bean
    public Queue seckillOrderDeadLetterQueue() {
        return QueueBuilder
                .durable(RabbitMqConstants.SECKILL_ORDER_DEAD_LETTER_QUEUE)
                .build();
    }


    /**
     * 将秒杀订单主队列绑定到主交换机。
     */
    @Bean
    public Binding seckillOrderBinding(
            @Qualifier("seckillOrderQueue") Queue queue,
            @Qualifier("seckillOrderExchange") DirectExchange exchange) {
        return BindingBuilder
                .bind(queue)
                .to(exchange)
                .with(RabbitMqConstants.SECKILL_ORDER_ROUTING_KEY);
    }

    /**
     * 将秒杀订单死信队列绑定到死信交换机。
     */
    @Bean
    public Binding seckillOrderDeadLetterBinding(
            @Qualifier("seckillOrderDeadLetterQueue") Queue queue,
            @Qualifier("seckillOrderDeadLetterExchange") DirectExchange exchange) {
        return BindingBuilder
                .bind(queue)
                .to(exchange)
                .with(RabbitMqConstants.SECKILL_ORDER_DEAD_LETTER_ROUTING_KEY);
    }

    /**
     * 使用 JSON 在 Java 消息对象和 RabbitMQ 消息体之间转换。
     */
    @Bean
    public MessageConverter rabbitMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * 监听重试耗尽后，先把事件标记为 FAILED，再拒绝消息；
     * 队列的死信配置随后会把消息转发到 DLQ。
     */
    @Bean
    public MessageRecoverer seckillOrderMessageRecoverer(
            SeckillOrderEventService eventService) {
        RejectAndDontRequeueRecoverer delegate =
                new RejectAndDontRequeueRecoverer();

        return (message, cause) -> {
            String eventId = headerAsString(
                    message,
                    RabbitMqConstants.SECKILL_ORDER_EVENT_ID_HEADER
            );

            if (eventId != null && !eventId.trim().isEmpty()) {
                boolean marked = eventService.markFailed(
                        eventId,
                        "consumer_retry_exhausted: "
                                + summarize(cause)
                );

                if (!marked) {
                    log.error(
                            "消费者重试耗尽，但事件无法标记为 FAILED，eventId={}",
                            eventId
                    );
                }
            } else {
                log.error("消费者重试耗尽，但消息缺少 eventId");
            }

            delegate.recover(message, cause);
        };
    }

    /**
     * Spring Boot 默认的 RabbitMQ Retry 只能统一设置重试次数。
     * 通过 RabbitRetryTemplateCustomizer 自定义监听器的 RetryPolicy：消息格式和版本错误属于永久性异常，不进行重试；
     * 数据库临时故障等其他异常默认执行有限退避重试。
     * 重试耗尽或不可重试的消息最终被拒绝，并通过队列的死信配置进入 DLQ。
     */
    @Bean
    public RabbitRetryTemplateCustomizer rabbitListenerRetryCustomizer(
            RabbitProperties rabbitProperties) {

        int maxAttempts = rabbitProperties
                .getListener()
                .getSimple()
                .getRetry()
                .getMaxAttempts();

        return (target, retryTemplate) -> {
            if (target != RabbitRetryTemplateCustomizer.Target.LISTENER) {
                return;
            }

            Map<Class<? extends Throwable>, Boolean> retryableExceptions =
                    new HashMap<>();

            retryableExceptions.put(
                    AmqpRejectAndDontRequeueException.class,
                    false
            );

            retryTemplate.setRetryPolicy(
                    new SimpleRetryPolicy(
                            maxAttempts,          // 从 application.yaml 读取，当前为 4
                            retryableExceptions,  // 特殊异常分类
                            true,                 // 检查被包装异常的 cause
                            true                  // 未单独配置的异常默认允许重试
                    )
            );
        };
    }

    /**
     * 从消息 Header 中读取字符串值，用于定位对应的事件记录。
     */
    private String headerAsString(Message message, String headerName) {
        Object value = message.getMessageProperties()
                .getHeaders()
                .get(headerName);
        return value == null ? null : value.toString();
    }

    /**
     * 截断异常摘要，避免 last_error 字段保存过长内容。
     */
    private String summarize(Throwable cause) {
        if (cause == null || cause.getMessage() == null) {
            return "unknown";
        }

        String message = cause.getMessage();
        return message.length() <= 400
                ? message
                : message.substring(0, 400);
    }
}
