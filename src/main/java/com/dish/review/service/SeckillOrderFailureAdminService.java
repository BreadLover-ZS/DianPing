package com.dish.review.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dish.review.entity.SeckillFailureAudit;
import com.dish.review.entity.SeckillFailureCase;
import com.dish.review.entity.VoucherOrder;
import com.dish.review.mapper.SeckillFailureAuditMapper;
import com.dish.review.mapper.VoucherOrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

/**
 * 秒杀失败处置管理 Service（规格第 13 节）。
 *
 * <p>项目暂无 RBAC，本 Service 是人工处置的唯一入口，
 * 禁止在 RBAC 完成前暴露公网写接口（Controller）。
 * 重放只把事件重新置为 PENDING 由 Outbox 统一发布，
 * 复用原 eventId/orderId，禁止直接调用 RabbitTemplate。</p>
 */
@Service
@Slf4j
public class SeckillOrderFailureAdminService {

    /** 重放成功，事件已回到 PENDING 等待 Outbox 发布。 */
    public static final String OUTCOME_REPLAYED = "REPLAYED";

    /** 回滚决定成功，事件已进入 ROLLBACK_PENDING 等待回滚任务。 */
    public static final String OUTCOME_ROLLED_BACK = "ROLLED_BACK";

    /** 失败记录已关闭。 */
    public static final String OUTCOME_CLOSED = "CLOSED";

    /** 订单已存在：按幂等成功关闭失败记录，禁止重放或回滚。 */
    public static final String OUTCOME_ALREADY_CONSUMED = "ALREADY_CONSUMED";

    /** 重放次数已达上限。 */
    public static final String OUTCOME_REPLAY_LIMIT_EXCEEDED =
            "REPLAY_LIMIT_EXCEEDED";

    /** 事件当前状态不允许重放（非 MANUAL_REVIEW/DLQ）。 */
    public static final String OUTCOME_EVENT_NOT_REPLAYABLE =
            "EVENT_NOT_REPLAYABLE";

    /** 事件当前状态不允许回滚（非 MANUAL_REVIEW）。 */
    public static final String OUTCOME_EVENT_NOT_ROLLBACKABLE =
            "EVENT_NOT_ROLLBACKABLE";

    /** 失败记录或事件不存在。 */
    public static final String OUTCOME_NOT_FOUND = "NOT_FOUND";

    private final SeckillFailureCaseService failureCaseService;

    private final SeckillOrderEventService eventService;

    private final SeckillFailureAuditMapper auditMapper;

    private final VoucherOrderMapper voucherOrderMapper;

    @Value("${dish-review.seckill.failure-admin.max-replay-count:3}")
    private int maxReplayCount;

    /**
     * 注入失败记录服务、事件服务、审计 Mapper 和订单 Mapper。
     */
    public SeckillOrderFailureAdminService(
            SeckillFailureCaseService failureCaseService,
            SeckillOrderEventService eventService,
            SeckillFailureAuditMapper auditMapper,
            VoucherOrderMapper voucherOrderMapper) {
        this.failureCaseService = failureCaseService;
        this.eventService = eventService;
        this.auditMapper = auditMapper;
        this.voucherOrderMapper = voucherOrderMapper;
    }

    /**
     * 查询待处理失败记录（人工处置工作台数据源）。
     */
    public List<SeckillFailureCase> listOpenCases(int limit) {
        return failureCaseService.findOpenCases(limit);
    }

    /**
     * 按失败记录 ID 查询详情。
     */
    public SeckillFailureCase getFailureCase(Long failureId) {
        if (failureId == null || failureId <= 0) {
            return null;
        }

        return failureCaseService.findByFailureId(failureId);
    }

    /**
     * 查询失败记录的处置审计历史。
     */
    public List<SeckillFailureAudit> listAuditTrail(Long failureId) {
        if (failureId == null || failureId <= 0) {
            return Collections.emptyList();
        }

        LambdaQueryWrapper<SeckillFailureAudit> query =
                new LambdaQueryWrapper<>();

        query.eq(SeckillFailureAudit::getFailureId, failureId)
                .orderByDesc(SeckillFailureAudit::getAuditId);

        return auditMapper.selectList(query);
    }

