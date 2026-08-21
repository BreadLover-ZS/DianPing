package com.dish.review.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.dish.review.dto.SeckillOrderMessage;
import com.dish.review.entity.SeckillOrderEvent;
import com.dish.review.mapper.SeckillOrderEventMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 管理秒杀消息事件状态机，为发布确认、消费完成、回滚和人工处置提供持久化依据。
 *
 * <p>所有状态更新都带当前状态条件（CAS），禁止无条件覆盖；
 * CONSUMED 和 ROLLED_BACK 终态不可能被迟到回调改写。
 * 租约与到期判断使用 MySQL CURRENT_TIMESTAMP，避免多实例本地时钟偏差。</p>
 */
@Service
@Slf4j
public class SeckillOrderEventService {

    private final SeckillOrderEventMapper eventMapper;

    /**
     * 注入事件表 Mapper。
     */
    public SeckillOrderEventService(
            SeckillOrderEventMapper eventMapper) {
        this.eventMapper = eventMapper;
    }

    /**
     * 请求线程在 Lua 预留成功后尽力创建 PENDING 事件。
     * 写入失败时调用方禁止直接回滚 Redis，由对账任务依据预留账本恢复事件。
     */
    public void createPending(SeckillOrderMessage message) {
        SeckillOrderEvent event = new SeckillOrderEvent();

        event.setEventId(message.getEventId());
        event.setOrderId(message.getOrderId());
        event.setUserId(message.getUserId());
        event.setVoucherId(message.getVoucherId());
        event.setCreatedAt(message.getCreatedAt());
        event.setMessageVersion(message.getVersion());
        event.setStatus(SeckillOrderEvent.STATUS_PENDING);
        event.setRetryCount(0);
        event.setRollbackRetryCount(0);
        event.setRowVersion(0L);
        event.setLeaseToken(0L);
        // 立即到期：首次发布和所有补偿发布统一由 Outbox 任务执行
        event.setNextRetryTime(LocalDateTime.now());

        int insertedRows = eventMapper.insert(event);

        if (insertedRows != 1) {
            throw new IllegalStateException(
                    "秒杀订单 PENDING 事件写入失败，eventId="
                            + message.getEventId()
            );
        }
    }

    /**
     * 对账任务依据 Redis 预留详情幂等补建事件。
     * 事件已存在时按幂等成功处理，不覆盖任何状态。
     */
    public boolean createPendingIfAbsent(SeckillOrderMessage message) {
        if (eventMapper.selectById(message.getEventId()) != null) {
            return false;
        }

        try {
            createPending(message);
            return true;
        } catch (Exception exception) {
            // 唯一索引冲突表示并发补建，按幂等成功处理
            log.warn(
                    "对账补建秒杀订单事件失败（可能并发），eventId={}",
                    message.getEventId()
            );
            return false;
        }
    }

    /**
     * 将事件推进为 CONFIRMED；仅允许从状态机合法来源迁入，终态按幂等成功处理。
     */
    public boolean markConfirmed(String eventId) {
        UpdateWrapper<SeckillOrderEvent> update = new UpdateWrapper<>();

        update.set("status", SeckillOrderEvent.STATUS_CONFIRMED)
                .set("last_error", null)
                .set("last_error_code", null)
                .set("next_retry_time", null)
                .setSql("confirmed_at = CURRENT_TIMESTAMP");

        return applyCasUpdate(
                eventId,
                SeckillOrderEvent.STATUS_CONFIRMED,
                update
        );
    }

    /**
     * 将事件推进为 CONSUMED；订单事务内调用，与订单写入同事务提交。
     */
    public boolean markConsumed(String eventId) {
        UpdateWrapper<SeckillOrderEvent> update = new UpdateWrapper<>();

        update.set("status", SeckillOrderEvent.STATUS_CONSUMED)
                .set("last_error", null)
                .set("last_error_code", null)
                .set("next_retry_time", null)
                .setSql("consumed_at = CURRENT_TIMESTAMP")
                .setSql("terminal_at = CURRENT_TIMESTAMP");

        return applyCasUpdate(
                eventId,
                SeckillOrderEvent.STATUS_CONSUMED,
                update
        );
    }

