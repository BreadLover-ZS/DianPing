package com.dish.review.mq;

import com.dish.review.dto.SeckillOrderMessage;
import com.dish.review.entity.SeckillOrderEvent;
import com.dish.review.entity.SeckillPublishAttempt;
import com.dish.review.service.SeckillFailureCaseService;
import com.dish.review.service.SeckillOrderEventService;
import com.dish.review.service.SeckillOrderFailureDecisionService;
import com.dish.review.service.SeckillPublishAttemptService;
import com.dish.review.service.SeckillPublishRetryPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.List;
import java.util.UUID;

/**
 * Outbox 发布任务：秒杀消息的唯一生产者入口。
 *
 * <p>请求线程只写事件，本任务负责扫描到期事件、抢占租约、
 * 在事务内创建发布尝试，然后调用一次 RabbitTemplate.convertAndSend()。
 * 同步异常只更新本次尝试并把事件标记为 PUBLISH_UNKNOWN（结果未知，禁止回滚）；
 * 正常发送后按退避推迟 next_retry_time，等待 Confirm 回调推进状态。</p>
 *
 * <p>最后一次发送后不立即转人工：先推迟一个终局等待窗口
 * （必须大于确认超时时间），让 Confirm/超时任务有机会收敛结果；
 * 窗口到期仍无进展时才在扫描阶段转 MANUAL_REVIEW 并写失败记录。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "dish-review.seckill.tasks-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class SeckillOrderPublishRetryTask {

    private final SeckillOrderEventService eventService;

    private final SeckillPublishAttemptService attemptService;

    private final SeckillOrderFailureDecisionService decisionService;

    private final SeckillOrderPublisher orderPublisher;

    private final SeckillFailureCaseService failureCaseService;

    /**
     * 每轮扫描的最多事件数。
     */
    @Value("${dish-review.seckill.outbox.batch-size:20}")
    private int batchSize;

    /**
     * 租约时长（秒）：必须大于单次发送的最大耗时。
     */
    @Value("${dish-review.seckill.outbox.lease-seconds:60}")
    private int leaseSeconds;

    /**
     * 当前实例的租约持有者标识。
     */
    private final String owner;

    /**
     * 注入事件服务、发布尝试服务、决策服务、生产者和失败记录服务；生成实例唯一租约标识。
     */
    public SeckillOrderPublishRetryTask(
            SeckillOrderEventService eventService,
            SeckillPublishAttemptService attemptService,
            SeckillOrderFailureDecisionService decisionService,
            SeckillOrderPublisher orderPublisher,
            SeckillFailureCaseService failureCaseService) {

        this.eventService = eventService;
        this.attemptService = attemptService;
        this.decisionService = decisionService;
        this.orderPublisher = orderPublisher;
        this.failureCaseService = failureCaseService;
        this.owner = buildOwner();
    }

    /**
     * 周期扫描到期事件并发布；快速退避 1/2/4 秒，慢速补偿到 30 分钟。
     */
    @Scheduled(
            fixedDelayString = "${dish-review.seckill.publish-retry-delay:1000}"
    )
    public void publishDueEvents() {
        List<SeckillOrderEvent> events;

        try {
            events = eventService.findDueForPublish(batchSize);
        } catch (Exception exception) {
            log.error("Outbox 扫描到期秒杀订单事件失败", exception);
            return;
        }

        for (SeckillOrderEvent event : events) {
            try {
                publishOneEvent(event);
            } catch (Exception exception) {
                log.error(
                        "Outbox 发布秒杀订单事件异常，eventId={}",
                        event.getEventId(),
                        exception
                );
            }
        }
    }

    /**
     * 单个事件的完整发布流程：抢占租约 → （耗尽检查）→ 创建尝试 → 发送一次 → 退避。
     */
    private void publishOneEvent(SeckillOrderEvent event) {
        String eventId = event.getEventId();

        Long leaseToken = eventService.claimLease(
                eventId,
                owner,
                leaseSeconds
        );

        if (leaseToken == null) {
            // 其他实例已抢占，或事件状态已经收敛
            return;
        }

        try {
            int completedAttempts = event.getRetryCount() == null
                    ? 0
                    : event.getRetryCount();

            if (completedAttempts
                    >= SeckillPublishRetryPolicy.maxAutomaticAttempts()) {
                /*
                 * 自动发送次数已耗尽且终局等待窗口已过（事件仍可被扫描到
                 * 说明 Confirm/消费/失败决策都未推进状态）：
                 * 在这里统一转 MANUAL_REVIEW 并事务性写 SOURCE_PUBLISH 失败记录，
                 * 保证人工处置入口必然存在。
                 */
                escalateExhaustedEvent(event);
                return;
            }

            SeckillPublishAttempt attempt;

            try {
                attempt = attemptService.createNextAttempt(eventId);
            } catch (Exception exception) {
                // 事件不可发布（状态已变化）或数据库故障；租约到期后自然恢复
                log.warn(
                        "Outbox 创建发布尝试失败，eventId={}",
                        eventId,
                        exception
                );
                return;
            }

            SeckillOrderMessage message = toMessage(event);

            try {
                // 一次调度只调用一次 convertAndSend；模板内部重试已关闭
                orderPublisher.send(attempt, message);
            } catch (Exception exception) {
                /*
                 * 同步异常：消息可能已经到达 Broker，也可能没有。
                 * 只把本次尝试记为 UNKNOWN，事件转 PUBLISH_UNKNOWN 并退避，
                 * 禁止回滚 Redis。
                 */
                recordSyncFailure(attempt, exception);
                return;
            }

            deferNextRetry(eventId, attempt);
        } finally {
            // 发布调用结束即释放租约；进程崩溃依靠租约过期重新领取
            eventService.releaseLease(eventId, leaseToken);
        }
    }

    /**
     * 耗尽自动发送次数的事件转人工，并在同一事务内写 SOURCE_PUBLISH 失败记录。
     */
    private void escalateExhaustedEvent(SeckillOrderEvent event) {
        boolean escalated = failureCaseService.recordManualReviewEscalation(
                event,
                com.dish.review.entity.SeckillFailureCase.SOURCE_PUBLISH,
                "publish_retry_exhausted",
                "publish attempts exhausted after "
                        + SeckillPublishRetryPolicy.maxAutomaticAttempts()
                        + " sends and final decision window elapsed"
        );

        log.error(
                "[SECKILL_PUBLISH_EXHAUSTED] "
                        + "Outbox 自动发布次数耗尽且终局窗口内未收敛，"
                        + "已转人工并写失败记录，eventId={}，escalated={}",
                event.getEventId(),
                escalated
        );
    }

    /**
     * 记录同步发送异常证据，并交由统一失败决策服务处理。
     */
    private void recordSyncFailure(
            SeckillPublishAttempt attempt,
            Exception exception) {

        String errorMessage = exception.getMessage();

        try {
            attemptService.recordUnknown(
                    attempt.getAttemptId(),
                    "send_exception",
                    errorMessage
            );
        } catch (Exception recordException) {
            log.error(
                    "Outbox 记录同步异常尝试失败，attemptId={}，eventId={}",
                    attempt.getAttemptId(),
                    attempt.getEventId(),
                    recordException
            );
        }

        // 是否重发或回滚由统一失败决策服务决定；未知结果禁止自动回滚
        SeckillOrderFailureDecisionService.Decision decision =
                decisionService.evaluateForRetry(
                        attempt.getEventId(),
                        "send_exception",
                        errorMessage
                );

        log.warn(
                "Outbox 发布同步异常，结果未知，eventId={}，attemptId={}，decision={}",
                attempt.getEventId(),
                attempt.getAttemptId(),
                decision,
                exception
        );
    }

    /**
     * 正常发送后按退避推迟 next_retry_time，等待 Confirm 回调推进状态。
     *
     * <p>本次发送是最后一次（退避表耗尽）时，推迟的时长换成终局等待窗口
     * （大于确认超时时间）：窗口内 Confirm/消费仍可收敛事件；
     * 到期后仍无进展时由下一轮扫描的耗尽检查统一转人工，
     * 禁止在 send() 刚返回就停止自动流程。</p>
     */
    private void deferNextRetry(
            String eventId,
            SeckillPublishAttempt attempt) {

        long delaySeconds = SeckillPublishRetryPolicy
                .nextDelaySeconds(attempt.getAttemptNo());

        if (delaySeconds
                == SeckillPublishRetryPolicy.STOP_AUTOMATIC_RETRY) {
            delaySeconds =
                    SeckillPublishRetryPolicy.FINAL_DECISION_WAIT_SECONDS;
        }

        boolean deferred = eventService.deferNextRetryTime(
                eventId,
                delaySeconds
        );

        if (deferred) {
            log.info(
                    "Outbox 已发布秒杀订单事件，等待 Confirm，eventId={}，attemptId={}，nextRetryDelay={}s",
                    eventId,
                    attempt.getAttemptId(),
                    delaySeconds
            );
        } else {
            // 状态已被 Confirm 回调推进（如 CONFIRMED），按幂等成功处理
            log.debug(
                    "Outbox 推迟重试未命中，事件状态已推进，eventId={}",
                    eventId
            );
        }
    }

    /**
     * 用持久化事件重建原消息，保持 eventId 和 orderId 不变以支持幂等。
     */
    private SeckillOrderMessage toMessage(SeckillOrderEvent event) {
        return new SeckillOrderMessage(
                event.getEventId(),
                event.getOrderId(),
                event.getUserId(),
                event.getVoucherId(),
                event.getCreatedAt(),
                event.getMessageVersion()
        );
    }

    /**
     * 生成实例唯一的租约持有者标识：主机名 + 随机后缀。
     */
    private String buildOwner() {
        String hostName;

        try {
            hostName = InetAddress.getLocalHost().getHostName();
        } catch (Exception exception) {
            hostName = "unknown-host";
        }

        return hostName + ":" + UUID.randomUUID();
    }
}
