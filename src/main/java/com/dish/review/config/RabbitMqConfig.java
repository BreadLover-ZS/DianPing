package com.dish.review.config;

import com.dish.review.entity.SeckillFailureCase;
import com.dish.review.exception.SeckillConsistencyException;
import com.dish.review.exception.SeckillPermanentMessageException;
import com.dish.review.mq.SeckillFailureEvidence;
import com.dish.review.mq.SeckillRabbitListenerErrorHandler;
import com.dish.review.service.SeckillFailureCaseService;
import com.dish.review.utils.RabbitMqConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.autoconfigure.amqp.RabbitRetryTemplateCustomizer;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.policy.SimpleRetryPolicy;

import java.util.HashMap;
import java.util.Map;

/**
 * 声明秒杀订单的 RabbitMQ 拓扑、序列化、消费重试和失败恢复策略（规格第 9、13 节）。
 *
 * <p>失败闭环顺序：重试耗尽或不可重试的消息先由 Recoverer 在独立事务中
 * 持久化失败记录并推进事件状态，事务提交后才拒绝消息进入 Broker DLQ；
 * 失败记录落库失败时抛 {@link ImmediateRequeueAmqpException} 强制重新入队，
 * 禁止 ACK 或丢弃原消息。Listener 前的转换失败由容器 ErrorHandler 兜底。</p>
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
     * 覆盖 Boot 默认监听容器工厂：在保留 yaml 重试与 Acknowledge 配置的基础上
     * 注册自定义 ErrorHandler，兜底 Listener 前的消息转换失败（规格第 9.2 节）。
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            SeckillFailureCaseService failureCaseService) {

        SimpleRabbitListenerContainerFactory factory =
                new SimpleRabbitListenerContainerFactory();

        configurer.configure(factory, connectionFactory);

        factory.setErrorHandler(
                new SeckillRabbitListenerErrorHandler(failureCaseService)
        );

        return factory;
    }

    /**
     * DLQ 消费者专用容器工厂：不带重试拦截，异常时保持默认重新入队，
     * 保证死信运维副本和失败记录补充不会因重试耗尽被丢弃。
     */
    @Bean
    public SimpleRabbitListenerContainerFactory seckillDlqContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory) {

        SimpleRabbitListenerContainerFactory factory =
                new SimpleRabbitListenerContainerFactory();

        configurer.configure(factory, connectionFactory);

        // 清空 yaml 中启用的监听重试拦截：DLQ 消费失败直接重新入队
        factory.setAdviceChain();
        factory.setDefaultRequeueRejected(true);

        return factory;
    }

    /**
     * 消费重试耗尽或不可重试后的统一入口（规格第 13 节）：
     * 先在独立 Service 事务内幂等写入失败记录并把事件推进为 DLQ/MANUAL_REVIEW，
     * 事务提交后再调用拒绝 Recoverer 让原消息进入 Broker DLQ；
     * 失败记录落库失败时抛 {@link ImmediateRequeueAmqpException} 强制重新入队。
     */
    @Bean
    public MessageRecoverer seckillOrderMessageRecoverer(
            SeckillFailureCaseService failureCaseService) {
        RejectAndDontRequeueRecoverer delegate =
                new RejectAndDontRequeueRecoverer();

        return (message, cause) -> {
            SeckillFailureCase evidence =
                    SeckillFailureEvidence.from(message, cause);
            boolean consistencyConflict =
                    SeckillFailureEvidence.isConsistencyConflict(cause);

            try {
                failureCaseService.recordConsumerDlqFailure(
                        evidence,
                        consistencyConflict
                );
            } catch (Exception persistenceException) {
                log.error(
                        "[SECKILL_DLQ_PERSISTENCE_UNAVAILABLE] "
                                + "消费失败记录持久化失败，强制重新入队，"
                                + "idempotencyKey={}，eventId={}，errorCode={}",
                        evidence.getIdempotencyKey(),
                        evidence.getEventId(),
                        evidence.getErrorCode(),
                        persistenceException
                );

                throw new ImmediateRequeueAmqpException(persistenceException);
            }

            log.error(
                    "消费重试耗尽，失败记录已持久化，拒绝消息进入 DLQ，"
                            + "eventId={}，orderId={}，consistencyConflict={}，errorCode={}",
                    evidence.getEventId(),
                    evidence.getOrderId(),
                    consistencyConflict,
                    evidence.getErrorCode()
            );

            delegate.recover(message, cause);
        };
    }

    /**
     * 监听重试模板的异常分类（规格第 9.2 节）：
     * 永久消息错误、一致性冲突和消息转换错误不重试；
     * 数据库连接、超时、死锁等临时故障（含 SeckillRetryableException）
     * 按 yaml 配置执行有限退避重试，耗尽后交给 Recoverer。
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

            // 永久消息错误：不重试，直接 DLQ
            retryableExceptions.put(
                    SeckillPermanentMessageException.class,
                    false
            );

            // 一致性冲突：不做相同业务重试，持久化失败记录转人工
            retryableExceptions.put(
                    SeckillConsistencyException.class,
                    false
            );

            // Listener 前反序列化/转换失败：不重试
            retryableExceptions.put(
                    org.springframework.amqp.support.converter
                            .MessageConversionException.class,
                    false
            );
            retryableExceptions.put(
                    org.springframework.messaging.converter
                            .MessageConversionException.class,
                    false
            );

            // 显式拒绝异常：不重试
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
}