    /**
     * 同步发送异常后把事件标记为 PUBLISH_UNKNOWN：结果未知，禁止自动回滚。
     */
    public boolean markPublishUnknown(
            String eventId,
            String errorCode,
            String errorMessage) {

        UpdateWrapper<SeckillOrderEvent> update = new UpdateWrapper<>();

        update.set("status", SeckillOrderEvent.STATUS_PUBLISH_UNKNOWN)
                .set("last_error_code", limitText(errorCode, 64))
                .set("last_error", limitError(errorMessage))
                .setSql("next_retry_time = DATE_ADD(CURRENT_TIMESTAMP, "
                        + "INTERVAL 5 SECOND)");

        return applyCasUpdate(
                eventId,
                SeckillOrderEvent.STATUS_PUBLISH_UNKNOWN,
                update
        );
    }

    /**
     * 统一失败决策服务判定明确失败后，把事件置为 ROLLBACK_PENDING，等待回滚任务执行。
     */
    public boolean markRollbackPending(
            String eventId,
            String errorCode,
            String errorMessage) {

        UpdateWrapper<SeckillOrderEvent> update = new UpdateWrapper<>();

        update.set("status", SeckillOrderEvent.STATUS_ROLLBACK_PENDING)
                .set("last_error_code", limitText(errorCode, 64))
                .set("last_error", limitError(errorMessage))
                // 立即可执行：回滚任务按退避自行调度
                .setSql("next_retry_time = CURRENT_TIMESTAMP");

        return applyCasUpdate(
                eventId,
                SeckillOrderEvent.STATUS_ROLLBACK_PENDING,
                update
        );
    }

    /**
     * 消费重试耗尽且失败记录已持久化后，把事件置为 DLQ。
     */
    public boolean markDlq(
            String eventId,
            String errorCode,
            String errorMessage) {

        UpdateWrapper<SeckillOrderEvent> update = new UpdateWrapper<>();

        update.set("status", SeckillOrderEvent.STATUS_DLQ)
                .set("last_error_code", limitText(errorCode, 64))
                .set("last_error", limitError(errorMessage))
                .set("next_retry_time", null);

        return applyCasUpdate(
                eventId,
                SeckillOrderEvent.STATUS_DLQ,
                update
        );
    }

    /**
     * 自动处理停止，事件进入 MANUAL_REVIEW 等待人工核对。
     * 终态（CONSUMED/ROLLED_BACK）事件不可迁入。
     */
    public boolean markManualReview(
            String eventId,
            String errorCode,
            String errorMessage) {

        UpdateWrapper<SeckillOrderEvent> update = new UpdateWrapper<>();

        update.set("status", SeckillOrderEvent.STATUS_MANUAL_REVIEW)
                .set("last_error_code", limitText(errorCode, 64))
                .set("last_error", limitError(errorMessage))
                .set("next_retry_time", null)
                .setSql("terminal_at = CURRENT_TIMESTAMP");

        return applyCasUpdate(
                eventId,
                SeckillOrderEvent.STATUS_MANUAL_REVIEW,
                update
        );
    }

    /**
     * 消费事务内 CAS 取消回滚（ROLLBACK_PENDING → PENDING），成功后才能继续创建订单。
     * 与后续 CONSUMED 同事务提交；事务回滚时状态自动恢复。
     */
    public boolean cancelRollback(String eventId) {
        UpdateWrapper<SeckillOrderEvent> update = new UpdateWrapper<>();

        update.set("status", SeckillOrderEvent.STATUS_PENDING)
                .setSql("row_version = row_version + 1")
                .eq("event_id", eventId)
                .eq("status", SeckillOrderEvent.STATUS_ROLLBACK_PENDING);

        return eventMapper.update(null, update) == 1;
    }

