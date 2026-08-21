package com.dish.review.mq;

import com.dish.review.service.SeckillPublishAttemptService;
import com.dish.review.utils.RabbitMqConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * 处理不可路由消息的 Return 回调。
 *
 * <p>Return 只按 Header 幂等记录 returned=true 证据，
 * 不单独决定事件回滚，也不修改任何 Redis 状态；
 * Confirm Future 完成时由统一失败决策服务核对全部证据后做决定。</p>
 */
@Slf4j
@Component
public class RabbitMqPublisherCallback
        implements RabbitTemplate.ReturnCallback {

    private final RabbitTemplate rabbitTemplate;

    private final SeckillPublishAttemptService attemptService;

    /**
     * 注入 RabbitTemplate 和发布尝试证据服务。
     */
    public RabbitMqPublisherCallback(
            RabbitTemplate rabbitTemplate,
            SeckillPublishAttemptService attemptService) {

        this.rabbitTemplate = rabbitTemplate;
        this.attemptService = attemptService;
    }

    /**
     * Bean 初始化后向 RabbitTemplate 注册 Return 回调。
     */
    @PostConstruct
    public void init() {
        rabbitTemplate.setReturnCallback(this);
    }

    /**
     * 记录一次不可路由消息的证据；Confirm 结果由 Future 处理器统一决策。
     */
    @Override
    public void returnedMessage(
            Message message,
            int replyCode,
            String replyText,
            String exchange,
            String routingKey) {

        String attemptId = headerAsString(
                message,
                RabbitMqConstants.SECKILL_ORDER_ATTEMPT_ID_HEADER
        );

        String eventId = headerAsString(
                message,
                RabbitMqConstants.SECKILL_ORDER_EVENT_ID_HEADER
        );

        try {
            if (attemptId != null && !attemptId.trim().isEmpty()) {
                boolean recorded =
                        attemptService.recordReturned(attemptId);

                if (!recorded) {
                    log.warn(
                            "RabbitMQ Return 证据记录未命中（尝试可能已收敛），"
                                    + "eventId={}，attemptId={}",
                            eventId,
                            attemptId
                    );
                }
            } else {
                log.error(
                        "RabbitMQ 消息无法路由且缺少 attemptId Header，eventId={}",
                        eventId
                );
            }
        } catch (Exception exception) {
            log.error(
                    "RabbitMQ Return 证据记录异常，eventId={}，attemptId={}",
                    eventId,
                    attemptId,
                    exception
            );
        }

        log.error(
                "RabbitMQ 消息无法路由，eventId={}，attemptId={}，replyCode={}，"
                        + "replyText={}，exchange={}，routingKey={}",
                eventId,
                attemptId,
                replyCode,
                replyText,
                exchange,
                routingKey
        );
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
}
