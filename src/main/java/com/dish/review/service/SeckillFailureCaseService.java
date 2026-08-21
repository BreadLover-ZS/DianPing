package com.dish.review.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.dish.review.entity.SeckillFailureCase;
import com.dish.review.entity.SeckillOrderEvent;
import com.dish.review.mapper.SeckillFailureCaseMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 失败记录服务：DLQ、回滚异常和对账冲突的唯一持久化事实。
 *
 * <p>所有写入以 idempotency_key 唯一索引幂等。消费侧入口
 * {@link #recordConsumerDlqFailure} 是独立 Service 事务方法：
 * 失败记录与事件状态同事务提交，Recoverer 在其返回后才执行拒绝，
 * 保证拒绝异常不会回滚已写入的失败记录（规格第 13 节）。</p>
 */
@Service
@Slf4j
public class SeckillFailureCaseService {

    private final SeckillFailureCaseMapper failureCaseMapper;

    private final SeckillOrderEventService eventService;

    /**
     * 注入失败记录表 Mapper 和事件状态服务。
     */
    public SeckillFailureCaseService(
            SeckillFailureCaseMapper failureCaseMapper,
            SeckillOrderEventService eventService) {
        this.failureCaseMapper = failureCaseMapper;
        this.eventService = eventService;
    }

    /**
     * 幂等记录一条失败事实；已存在时补充证据字段或重新开启。
     *
     * <p>唯一索引冲突（并发写入同一幂等键）按幂等成功处理；
     * 其他数据库异常原样抛出，由调用方决定重新入队或告警。</p>
     *
     * <p>已存在记录处于 REPLAYED/CLOSED 状态时视为“同一事件在人工处置后
     * 再次失败”：重新置为 OPEN 并更新最新错误证据，保证 findOpenCases
     * 能再次看到这次失败，不会从人工工作台消失。</p>
     *
     * @return 记录是否新建成功
     */
    @Transactional
    public boolean recordFailure(SeckillFailureCase failureCase) {
        SeckillFailureCase existing = findByIdempotencyKey(
                failureCase.getIdempotencyKey()
        );

        if (existing != null) {
            if (isReopenable(existing)) {
                reopenWithLatestEvidence(
                        existing.getFailureId(),
                        failureCase
                );
                return true;
            }

            // 幂等命中：仅补充可能缺失的证据字段
            if (existing.getXDeathInfo() == null
                    && failureCase.getXDeathInfo() != null) {
                supplementXDeath(existing.getFailureId(),
                        failureCase.getXDeathInfo());
            }
            return true;
        }

        failureCase.setErrorMessage(limitText(
                failureCase.getErrorMessage(), 512));
        failureCase.setMessagePayload(limitText(
                failureCase.getMessagePayload(), 2048));
        failureCase.setXDeathInfo(limitText(
                failureCase.getXDeathInfo(), 1024));

        try {
            return failureCaseMapper.insert(failureCase) == 1;
        } catch (DuplicateKeyException exception) {
            // 唯一索引冲突表示并发写入，按幂等成功处理
            log.warn(
                    "失败记录并发写入，按幂等成功处理，idempotencyKey={}",
                    failureCase.getIdempotencyKey()
            );
            return true;
        }
    }

    /**
     * 转人工升级的统一入口：同一事务内把事件置为 MANUAL_REVIEW
     * 并写入对应来源的失败记录，保证“停止自动处理”的事件
     * 必然存在人工处置入口（规格第 13 节）。
     *
     * <p>幂等键为 {@code source:MANUAL:eventId}：重复调用只补证据，
     * 不产生第二条记录。</p>
     *
     * @return 事件状态是否被本次调用推进为 MANUAL_REVIEW
     */
    @Transactional
    public boolean recordManualReviewEscalation(
            SeckillOrderEvent event,
            String source,
            String errorCode,
            String errorMessage) {

        boolean marked = eventService.markManualReview(
                event.getEventId(),
                errorCode,
                errorMessage
        );

        SeckillFailureCase failureCase = new SeckillFailureCase();

        failureCase.setIdempotencyKey(source + ":MANUAL:"
                + event.getEventId());
        failureCase.setEventId(event.getEventId());
        failureCase.setOrderId(event.getOrderId());
        failureCase.setUserId(event.getUserId());
        failureCase.setVoucherId(event.getVoucherId());
        failureCase.setSource(source);
        failureCase.setStatus(SeckillFailureCase.STATUS_OPEN);
        failureCase.setErrorCode(errorCode);
        failureCase.setErrorMessage(errorMessage);
        failureCase.setReplayCount(0);

        recordFailure(failureCase);

        return marked;
    }

    /**
     * 判断已存在的失败记录是否允许被再次失败重新开启。
     */
    private boolean isReopenable(SeckillFailureCase existing) {
        return SeckillFailureCase.STATUS_REPLAYED
                .equals(existing.getStatus())
                || SeckillFailureCase.STATUS_CLOSED
                .equals(existing.getStatus());
    }

    /**
     * 重新开启已处置的失败记录并更新最新错误证据。
     *
     * <p>保留 replay_count（人工重放次数是累计事实）；
     * 更新 error_code/error_message/message_payload 并重置 next_action_time，
     * 使 findOpenCases 重新可见。</p>
     */
    private void reopenWithLatestEvidence(
            Long failureId,
            SeckillFailureCase latest) {

        UpdateWrapper<SeckillFailureCase> update = new UpdateWrapper<>();

        update.set("status", SeckillFailureCase.STATUS_OPEN)
                .set("error_code", limitText(latest.getErrorCode(), 64))
                .set("error_message", limitText(latest.getErrorMessage(), 512))
                .set("message_payload",
                        limitText(latest.getMessagePayload(), 2048))
                .setSql("next_action_time = CURRENT_TIMESTAMP")
                .eq("failure_id", failureId);

        if (latest.getXDeathInfo() != null) {
            update.set("x_death_info",
                    limitText(latest.getXDeathInfo(), 1024));
        }

        failureCaseMapper.update(null, update);

        log.warn(
                "失败记录在人工处置后再次失败，已重新开启并更新证据，"
                        + "failureId={}，idempotencyKey={}，errorCode={}",
                failureId,
                latest.getIdempotencyKey(),
                latest.getErrorCode()
        );
    }

    /**
     * 消费重试耗尽（或永久失败）后的统一入口：
     * 同一事务内先幂等写入失败记录，再把事件推进为 DLQ 或 MANUAL_REVIEW。
     *
     * <p>数据库不可用时异常原样抛出，调用方必须强制重新入队，
     * 禁止 ACK 或丢弃原消息（规格第 13 节）。</p>
     *
     * @param failureCase        从失败消息提取的证据
     * @param consistencyConflict 是否为一致性冲突（事件转人工而不是 DLQ）
     */
    @Transactional
    public void recordConsumerDlqFailure(
            SeckillFailureCase failureCase,
            boolean consistencyConflict) {

        recordFailure(failureCase);

        String eventId = failureCase.getEventId();

        if (eventId == null || eventId.trim().isEmpty()) {
            // 无法定位事件的垃圾消息：失败记录本身就是持久化事实
            log.error(
                    "消费失败消息缺少 eventId，仅持久化失败记录，"
                            + "idempotencyKey={}，errorCode={}",
                    failureCase.getIdempotencyKey(),
                    failureCase.getErrorCode()
            );
            return;
        }

        boolean marked = consistencyConflict
                ? eventService.markManualReview(
                eventId,
                failureCase.getErrorCode(),
                failureCase.getErrorMessage())
                : eventService.markDlq(
                eventId,
                failureCase.getErrorCode(),
                failureCase.getErrorMessage());

        if (!marked) {
            // 事件处于不允许迁入 DLQ/MANUAL_REVIEW 的状态（如 PENDING 或终态），
            // 失败记录已落库，事件由 Outbox 重发或对账任务收敛
            log.warn(
                    "消费失败记录已持久化，但事件状态未推进（来源状态不允许迁移），"
                            + "eventId={}，consistencyConflict={}，errorCode={}",
                    eventId,
                    consistencyConflict,
                    failureCase.getErrorCode()
            );
        }
    }

    /**
     * 回滚任务失败后的统一入口：同一事务内恢复事件状态（退避或转 MANUAL_REVIEW），
     * 升级为 MANUAL_REVIEW 时同时持久化 ROLLBACK 来源的失败记录，
     * 保证“停止自动处理”的人工处置入口必然存在（规格第 11 节）。
     *
     * @return 恢复后的事件状态（ROLLBACK_PENDING 或 MANUAL_REVIEW）
     */
    @Transactional
    public int recordRollbackRevert(
            SeckillOrderEvent event,
            long leaseToken,
            String errorCode,
            String errorMessage) {

        int status = eventService.revertToRollbackPending(
                event.getEventId(),
                leaseToken,
                errorCode,
                errorMessage
        );

        if (status == SeckillOrderEvent.STATUS_MANUAL_REVIEW) {
            SeckillFailureCase failureCase = new SeckillFailureCase();

            failureCase.setIdempotencyKey(
                    "ROLLBACK:" + event.getEventId());
            failureCase.setEventId(event.getEventId());
            failureCase.setOrderId(event.getOrderId());
            failureCase.setUserId(event.getUserId());
            failureCase.setVoucherId(event.getVoucherId());
            failureCase.setSource(SeckillFailureCase.SOURCE_ROLLBACK);
            failureCase.setStatus(SeckillFailureCase.STATUS_OPEN);
            failureCase.setErrorCode(errorCode);
            failureCase.setErrorMessage(errorMessage);
            failureCase.setReplayCount(0);

            recordFailure(failureCase);
        }

        return status;
    }

    /**
     * DLQ 消费者到达时补充证据：记录已存在则补充 x-death 与到达时间；
     * 不存在则补建（消息可能未经 Recoverer 直接死信，如队列超长）。
     *
     * @return 补充或补建后的失败记录
     */
    @Transactional
    public SeckillFailureCase supplementDlqArrival(SeckillFailureCase evidence) {
        SeckillFailureCase existing = findByIdempotencyKey(
                evidence.getIdempotencyKey()
        );

        if (existing == null) {
            recordFailure(evidence);

            SeckillFailureCase created = findByIdempotencyKey(
                    evidence.getIdempotencyKey()
            );

            return created == null ? evidence : created;
        }

        if (evidence.getXDeathInfo() != null) {
            supplementXDeath(
                    existing.getFailureId(),
                    evidence.getXDeathInfo()
            );
        }

        return existing;
    }

    /**
     * DLQ 消费者到达时补充 x-death 信息和到达时间。
     */
    public boolean supplementXDeath(Long failureId, String xDeathInfo) {
        UpdateWrapper<SeckillFailureCase> update = new UpdateWrapper<>();

        update.set("x_death_info", limitText(xDeathInfo, 1024))
                .setSql("next_action_time = CURRENT_TIMESTAMP")
                .eq("failure_id", failureId);

        return failureCaseMapper.update(null, update) == 1;
    }

    /**
     * 事件已 CONSUMED 时按幂等成功关闭失败记录。
     */
    public boolean closeAsIdempotentSuccess(Long failureId) {
        UpdateWrapper<SeckillFailureCase> update = new UpdateWrapper<>();

        update.set("status", SeckillFailureCase.STATUS_CLOSED)
                .set("next_action_time", null)
                .eq("failure_id", failureId)
                .eq("status", SeckillFailureCase.STATUS_OPEN);

        return failureCaseMapper.update(null, update) == 1;
    }

    /**
     * 人工重放后标记失败记录状态。
     */
    public boolean markReplayed(Long failureId) {
        UpdateWrapper<SeckillFailureCase> update = new UpdateWrapper<>();

        update.setSql("replay_count = replay_count + 1")
                .set("status", SeckillFailureCase.STATUS_REPLAYED)
                .set("next_action_time", null)
                .eq("failure_id", failureId);

        return failureCaseMapper.update(null, update) == 1;
    }

    /**
     * 人工回滚后标记失败记录状态。
     */
    public boolean markRolledBack(Long failureId) {
        UpdateWrapper<SeckillFailureCase> update = new UpdateWrapper<>();

        update.set("status", SeckillFailureCase.STATUS_ROLLED_BACK)
                .set("next_action_time", null)
                .eq("failure_id", failureId);

        return failureCaseMapper.update(null, update) == 1;
    }

    /**
     * 人工关闭失败记录。
     */
    public boolean markClosed(Long failureId) {
        UpdateWrapper<SeckillFailureCase> update = new UpdateWrapper<>();

        update.set("status", SeckillFailureCase.STATUS_CLOSED)
                .set("next_action_time", null)
                .eq("failure_id", failureId);

        return failureCaseMapper.update(null, update) == 1;
    }

    /**
     * 按幂等键查询失败记录。
     */
    public SeckillFailureCase findByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isEmpty()) {
            return null;
        }

        QueryWrapper<SeckillFailureCase> query = new QueryWrapper<>();
        query.eq("idempotency_key", idempotencyKey);

        List<SeckillFailureCase> cases = failureCaseMapper.selectList(query);
        return cases.isEmpty() ? null : cases.get(0);
    }

    /**
     * 查询待处理失败记录（人工处置入口）。
     */
    public List<SeckillFailureCase> findOpenCases(int limit) {
        QueryWrapper<SeckillFailureCase> query = new QueryWrapper<>();

        query.eq("status", SeckillFailureCase.STATUS_OPEN)
                .orderByAsc("create_time")
                .last("LIMIT " + Math.max(1, Math.min(limit, 100)));

        return failureCaseMapper.selectList(query);
    }

    /**
     * 按事件 ID 查询失败记录。
     */
    public SeckillFailureCase findByEventId(String eventId) {
        if (eventId == null || eventId.isEmpty()) {
            return null;
        }

        QueryWrapper<SeckillFailureCase> query = new QueryWrapper<>();
        query.eq("event_id", eventId);

        List<SeckillFailureCase> cases = failureCaseMapper.selectList(query);
        return cases.isEmpty() ? null : cases.get(0);
    }

    /**
     * 按主键查询。
     */
    public SeckillFailureCase findByFailureId(Long failureId) {
        return failureCaseMapper.selectById(failureId);
    }

    /**
     * 统计 OPEN 状态失败记录数量（监控使用）。
     */
    public Integer countOpenCases() {
        QueryWrapper<SeckillFailureCase> query = new QueryWrapper<>();
        query.eq("status", SeckillFailureCase.STATUS_OPEN);

        return failureCaseMapper.selectCount(query);
    }

    /**
     * 截断文本到指定长度。
     */
    private String limitText(String text, int maxLength) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }

        String normalized = text.trim();

        return normalized.length() <= maxLength
                ? normalized
                : normalized.substring(0, maxLength);
    }
}