    /**
     * 为发送结果未知的事件安排下一次补偿发布。
     * 快速 1/2/4 秒，慢速 30 秒到 30 分钟。
     *
     * <p>退避轮次耗尽时禁止立即转 MANUAL_REVIEW：本次触发可能是发送异常，
     * 消息仍在途，Confirm/消费可能稍后到达。先推迟一个终局等待窗口
     * （大于确认超时时间），窗口内事件仍未收敛时由 Outbox 扫描阶段
     * 统一转 MANUAL_REVIEW 并写失败记录。</p>
     *
     * @return 更新后的状态（PUBLISH_UNKNOWN）
     */
    public int schedulePublishRetry(
            String eventId,
            String errorCode,
            String errorMessage) {

        SeckillOrderEvent existing = eventMapper.selectById(eventId);

        if (existing == null
                || !isPublishRetryable(existing.getStatus())) {
            return existing == null
                    ? SeckillOrderEvent.STATUS_MANUAL_REVIEW
                    : existing.getStatus();
        }

        int completedAttempts = existing.getRetryCount() == null
                ? 0
                : existing.getRetryCount();
        long delaySeconds = SeckillPublishRetryPolicy
                .nextDelaySeconds(completedAttempts);

        if (delaySeconds == SeckillPublishRetryPolicy.STOP_AUTOMATIC_RETRY) {
            // 已达自动发送上限：等待终局窗口（Confirm/超时/消费仍可收敛），
            // 到期后由 Outbox 扫描统一转人工，禁止在这里立即停止自动流程
            delaySeconds =
                    SeckillPublishRetryPolicy.FINAL_DECISION_WAIT_SECONDS;
        }

        UpdateWrapper<SeckillOrderEvent> update = new UpdateWrapper<>();

        update.set("status", SeckillOrderEvent.STATUS_PUBLISH_UNKNOWN)
                .set("last_error_code", limitText(errorCode, 64))
                .set("last_error", limitError(errorMessage))
                .setSql("next_retry_time = DATE_ADD(CURRENT_TIMESTAMP, "
                        + "INTERVAL " + delaySeconds + " SECOND)")
                .setSql("row_version = row_version + 1")
                .eq("event_id", eventId)
                .in("status",
                        SeckillOrderEvent.STATUS_PENDING,
                        SeckillOrderEvent.STATUS_PUBLISH_UNKNOWN);

        if (eventMapper.update(null, update) != 1) {
            return existing.getStatus();
        }

        if (existing.getStatus() == SeckillOrderEvent.STATUS_PENDING) {
            log.warn(
                    "秒杀订单事件进入 PUBLISH_UNKNOWN 低频补偿，eventId={}，已完成发送次数={}",
                    eventId,
                    completedAttempts
            );
        }

        return SeckillOrderEvent.STATUS_PUBLISH_UNKNOWN;
    }

    /**
     * 正常发送后把 next_retry_time 推迟到退避时间。
     *
     * <p>Confirm 回调正常会在此之前推进事件状态；确认丢失时事件到期后
     * 由 Outbox 重发，避免事件停留在“已到期”形成热循环。</p>
     */
    public boolean deferNextRetryTime(String eventId, long delaySeconds) {
        UpdateWrapper<SeckillOrderEvent> update = new UpdateWrapper<>();

        update.setSql("next_retry_time = DATE_ADD(CURRENT_TIMESTAMP, "
                        + "INTERVAL " + delaySeconds + " SECOND)")
                .setSql("row_version = row_version + 1")
                .eq("event_id", eventId)
                .in("status",
                        SeckillOrderEvent.STATUS_PENDING,
                        SeckillOrderEvent.STATUS_PUBLISH_UNKNOWN);

        return eventMapper.update(null, update) == 1;
    }

    /**
     * Outbox 任务用租约抢占一个待发布事件，防止多实例重复发送。
     *
     * @return 新的 fencing token；抢占失败返回 null
     */
    public Long claimLease(
            String eventId,
            String owner,
            int leaseSeconds) {

        UpdateWrapper<SeckillOrderEvent> update = new UpdateWrapper<>();

        update.set("lease_owner", owner)
                .setSql("lease_until = DATE_ADD(CURRENT_TIMESTAMP, INTERVAL "
                        + leaseSeconds + " SECOND)")
                .setSql("lease_token = lease_token + 1")
                .setSql("row_version = row_version + 1")
                .eq("event_id", eventId)
                .in("status",
                        SeckillOrderEvent.STATUS_PENDING,
                        SeckillOrderEvent.STATUS_PUBLISH_UNKNOWN)
                .apply("(lease_until IS NULL OR lease_until <= CURRENT_TIMESTAMP)");

        if (eventMapper.update(null, update) != 1) {
            return null;
        }

        SeckillOrderEvent claimed = eventMapper.selectById(eventId);

        if (claimed == null || !owner.equals(claimed.getLeaseOwner())) {
            return null;
        }

        return claimed.getLeaseToken();
    }