    /**
     * 人工重放失败消息（规格第 13 节）。
     *
     * <p>复用原 eventId/orderId；订单已存在时按幂等成功关闭；
     * 重放次数达到上限后拒绝；事件只允许从 MANUAL_REVIEW/DLQ 回到 PENDING，
     * 由 Outbox 发布任务统一发送，禁止绕过 Outbox 直接调用 RabbitTemplate。</p>
     *
     * @return 处置结果代码
     */
    @Transactional
    public String replayFailure(
            Long failureId,
            String operator,
            String reason) {

        SeckillFailureCase failureCase =
                requireFailureCase(failureId);

        if (failureCase == null) {
            return OUTCOME_NOT_FOUND;
        }

        // 1. 检查订单是否已存在：存在则按幂等成功关闭，禁止重放
        if (isOrderPresent(failureCase)) {
            failureCaseService.closeAsIdempotentSuccess(failureId);

            insertAudit(
                    failureCase,
                    SeckillFailureAudit.ACTION_REPLAY,
                    operator,
                    "订单已存在，按幂等成功关闭；" + safeReason(reason)
            );

            return OUTCOME_ALREADY_CONSUMED;
        }

        // 2. 限制重放次数
        int replayCount = failureCase.getReplayCount() == null
                ? 0
                : failureCase.getReplayCount();

        if (replayCount >= maxReplayCount) {
            log.warn(
                    "失败记录重放次数已达上限，拒绝重放，failureId={}，"
                            + "replayCount={}，maxReplayCount={}",
                    failureId,
                    replayCount,
                    maxReplayCount
            );
            return OUTCOME_REPLAY_LIMIT_EXCEEDED;
        }

        String eventId = failureCase.getEventId();

        // 3. 事件重新置为 PENDING（复用原 eventId/orderId），由 Outbox 发布
        boolean replayed = eventId != null
                && eventService.markReplayedForManualRetry(eventId);

        if (!replayed) {
            return OUTCOME_EVENT_NOT_REPLAYABLE;
        }

        // 4. 失败记录标记 REPLAYED 并累加 replayCount
        failureCaseService.markReplayed(failureId);

        // 5. 审计：操作者、时间和原因
        insertAudit(
                failureCase,
                SeckillFailureAudit.ACTION_REPLAY,
                operator,
                reason
        );

        log.info(
                "失败记录人工重放，事件已回到 PENDING 等待 Outbox 发布，"
                        + "failureId={}，eventId={}，operator={}",
                failureId,
                eventId,
                operator
        );

        return OUTCOME_REPLAYED;
    }

    /**
     * 人工决定回滚：把 MANUAL_REVIEW 事件置为 ROLLBACK_PENDING，
     * 由持久化回滚任务执行 Redis 预留恢复。
     *
     * @return 处置结果代码
     */
    @Transactional
    public String rollbackFailure(
            Long failureId,
            String operator,
            String reason) {

        SeckillFailureCase failureCase =
                requireFailureCase(failureId);

        if (failureCase == null) {
            return OUTCOME_NOT_FOUND;
        }

        // 1. 订单已存在时禁止回滚（终态保护）
        if (isOrderPresent(failureCase)) {
            return OUTCOME_ALREADY_CONSUMED;
        }

        String eventId = failureCase.getEventId();

        // 2. 事件只能从 MANUAL_REVIEW 进入 ROLLBACK_PENDING
        boolean marked = eventId != null
                && eventService.markManualRollback(eventId);

        if (!marked) {
            return OUTCOME_EVENT_NOT_ROLLBACKABLE;
        }

        // 3. 失败记录标记 ROLLED_BACK 决定（实际恢复由回滚任务执行并落终态）
        failureCaseService.markRolledBack(failureId);

        // 4. 审计：操作者、时间和原因
        insertAudit(
                failureCase,
                SeckillFailureAudit.ACTION_ROLLBACK,
                operator,
                reason
        );

        log.info(
                "失败记录人工回滚，事件已进入 ROLLBACK_PENDING 等待回滚任务，"
                        + "failureId={}，eventId={}，operator={}",
                failureId,
                eventId,
                operator
        );

        return OUTCOME_ROLLED_BACK;
    }

    /**
     * 人工关闭失败记录（判定无需处理，例如业务上接受该失败）。
     *
     * @return 处置结果代码
     */
    @Transactional
    public String closeFailure(
            Long failureId,
            String operator,
            String reason) {

        SeckillFailureCase failureCase =
                requireFailureCase(failureId);

        if (failureCase == null) {
            return OUTCOME_NOT_FOUND;
        }

        boolean closed = failureCaseService.markClosed(failureId);

        if (!closed) {
            return OUTCOME_NOT_FOUND;
        }

        insertAudit(
                failureCase,
                SeckillFailureAudit.ACTION_CLOSE,
                operator,
                reason
        );

        log.info(
                "失败记录人工关闭，failureId={}，operator={}",
                failureId,
                operator
        );

        return OUTCOME_CLOSED;
    }

    /**
     * 校验并加载失败记录。
     */
    private SeckillFailureCase requireFailureCase(Long failureId) {
        if (failureId == null || failureId <= 0) {
            return null;
        }

        return failureCaseService.findByFailureId(failureId);
    }

    /**
     * 检查失败记录对应的订单是否已经创建。
     */
    private boolean isOrderPresent(SeckillFailureCase failureCase) {
        Long orderId = failureCase.getOrderId();

        if (orderId == null) {
            return false;
        }

        VoucherOrder order = voucherOrderMapper.selectById(orderId);
        return order != null;
    }

    /**
     * 写入审计记录：操作者、时间和原因。
     */
    private void insertAudit(
            SeckillFailureCase failureCase,
            String action,
            String operator,
            String reason) {

        SeckillFailureAudit audit = new SeckillFailureAudit();

        audit.setFailureId(failureCase.getFailureId());
        audit.setEventId(failureCase.getEventId());
        audit.setAction(action);
        audit.setOperator(operator == null || operator.trim().isEmpty()
                ? "unknown"
                : operator.trim());
        audit.setReason(limitReason(reason));

        auditMapper.insert(audit);
    }

    /**
     * 规范化并截断操作原因。
     */
    private String safeReason(String reason) {
        return reason == null ? "" : reason;
    }

    /**
     * 截断原因到审计表字段长度。
     */
    private String limitReason(String reason) {
        String normalized = safeReason(reason).trim();

        if (normalized.isEmpty()) {
            return null;
        }

        return normalized.length() <= 512
                ? normalized
                : normalized.substring(0, 512);
    }
}
