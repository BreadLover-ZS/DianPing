package com.dish.review.mq;

import com.dish.review.entity.SeckillFailureCase;
import com.dish.review.entity.SeckillOrderEvent;
import com.dish.review.service.SeckillFailureCaseService;
import com.dish.review.service.SeckillOrderEventService;
import com.dish.review.utils.RabbitMqConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 消费 Broker DLQ 中的死信，补充失败记录证据（规格第 13 节）。
 *
 * <p>DLQ 只是运维副本，失败记录才是持久化事实。死信到达时：
 * 补充 x-death 与到达时间；前置失败记录不存在则补建并告警；
 * 事件已经 CONSUMED 的按幂等成功关闭失败记录。
 * 本消费者不恢复 Redis 库存，也不重发消息；
 * 数据库不可用时强制重新入队，保证运维副本和补充证据不丢失。</p>
 */
@Slf4j
@Component
public class SeckillOrderDeadLetterConsumer {

    private final SeckillFailureCaseService failureCaseService;

    private final SeckillOrderEventService eventService;

    /**
     * 注入失败记录服务和事件状态服务。
     */
    public SeckillOrderDeadLetterConsumer(
            SeckillFailureCaseService failureCaseService,
            SeckillOrderEventService eventService) {
        this.failureCaseService = failureCaseService;
        this.eventService = eventService;
    }

    /**
     * 读取死信并补充失败记录；接收原始 Message，避免再次触发消息转换。
     */
    @RabbitListener(
            queues = RabbitMqConstants.SECKILL_ORDER_DEAD_LETTER_QUEUE,
            containerFactory = "seckillDlqContainerFactory",
            autoStartup =
                    "${dish-review.seckill.rabbit-consumer-enabled:false}"
    )
    public void consume(Message message) {
        try {
            supplementFailureCase(message);
        } catch (Exception persistenceException) {
            log.error(
                    "[SECKILL_DLQ_PERSISTENCE_UNAVAILABLE] "
                            + "死信失败记录补充失败，强制重新入队，保留运维副本",
                    persistenceException
            );

            throw new ImmediateRequeueAmqpException(persistenceException);
        }
    }

    /**
     * 补充或补建失败记录，并按事件状态收敛。
     */
    private void supplementFailureCase(Message message) {
        String eventId = SeckillFailureEvidence.headerAsString(
                message,
                RabbitMqConstants.SECKILL_ORDER_EVENT_ID_HEADER
        );

        String idempotencyKey = SeckillFailureEvidence.idempotencyKey(
                message,
                eventId
        );

        SeckillFailureCase existing = failureCaseService
                .findByIdempotencyKey(idempotencyKey);

        // 前置失败记录不存在：补建（消息可能未经 Recoverer 直接死信）并告警
        if (existing == null) {
            SeckillFailureCase rebuilt =
                    SeckillFailureEvidence.from(message, null);

            rebuilt.setErrorCode("dlq_arrival_no_prior_record");
            rebuilt.setErrorMessage("DLQ 到达时前置失败记录不存在");
            rebuilt.setXDeathInfo(
                    SeckillFailureEvidence.xDeathSummary(message)
            );

            failureCaseService.supplementDlqArrival(rebuilt);

            log.error(
                    "死信到达但前置失败记录不存在，已补建并需要人工关注，"
                            + "idempotencyKey={}，eventId={}",
                    idempotencyKey,
                    eventId
            );

            closeIfEventConsumed(idempotencyKey, eventId);
            return;
        }

        // 已有失败记录：补充 x-death 与 DLQ 到达时间
        SeckillFailureCase supplement =
                SeckillFailureEvidence.from(message, null);

        supplement.setIdempotencyKey(idempotencyKey);
        supplement.setXDeathInfo(
                SeckillFailureEvidence.xDeathSummary(message)
        );

        failureCaseService.supplementDlqArrival(supplement);

        closeIfEventConsumed(idempotencyKey, eventId);
    }

    /**
     * 事件已经 CONSUMED：迟到死信按幂等成功关闭失败记录。
     */
    private void closeIfEventConsumed(
            String idempotencyKey,
            String eventId) {

        if (eventId == null || eventId.trim().isEmpty()) {
            log.warn(
                    "死信缺少 eventId，无法核对事件状态，保持失败记录 OPEN，"
                            + "idempotencyKey={}",
                    idempotencyKey
            );
            return;
        }

        SeckillOrderEvent event = eventService.findByEventId(eventId);

        if (event == null) {
            log.warn(
                    "死信对应事件不存在，保持失败记录 OPEN，eventId={}",
                    eventId
            );
            return;
        }

        if (event.getStatus() != SeckillOrderEvent.STATUS_CONSUMED) {
            log.warn(
                    "死信到达，事件状态={}，等待人工处置，eventId={}",
                    event.getStatus(),
                    eventId
            );
            return;
        }

        SeckillFailureCase failureCase = failureCaseService
                .findByIdempotencyKey(idempotencyKey);

        if (failureCase != null) {
            failureCaseService.closeAsIdempotentSuccess(
                    failureCase.getFailureId()
            );
        }

        log.info(
                "事件已 CONSUMED，死信按幂等成功关闭失败记录，eventId={}",
                eventId
        );
    }
}