    /**
     * 发布调用结束后释放租约；携带 token，过期旧任务不能清除新任务租约。
     */
    public boolean releaseLease(String eventId, long leaseToken) {
        UpdateWrapper<SeckillOrderEvent> update = new UpdateWrapper<>();

        update.set("lease_owner", null)
                .set("lease_until", null)
                .eq("event_id", eventId)
                .eq("lease_token", leaseToken);

        return eventMapper.update(null, update) == 1;
    }

    /**
     * 回滚任务 CAS 抢占 ROLLBACK_PENDING 事件并记录执行令牌。
     *
     * @return 执行令牌；抢占失败返回 null
     */
    public Long claimForRollback(String eventId, String owner) {
        UpdateWrapper<SeckillOrderEvent> update = new UpdateWrapper<>();

        update.set("status", SeckillOrderEvent.STATUS_ROLLBACK_EXECUTING)
                .set("lease_owner", owner)
                .setSql("lease_until = DATE_ADD(CURRENT_TIMESTAMP, "
                        + "INTERVAL 300 SECOND)")
                .setSql("lease_token = lease_token + 1")
                .setSql("rollback_retry_count = rollback_retry_count + 1")
                .setSql("row_version = row_version + 1")
                .eq("event_id", eventId)
                .eq("status", SeckillOrderEvent.STATUS_ROLLBACK_PENDING)
                .apply("(next_retry_time IS NULL "
                        + "OR next_retry_time <= CURRENT_TIMESTAMP)");

        if (eventMapper.update(null, update) != 1) {
            return null;
        }

        SeckillOrderEvent claimed = eventMapper.selectById(eventId);

        if (claimed == null
                || claimed.getStatus()
                != SeckillOrderEvent.STATUS_ROLLBACK_EXECUTING) {
            return null;
        }

        return claimed.getLeaseToken();
    }

    /**
     * 回滚 Lua 成功或幂等确认后标记 ROLLED_BACK；携带执行令牌防止过期任务覆盖。
     */
    public boolean markRolledBack(String eventId, long leaseToken) {
        UpdateWrapper<SeckillOrderEvent> update = new UpdateWrapper<>();

        update.set("status", SeckillOrderEvent.STATUS_ROLLED_BACK)
                .set("next_retry_time", null)
                .set("lease_owner", null)
                .set("lease_until", null)
                .setSql("terminal_at = CURRENT_TIMESTAMP")
                .setSql("row_version = row_version + 1")
                .eq("event_id", eventId)
                .eq("status", SeckillOrderEvent.STATUS_ROLLBACK_EXECUTING)
                .eq("lease_token", leaseToken);

        return eventMapper.update(null, update) == 1;
    }

