package com.dish.review.service;

import com.dish.review.entity.SeckillOrderEvent;
import com.dish.review.entity.SeckillPublishAttempt;
import com.dish.review.entity.VoucherOrder;
import com.dish.review.mapper.VoucherOrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 统一失败决策服务：Confirm、Return、发送异常、确认超时、DLQ 和对账任务
 * 只记录证据，是否重发或回滚由本服务统一决定。
 *
 * <p>拆成两层：{@link #decide(DecisionSnapshot)} 是纯决策函数，
 * 只根据订单、事件、尝试快照返回结论，不访问 Redis、RabbitMQ 或发送消息；
 * {@link #evaluateAfterConfirm(String)} 等执行层方法负责加载数据并通过 CAS 落状态。</p>
 */
@Service
@Slf4j
public class SeckillOrderFailureDecisionService {

    /**
     * 决策结论。
     */
    public enum Decision {
        /** 安排补偿发布：结果未知，按退避重发。 */
        RETRY_PUBLISH,
        /** 暂不动作：证据已记录，等待其他机制收敛。 */
        WAIT,
        /** 进入 ROLLBACK_PENDING，由持久化回滚任务执行。 */
        ROLLBACK,
        /** 订单已存在，事件收敛为 CONSUMED，禁止回滚。 */
        MARK_CONSUMED,
        /** 证据矛盾或自动处理边界已到，转人工核对。 */
        MANUAL_REVIEW
    }

    /**
     * 触发决策的场景。
     */
    public enum Trigger {
        /** 一次 Confirm 已完成（NACK 或 Return 等失败证据）。 */
        CONFIRM_COMPLETED,
        /** 发送异常或确认超时：发送结果未知，需要安排重试。 */
        RETRY_SIGNALLED
    }

    /**
     * 决策输入快照：纯数据，不含任何外部资源引用。
     */
    public static class DecisionSnapshot {

        private final boolean orderExists;

        private final Integer eventStatus;

        private final List<AttemptEvidence> attempts;

        private final Trigger trigger;

        public DecisionSnapshot(
                boolean orderExists,
                Integer eventStatus,
                List<AttemptEvidence> attempts,
                Trigger trigger) {

            this.orderExists = orderExists;
            this.eventStatus = eventStatus;
            this.attempts = attempts == null
                    ? Collections.<AttemptEvidence>emptyList()
                    : new ArrayList<>(attempts);
            this.trigger = trigger;
        }

        public boolean isOrderExists() {
            return orderExists;
        }

        public Integer getEventStatus() {
            return eventStatus;
        }

        public List<AttemptEvidence> getAttempts() {
            return Collections.unmodifiableList(attempts);
        }

        public Trigger getTrigger() {
            return trigger;
        }
    }

    /**
     * 一次发送尝试的证据快照。
     */
    public static class AttemptEvidence {

        private final Integer confirmStatus;

        private final Boolean returned;

        public AttemptEvidence(Integer confirmStatus, Boolean returned) {
            this.confirmStatus = confirmStatus;
            this.returned = returned;
        }

        public Integer getConfirmStatus() {
            return confirmStatus;
        }

        public Boolean getReturned() {
            return returned;
        }
    }

    private final SeckillOrderEventService eventService;

    private final SeckillPublishAttemptService attemptService;

    private final VoucherOrderMapper voucherOrderMapper;

    private final SeckillFailureCaseService failureCaseService;

    /**
     * 注入事件服务、发布尝试服务、订单 Mapper 和失败记录服务。
     */
    public SeckillOrderFailureDecisionService(
            SeckillOrderEventService eventService,
            SeckillPublishAttemptService attemptService,
            VoucherOrderMapper voucherOrderMapper,
            SeckillFailureCaseService failureCaseService) {

        this.eventService = eventService;
        this.attemptService = attemptService;
        this.voucherOrderMapper = voucherOrderMapper;
        this.failureCaseService = failureCaseService;
    }

    /**
     * 纯决策函数：决策顺序不可调整。
     *
     * <p>1. 订单存在优先收敛为 CONSUMED；
     * 2. 存在可路由或结果未知的尝试时禁止自动回滚；
     * 3. 全部尝试都明确 NACK 或 Return 且没有订单，才允许回滚；
     * 4. 证据矛盾进入人工核对。</p>
     */
    public Decision decide(DecisionSnapshot snapshot) {
        if (snapshot.isOrderExists()) {
            return Decision.MARK_CONSUMED;
        }

        // 事件声称已消费但订单不存在：数据冲突，禁止自动处理
        if (snapshot.getEventStatus() != null
                && snapshot.getEventStatus()
                == SeckillOrderEvent.STATUS_CONSUMED) {
            return Decision.MANUAL_REVIEW;
        }

        List<AttemptEvidence> attempts = snapshot.getAttempts();

        boolean hasPossibleDelivery = false;

        for (AttemptEvidence attempt : attempts) {
            boolean notReturned =
                    !Boolean.TRUE.equals(attempt.getReturned());
            Integer status = attempt.getConfirmStatus();

            boolean maybeDelivered = notReturned
                    && (Integer.valueOf(SeckillPublishAttempt.CONFIRM_ACK)
                    .equals(status)
                    || Integer.valueOf(SeckillPublishAttempt.CONFIRM_WAITING)
                    .equals(status)
                    || Integer.valueOf(SeckillPublishAttempt.CONFIRM_UNKNOWN)
                    .equals(status));

            if (maybeDelivered) {
                hasPossibleDelivery = true;
                break;
            }
        }

        if (hasPossibleDelivery) {
            // 存在可路由或未知尝试：不能自动回滚；
            // 重试触发场景安排补偿发布，其余等待其他机制收敛
            return snapshot.getTrigger() == Trigger.RETRY_SIGNALLED
                    ? Decision.RETRY_PUBLISH
                    : Decision.WAIT;
        }

        if (attempts.isEmpty()) {
            return snapshot.getTrigger() == Trigger.RETRY_SIGNALLED
                    ? Decision.RETRY_PUBLISH
                    : Decision.WAIT;
        }

        boolean allDefinitiveFailure = true;

        for (AttemptEvidence attempt : attempts) {
            boolean definitive =
                    Boolean.TRUE.equals(attempt.getReturned())
                            || Integer.valueOf(
                            SeckillPublishAttempt.CONFIRM_NACK)
                            .equals(attempt.getConfirmStatus());

            if (!definitive) {
                allDefinitiveFailure = false;
                break;
            }
        }

        if (allDefinitiveFailure) {
            return Decision.ROLLBACK;
        }

        // 理论上不可达：证据既不构成可能投递也不构成明确失败
        return Decision.MANUAL_REVIEW;
    }

    /**
     * Confirm 完成后评估事件（NACK 或 Return 场景）。
     */
    public Decision evaluateAfterConfirm(String eventId) {
        return applyDecision(
                eventId,
                loadSnapshot(eventId, Trigger.CONFIRM_COMPLETED),
                null,
                null
        );
    }

    /**
     * 发送异常或确认超时后评估事件：结果未知，按重试信号处理。
     */
    public Decision evaluateForRetry(
            String eventId,
            String errorCode,
            String errorMessage) {

        return applyDecision(
                eventId,
                loadSnapshot(eventId, Trigger.RETRY_SIGNALLED),
                errorCode,
                errorMessage
        );
    }

    /**
     * 加载决策快照：订单、事件状态和全部发送尝试。
     */
    public DecisionSnapshot loadSnapshot(String eventId, Trigger trigger) {
        SeckillOrderEvent event = eventService.findByEventId(eventId);

        boolean orderExists = false;
        Integer eventStatus = null;
        List<AttemptEvidence> evidence =
                new ArrayList<>();

        if (event != null) {
            eventStatus = event.getStatus();

            if (event.getOrderId() != null) {
                VoucherOrder order =
                        voucherOrderMapper.selectById(event.getOrderId());
                orderExists = order != null;
            }
        }

        if (event != null) {
            List<SeckillPublishAttempt> attempts =
                    attemptService.findByEventId(eventId);

            for (SeckillPublishAttempt attempt : attempts) {
                evidence.add(new AttemptEvidence(
                        attempt.getConfirmStatus(),
                        attempt.getReturned()
                ));
            }
        }

        return new DecisionSnapshot(
                orderExists,
                eventStatus,
                evidence,
                trigger
        );
    }

    /**
     * 执行决策：通过事件服务的 CAS 方法落状态，禁止直接修改 Redis。
     */
    private Decision applyDecision(
            String eventId,
            DecisionSnapshot snapshot,
            String errorCode,
            String errorMessage) {

        Decision decision = decide(snapshot);

        switch (decision) {
            case MARK_CONSUMED:
                boolean consumed = eventService.markConsumed(eventId);
                log.warn(
                        "失败决策：订单已存在，事件收敛为 CONSUMED，eventId={}，applied={}",
                        eventId,
                        consumed
                );
                break;

            case RETRY_PUBLISH:
                int status = eventService.schedulePublishRetry(
                        eventId,
                        errorCode,
                        errorMessage
                );
                log.warn(
                        "失败决策：发送结果未知，安排补偿发布，eventId={}，nextStatus={}",
                        eventId,
                        status
                );
                break;

            case ROLLBACK:
                boolean rollback = eventService.markRollbackPending(
                        eventId,
                        "all_attempts_undelivered",
                        "all " + snapshot.getAttempts().size()
                                + " publish attempts were nacked or returned"
                );
                log.warn(
                        "失败决策：全部发送尝试均明确未投递，进入回滚队列，"
                                + "eventId={}，applied={}",
                        eventId,
                        rollback
                );
                break;

            case MANUAL_REVIEW:
                /*
                 * 证据矛盾转人工：必须事务性创建 SOURCE_PUBLISH 失败记录，
                 * 否则人工工作台查不到该事件，自动流程停止后没有任何处置入口。
                 */
                SeckillOrderEvent reviewedEvent =
                        eventService.findByEventId(eventId);

                String reviewErrorCode =
                        errorCode == null ? "evidence_conflict" : errorCode;

                boolean reviewed = reviewedEvent != null
                        && failureCaseService.recordManualReviewEscalation(
                        reviewedEvent,
                        com.dish.review.entity.SeckillFailureCase
                                .SOURCE_PUBLISH,
                        reviewErrorCode,
                        errorMessage
                );

                log.error(
                        "失败决策：证据矛盾或超出自动处理边界，转人工核对并写失败记录，"
                                + "eventId={}，applied={}",
                        eventId,
                        reviewed
                );
                break;

            case WAIT:
            default:
                log.debug(
                        "失败决策：暂不动作，等待其他机制收敛，eventId={}",
                        eventId
                );
                break;
        }

        return decision;
    }
}
