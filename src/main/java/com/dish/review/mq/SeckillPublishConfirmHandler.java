package com.dish.review.mq;

import com.dish.review.service.SeckillOrderEventService;
import com.dish.review.service.SeckillOrderFailureDecisionService;
import com.dish.review.service.SeckillPublishAttemptService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.stereotype.Component;

/**
 * Confirm 结果处理器：每次发送的 CorrelationData Future 完成时统一处理。
 *
 * <p>Future 完成时同时读取 Confirm 结果和 ReturnedMessage：
 * ACK 且未退回 → 尝试推进 CONFIRMED；
 * ACK 且退回、NACK → 记录证据后交给统一失败决策服务；
 * Future 异常 → 记录 UNKNOWN 并安排补偿发布。</p>
 *
 * <p>本类只落发送证据和调用决策服务，禁止直接修改 Redis。</p>
 */
@Slf4j
@Component
public class SeckillPublishConfirmHandler {

    private final SeckillPublishAttemptService attemptService;

    private final SeckillOrderEventService eventService;

    private final SeckillOrderFailureDecisionService decisionService;

    /**
     * 注入发布尝试服务、事件服务和统一失败决策服务。
     */
    public SeckillPublishConfirmHandler(
            SeckillPublishAttemptService attemptService,
            SeckillOrderEventService eventService,
            SeckillOrderFailureDecisionService decisionService) {

        this.attemptService = attemptService;
        this.eventService = eventService;
        this.decisionService = decisionService;
    }

    /**
     * 为一次发送挂接 Confirm Future 监听。
     * Future 已完成时回调立即执行，不存在丢失窗口。
     */
    public void attach(SeckillOrderCorrelationData correlationData) {
        correlationData.getFuture().addCallback(
                confirm -> handleConfirm(correlationData, confirm),
                ex -> handleFutureFailure(correlationData, ex)
        );
    }

    /**
     * 处理一次已完成的 Confirm。
     */
    private void handleConfirm(
            SeckillOrderCorrelationData correlationData,
            CorrelationData.Confirm confirm) {

        String attemptId = correlationData.getId();
        String eventId = correlationData.getEventId();

        try {
            if (confirm == null || !confirm.isAck()) {
                String reason = confirm == null
                        ? "confirm_missing"
                        : confirm.getReason();

                // NACK：Broker 明确拒绝承担该次消息
                attemptService.recordNack(attemptId, reason);

                log.error(
                        "RabbitMQ 发布确认 NACK，eventId={}，attemptId={}，reason={}",
                        eventId,
                        attemptId,
                        reason
                );

                decisionService.evaluateAfterConfirm(eventId);
                return;
            }

            boolean returned =
                    correlationData.getReturnedMessage() != null;

            attemptService.recordAck(attemptId);

            if (returned) {
                // 交换机收到消息但没有路由到队列：该次尝试明确未投递
                attemptService.recordReturned(attemptId);

                log.error(
                        "RabbitMQ 消息被退回（ACK + Return），eventId={}，attemptId={}",
                        eventId,
                        attemptId
                );

                decisionService.evaluateAfterConfirm(eventId);
                return;
            }

            // ACK 且未退回：消息已被目标队列接收
            boolean confirmed = eventService.markConfirmed(eventId);

            if (!confirmed) {
                // 事件可能已被消费推进为 CONSUMED 等状态，按幂等成功处理
                log.debug(
                        "RabbitMQ Confirm ACK，事件状态已推进，eventId={}",
                        eventId
                );
            }
        } catch (Exception exception) {
            // 回调内数据库更新失败：依靠 WAITING 尝试超时扫描补偿
            log.error(
                    "RabbitMQ Confirm 结果处理异常，eventId={}，attemptId={}",
                    eventId,
                    attemptId,
                    exception
            );
        }
    }

    /**
     * Future 本身异常（极少见）：结果未知，记录证据并安排补偿。
     */
    private void handleFutureFailure(
            SeckillOrderCorrelationData correlationData,
            Throwable cause) {

        String attemptId = correlationData.getId();
        String eventId = correlationData.getEventId();

        try {
            attemptService.recordUnknown(
                    attemptId,
                    "confirm_future_error",
                    cause == null ? null : cause.getMessage()
            );

            decisionService.evaluateForRetry(
                    eventId,
                    "confirm_future_error",
                    cause == null ? null : cause.getMessage()
            );
        } catch (Exception exception) {
            log.error(
                    "RabbitMQ Confirm Future 异常处理失败，eventId={}，attemptId={}",
                    eventId,
                    attemptId,
                    exception
            );
        }
    }
}