    /**
     * 回滚失败后按退避恢复 ROLLBACK_PENDING；耗尽上限转 MANUAL_REVIEW。
     *
     * @return 更新后的状态（ROLLBACK_PENDING 或 MANUAL_REVIEW）
     */
    public int revertToRollbackPending(
            String eventId,
            long leaseToken,
            String errorCode,
            String errorMessage) {

        SeckillOrderEvent existing = eventMapper.selectById(eventId);

        if (existing == null
                || existing.getStatus()
                != SeckillOrderEvent.STATUS_ROLLBACK_EXECUTING
                || existing.getLeaseToken() == null
                || existing.getLeaseToken() != leaseToken) {
            return existing == null
                    ? SeckillOrderEvent.STATUS_MANUAL_REVIEW
                    : existing.getStatus();
        }

        int completedAttempts = existing.getRollbackRetryCount() == null
                ? 0
                : existing.getRollbackRetryCount();
        long delaySeconds = SeckillRollbackRetryPolicy
                .nextDelaySeconds(completedAttempts);

        if (delaySeconds == SeckillRollbackRetryPolicy.STOP_AUTOMATIC_RETRY) {
            UpdateWrapper<SeckillOrderEvent> update = new UpdateWrapper<>();

            update.set("status", SeckillOrderEvent.STATUS_MANUAL_REVIEW)
                    .set("last_error_code", limitText(errorCode, 64))
                    .set("last_error", limitError(errorMessage))
                    .set("next_retry_time", null)
                    .set("lease_owner", null)
                    .set("lease_until", null)
                    .setSql("terminal_at = CURRENT_TIMESTAMP")
                    .setSql("row_version = row_version + 1")
                    .eq("event_id", eventId)
                    .eq("status", SeckillOrderEvent.STATUS_ROLLBACK_EXECUTING)
                    .eq("lease_token", leaseToken);

            return eventMapper.update(null, update) == 1
                    ? SeckillOrderEvent.STATUS_MANUAL_REVIEW
                    : existing.getStatus();
        }

        UpdateWrapper<SeckillOrderEvent> update = new UpdateWrapper<>();

        update.set("status", SeckillOrderEvent.STATUS_ROLLBACK_PENDING)
                .set("last_error_code", limitText(errorCode, 64))
                .set("last_error", limitError(errorMessage))
                .set("lease_owner", null)
                .set("lease_until", null)
                .setSql("next_retry_time = DATE_ADD(CURRENT_TIMESTAMP, "
                        + "INTERVAL " + delaySeconds + " SECOND)")
                .setSql("row_version = row_version + 1")
                .eq("event_id", eventId)
                .eq("status", SeckillOrderEvent.STATUS_ROLLBACK_EXECUTING)
                .eq("lease_token", leaseToken);

        return eventMapper.update(null, update) == 1
                ? SeckillOrderEvent.STATUS_ROLLBACK_PENDING
                : existing.getStatus();
    }

    /**
     * 人工处置：把 MANUAL_REVIEW/DLQ 事件重新置为 PENDING，由 Outbox 重放。
     */
    public boolean markReplayedForManualRetry(String eventId) {
        UpdateWrapper<SeckillOrderEvent> update = new UpdateWrapper<>();

        update.set("status", SeckillOrderEvent.STATUS_PENDING)
                .set("next_retry_time", LocalDateTime.now())
                .setSql("row_version = row_version + 1")
                .eq("event_id", eventId)
                .in("status",
                        SeckillOrderEvent.STATUS_MANUAL_REVIEW,
                        SeckillOrderEvent.STATUS_DLQ);

        return eventMapper.update(null, update) == 1;
    }

    /**
     * 人工处置：把 MANUAL_REVIEW 事件置为 ROLLBACK_PENDING，由回滚任务执行。
     */
    public boolean markManualRollback(String eventId) {
        UpdateWrapper<SeckillOrderEvent> update = new UpdateWrapper<>();

        update.set("status", SeckillOrderEvent.STATUS_ROLLBACK_PENDING)
                .set("next_retry_time", LocalDateTime.now())
                .setSql("row_version = row_version + 1")
                .eq("event_id", eventId)
                .eq("status", SeckillOrderEvent.STATUS_MANUAL_REVIEW);

        return eventMapper.update(null, update) == 1;
    }

    /**
     * 查询已经到期、可被 Outbox 领取的 PENDING/PUBLISH_UNKNOWN 事件。
     */
    public List<SeckillOrderEvent> findDueForPublish(int limit) {
        QueryWrapper<SeckillOrderEvent> query = new QueryWrapper<>();

        query.in("status",
                        SeckillOrderEvent.STATUS_PENDING,
                        SeckillOrderEvent.STATUS_PUBLISH_UNKNOWN)
                .isNotNull("next_retry_time")
                .apply("next_retry_time <= CURRENT_TIMESTAMP")
                .apply("(lease_until IS NULL OR lease_until <= CURRENT_TIMESTAMP)")
                .orderByAsc("next_retry_time")
                .last("LIMIT " + safeLimit(limit));

        return eventMapper.selectList(query);
    }

    /**
     * 查询已经到期、等待回滚任务执行的 ROLLBACK_PENDING 事件。
     */
    public List<SeckillOrderEvent> findDueForRollback(int limit) {
        QueryWrapper<SeckillOrderEvent> query = new QueryWrapper<>();

        query.eq("status", SeckillOrderEvent.STATUS_ROLLBACK_PENDING)
                .apply("(next_retry_time IS NULL "
                        + "OR next_retry_time <= CURRENT_TIMESTAMP)")
                .orderByAsc("next_retry_time")
                .last("LIMIT " + safeLimit(limit));

        return eventMapper.selectList(query);
    }

    /**
     * 查询长时间停留在 ROLLBACK_EXECUTING 的事件（回滚任务崩溃后的兜底）。
     */
    public List<SeckillOrderEvent> findRollbackExecutingStuck(
            int olderThanMinutes,
            int limit) {

        QueryWrapper<SeckillOrderEvent> query = new QueryWrapper<>();

        query.eq("status", SeckillOrderEvent.STATUS_ROLLBACK_EXECUTING)
                .apply("update_time < DATE_SUB(CURRENT_TIMESTAMP, INTERVAL "
                        + olderThanMinutes + " MINUTE)")
                .orderByAsc("update_time")
                .last("LIMIT " + safeLimit(limit));

        return eventMapper.selectList(query);
    }

    /**
     * 查询超过最大存活时间的 PUBLISH_UNKNOWN 事件（对账任务转人工）。
     */
    public List<SeckillOrderEvent> findPublishUnknownOlderThan(
            int olderThanHours,
            int limit) {

        QueryWrapper<SeckillOrderEvent> query = new QueryWrapper<>();

        query.eq("status", SeckillOrderEvent.STATUS_PUBLISH_UNKNOWN)
                .apply("create_time < DATE_SUB(CURRENT_TIMESTAMP, INTERVAL "
                        + olderThanHours + " HOUR)")
                .orderByAsc("create_time")
                .last("LIMIT " + safeLimit(limit));

        return eventMapper.selectList(query);
    }

    /**
     * 查询最近进入 CONSUMED 的事件（对账任务执行预留完成脚本）。
     * 按终态时间倒序分批扫描，幂等执行不产生副作用。
     */
    public List<SeckillOrderEvent> findConsumedRecent(
            int withinMinutes, int limit) {
        QueryWrapper<SeckillOrderEvent> query = new QueryWrapper<>();

        query.eq("status", SeckillOrderEvent.STATUS_CONSUMED)
                .apply("consumed_at >= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL "
                        + withinMinutes + " MINUTE)")
                .orderByDesc("consumed_at")
                .last("LIMIT " + safeLimit(limit));

        return eventMapper.selectList(query);
    }

    /**
     * 查询最近进入 ROLLED_BACK 的事件（对账任务确认预留清理）。
     */
    public List<SeckillOrderEvent> findRolledBackRecent(
            int withinMinutes, int limit) {
        QueryWrapper<SeckillOrderEvent> query = new QueryWrapper<>();

        query.eq("status", SeckillOrderEvent.STATUS_ROLLED_BACK)
                .apply("terminal_at >= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL "
                        + withinMinutes + " MINUTE)")
                .orderByDesc("terminal_at")
                .last("LIMIT " + safeLimit(limit));

        return eventMapper.selectList(query);
    }

    /**
     * 对账任务权威收敛卡死的 ROLLBACK_EXECUTING 事件（规格第 12.2 节）。
     *
     * <p>Redis 预留详情仍存在说明回滚 Lua 尚未执行，恢复为 ROLLBACK_PENDING
     * 由回滚任务重试；预留详情已移除说明 Lua 已执行完毕，直接标记 ROLLED_BACK。
     * 调用方必须先查询 Redis 预留状态再决定 luaAlreadyExecuted。</p>
     *
     * @return 是否成功收敛
     */
    public boolean convergeStuckRollbackExecuting(
            String eventId, boolean luaAlreadyExecuted) {

        int targetStatus = luaAlreadyExecuted
                ? SeckillOrderEvent.STATUS_ROLLED_BACK
                : SeckillOrderEvent.STATUS_ROLLBACK_PENDING;

        UpdateWrapper<SeckillOrderEvent> update = new UpdateWrapper<>();

        update.set("status", targetStatus)
                .set("lease_owner", null)
                .set("lease_until", null)
                .setSql("row_version = row_version + 1")
                .eq("event_id", eventId)
                .eq("status",
                        SeckillOrderEvent.STATUS_ROLLBACK_EXECUTING);

        if (luaAlreadyExecuted) {
            update.set("next_retry_time", null)
                    .setSql("terminal_at = CURRENT_TIMESTAMP");
        } else {
            update.setSql("next_retry_time = CURRENT_TIMESTAMP");
        }

        return eventMapper.update(null, update) == 1;
    }

    /**
     * 按订单 ID 查询事件（订单状态查询接口使用）。
     */
    public SeckillOrderEvent findByOrderId(Long orderId) {
        if (orderId == null) {
            return null;
        }

        QueryWrapper<SeckillOrderEvent> query = new QueryWrapper<>();
        query.eq("order_id", orderId);

        List<SeckillOrderEvent> events = eventMapper.selectList(query);
        return events.isEmpty() ? null : events.get(0);
    }

    /**
     * 按事件 ID 查询事件（失败决策服务和回滚任务使用）。
     */
    public SeckillOrderEvent findByEventId(String eventId) {
        if (eventId == null || eventId.trim().isEmpty()) {
            return null;
        }

        return eventMapper.selectById(eventId);
    }

    /**
     * 统计某状态事件数量（监控任务使用）。
     */
    public Integer countByStatus(int status) {
        QueryWrapper<SeckillOrderEvent> query = new QueryWrapper<>();
        query.eq("status", status);

        return eventMapper.selectCount(query);
    }

    /**
     * 消费事务内锁定事件行。
     */
    public SeckillOrderEvent lockEvent(String eventId) {
        return eventMapper.selectByEventIdForUpdate(eventId);
    }

    /**
     * 统一执行带状态机来源条件与行版本递增的 CAS 更新。
     * 更新未命中时按“事件已处于目标状态”的幂等语义判断。
     */
    private boolean applyCasUpdate(
            String eventId,
            int targetStatus,
            UpdateWrapper<SeckillOrderEvent> update) {

        Set<Integer> sources =
                SeckillOrderEventStateMachine.allowedSources(targetStatus);

        if (sources.isEmpty()) {
            return false;
        }

        update.setSql("row_version = row_version + 1")
                .eq("event_id", eventId)
                .in("status", new ArrayList<>(sources));

        if (eventMapper.update(null, update) == 1) {
            return true;
        }

        SeckillOrderEvent existing = eventMapper.selectById(eventId);

        return existing != null
                && existing.getStatus() == targetStatus;
    }

    /**
     * 判断事件是否仍需要生产者侧补偿。
     */
    private boolean isPublishRetryable(Integer status) {
        return Integer.valueOf(SeckillOrderEvent.STATUS_PENDING).equals(status)
                || Integer.valueOf(SeckillOrderEvent.STATUS_PUBLISH_UNKNOWN)
                .equals(status);
    }

    /**
     * 规范化并截断错误信息，适配数据库 last_error 字段长度。
     */
    private String limitError(String errorMessage) {
        return limitText(errorMessage, 512);
    }

    /**
     * 截断文本到指定长度。
     */
    private String limitText(String text, int maxLength) {
        if (text == null || text.trim().isEmpty()) {
            return "unknown";
        }

        String normalized = text.trim();

        return normalized.length() <= maxLength
                ? normalized
                : normalized.substring(0, maxLength);
    }

    /**
     * 限制查询条数，避免把外部输入拼接进 SQL。
     */
    private int safeLimit(int limit) {
        return Math.max(1, Math.min(limit, 100));
    }
}
