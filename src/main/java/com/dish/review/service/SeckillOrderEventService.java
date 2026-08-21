package com.dish.review.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.dish.review.dto.SeckillOrderMessage;
import com.dish.review.entity.SeckillOrderEvent;
import com.dish.review.mapper.SeckillOrderEventMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理秒杀消息事件状态，为发布确认、消费完成和补偿重试提供持久化依据。
 */
@Service
@Slf4j
public class SeckillOrderEventService {

    /** 首次发布后的快速补偿次数，退避时间为 1、2、4 秒。 */
    public static final int MAX_PUBLISH_RETRY_COUNT = 3;
    private static final int RETRY_LEASE_SECONDS = 30;
    /** 快速补偿耗尽后，PUBLISH_UNKNOWN 事件的低频重发间隔。 */
    private static final int UNKNOWN_RETRY_DELAY_SECONDS = 60;

    private final SeckillOrderEventMapper eventMapper;

    /**
     * 注入事件表 Mapper。
     */
    public SeckillOrderEventService(
            SeckillOrderEventMapper eventMapper) {
        this.eventMapper = eventMapper;
    }

    /**
     * 发布前创建 PENDING 事件，并设置 30 秒初始兜底时间覆盖进程中断窗口。
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
        // 给“写入事件后进程立即崩溃”的窗口预留兜底时间；
        // 发布确认成功后 markConfirmed 会清空该时间。
        event.setNextRetryTime(
                LocalDateTime.now().plusSeconds(30)
        );

        int insertedRows = eventMapper.insert(event);

        if (insertedRows != 1) {
            throw new IllegalStateException(
                    "秒杀订单 PENDING 事件写入失败，eventId="
                            + message.getEventId()
            );
        }
    }

    /** 将等待确认的事件改为 CONFIRMED；重复 ACK 或已消费事件按幂等成功处理。 */
    public boolean markConfirmed(String eventId) {
        UpdateWrapper<SeckillOrderEvent> update = new UpdateWrapper<>();

        update.set("status", SeckillOrderEvent.STATUS_CONFIRMED)
                .set("last_error", null)
                .set("next_retry_time", null)
                .eq("event_id", eventId)
                .in(
                        "status",
                        SeckillOrderEvent.STATUS_PENDING,
                        SeckillOrderEvent.STATUS_PUBLISH_UNKNOWN
                );

        int updatedRows = eventMapper.update(null, update);

        if (updatedRows == 1) {
            return true;
        }

        SeckillOrderEvent existingEvent =
                eventMapper.selectById(eventId);

        if (existingEvent == null) {
            return false;
        }

        Integer status = existingEvent.getStatus();

        return Integer.valueOf(SeckillOrderEvent.STATUS_CONFIRMED)
                .equals(status)
                || Integer.valueOf(SeckillOrderEvent.STATUS_CONSUMED)
                .equals(status);
    }

    /**
     * 将未完成事件改为 FAILED 并保存原因；重复 FAILED 按幂等成功处理。
     */
    public boolean markFailed(String eventId, String errorMessage) {
        UpdateWrapper<SeckillOrderEvent> update = new UpdateWrapper<>();

        update.set("status", SeckillOrderEvent.STATUS_FAILED)
                .set("last_error", limitError(errorMessage))
                .set("next_retry_time", null)
                .eq("event_id", eventId)
                .in(
                        "status",
                        SeckillOrderEvent.STATUS_PENDING,
                        SeckillOrderEvent.STATUS_CONFIRMED,
                        SeckillOrderEvent.STATUS_PUBLISH_UNKNOWN
                );

        int updatedRows = eventMapper.update(null, update);

        if (updatedRows == 1) {
            return true;
        }

        SeckillOrderEvent existingEvent =
                eventMapper.selectById(eventId);

        return existingEvent != null
                && Integer.valueOf(SeckillOrderEvent.STATUS_FAILED)
                .equals(existingEvent.getStatus());
    }

    /**
     * 记录可以确定的首次发布失败。
     * 已经发生过补偿的事件可能有更早的消息到达 Broker，此时单次 NACK/Return
     * 不能证明整个业务事件失败，因此保持原状态并禁止直接回滚 Redis。
     */
    public boolean markInitialPublishFailed(
            String eventId,
            String errorMessage) {
        UpdateWrapper<SeckillOrderEvent> update = new UpdateWrapper<>();

        update.set("status", SeckillOrderEvent.STATUS_FAILED)
                .set("last_error", limitError(errorMessage))
                .set("next_retry_time", null)
                .eq("event_id", eventId)
                .eq("status", SeckillOrderEvent.STATUS_PENDING)
                .eq("retry_count", 1)
                .isNull("last_error");

        int updatedRows = eventMapper.update(null, update);

        if (updatedRows == 1) {
            return true;
        }

        SeckillOrderEvent existingEvent = eventMapper.selectById(eventId);

        if (existingEvent != null
                && Integer.valueOf(SeckillOrderEvent.STATUS_FAILED)
                .equals(existingEvent.getStatus())) {
            return true;
        }

        log.warn(
                "单次发布失败不足以判定整个事件失败，保留补偿状态，eventId={}，status={}，retryCount={}",
                eventId,
                existingEvent == null ? null : existingEvent.getStatus(),
                existingEvent == null ? null : existingEvent.getRetryCount()
        );
        return false;
    }

    /**
     * 规范化并截断错误信息，适配数据库 last_error 字段长度。
     */
    private String limitError(String errorMessage) {
        if (errorMessage == null || errorMessage.trim().isEmpty()) {
            return "unknown";
        }

        String normalized = errorMessage.trim();

        return normalized.length() <= 512
                ? normalized
                : normalized.substring(0, 512);
    }

    /** 将未完成事件改为 CONSUMED；重复消费完成按幂等成功处理。 */
    public boolean markConsumed(String eventId) {
        UpdateWrapper<SeckillOrderEvent> update = new UpdateWrapper<>();

        update.set("status", SeckillOrderEvent.STATUS_CONSUMED)
                .set("last_error", null)
                .set("next_retry_time", null)
                .eq("event_id", eventId)
                .in(
                        "status",
                        SeckillOrderEvent.STATUS_PENDING,
                        SeckillOrderEvent.STATUS_CONFIRMED,
                        SeckillOrderEvent.STATUS_PUBLISH_UNKNOWN
                );

        int updatedRows = eventMapper.update(null, update);

        if (updatedRows == 1) {
            return true;
        }

        SeckillOrderEvent existingEvent =
                eventMapper.selectById(eventId);

        return existingEvent != null
                && Integer.valueOf(SeckillOrderEvent.STATUS_CONSUMED)
                .equals(existingEvent.getStatus());
    }

    /**
     * 为发送结果未知的事件安排下一次补偿。
     * 前三次使用 1、2、4 秒快速退避；耗尽后进入 PUBLISH_UNKNOWN，
     * 每 60 秒低频重发，避免把“结果未知”误判成确定失败或直接恢复 Redis。
     */
    public boolean scheduleRetry(
            String eventId,
            String errorMessage) {

        SeckillOrderEvent existingEvent = eventMapper.selectById(eventId);

        //事件在数据库中不存在 或者 状态码不合法 则直接失败
        if (existingEvent == null
                || !isPublishRetryable(existingEvent.getStatus())) {
            return false;
        }

        int currentRetryCount = existingEvent.getRetryCount() == null
                ? 0
                : existingEvent.getRetryCount();

        int currentStatus = existingEvent.getStatus();
        int nextStatus = resolveStatusAfterPublishFailure(
                currentStatus,
                currentRetryCount
        );
        long delaySeconds = resolveRetryDelaySeconds(
                nextStatus,
                currentRetryCount
        );
        String recordedError = nextStatus
                == SeckillOrderEvent.STATUS_PUBLISH_UNKNOWN
                ? "publish_result_unknown: " + errorMessage
                : errorMessage;
        UpdateWrapper<SeckillOrderEvent> update = new UpdateWrapper<>();

        update.set("status", nextStatus)
                .set(
                        "next_retry_time",
                        LocalDateTime.now().plusSeconds(delaySeconds)
                )
                .set("last_error", limitError(recordedError))
                .eq("event_id", eventId)
                .eq("status", currentStatus)
                .eq("retry_count", currentRetryCount);

        boolean updated = eventMapper.update(null, update) == 1;

        if (updated
                && currentStatus != SeckillOrderEvent.STATUS_PUBLISH_UNKNOWN
                && nextStatus == SeckillOrderEvent.STATUS_PUBLISH_UNKNOWN) {
            log.error(
                    "秒杀订单快速发布补偿耗尽，进入低频未知状态重试，eventId={}，retryCount={}",
                    eventId,
                    currentRetryCount
            );
        }

        return updated;
    }

    /**
     * 在每次真正调用 RabbitTemplate 前记录一次高层发布尝试。
     * 第四次调用仍是最后一次快速补偿，但事件提前进入 PUBLISH_UNKNOWN，
     * 从而让“发送返回但 Confirm 丢失”的场景也能转入低频兜底。
     */
    public boolean recordPublishAttempt(String eventId) {
        SeckillOrderEvent existingEvent = eventMapper.selectById(eventId);

        if (existingEvent == null
                || !isPublishRetryable(existingEvent.getStatus())) {
            return false;
        }

        int currentAttemptCount = existingEvent.getRetryCount() == null
                ? 0
                : existingEvent.getRetryCount();
        int nextAttemptCount = currentAttemptCount + 1;
        int currentStatus = existingEvent.getStatus();
        int nextStatus = resolveStatusBeforePublish(
                currentStatus,
                nextAttemptCount
        );

        UpdateWrapper<SeckillOrderEvent> update = new UpdateWrapper<>();
        update.set("status", nextStatus)
                .set("retry_count", nextAttemptCount)
                .eq("event_id", eventId)
                .eq("status", currentStatus)
                .eq("retry_count", currentAttemptCount);

        if (currentStatus == SeckillOrderEvent.STATUS_PENDING
                && nextStatus
                == SeckillOrderEvent.STATUS_PUBLISH_UNKNOWN) {
            update.set(
                    "next_retry_time",
                    LocalDateTime.now().plusSeconds(
                            UNKNOWN_RETRY_DELAY_SECONDS
                    )
            );
        }

        return eventMapper.update(null, update) == 1;
    }

    /**
     * 查询已经到达重试时间的 PENDING/PUBLISH_UNKNOWN 事件。
     * LIMIT 使用固定常量，避免把外部输入拼接进 SQL。
     */
    public List<SeckillOrderEvent> findDueRetries(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));

        QueryWrapper<SeckillOrderEvent> query = new QueryWrapper<>();
        query.in(
                        "status",
                        SeckillOrderEvent.STATUS_PENDING,
                        SeckillOrderEvent.STATUS_PUBLISH_UNKNOWN
                )
                .isNotNull("next_retry_time")
                .le("next_retry_time", LocalDateTime.now())
                .orderByAsc("next_retry_time")
                .last("LIMIT " + safeLimit);

        return eventMapper.selectList(query);
    }

    /**
     * 抢占一个待补偿事件，设置短租约，避免多个定时任务实例同时重发。
     */
    public boolean claimForRetry(SeckillOrderEvent event) {
        if (event == null || event.getEventId() == null) {
            return false;
        }

        Integer status = event.getStatus();

        if (!isPublishRetryable(status)) {
            return false;
        }

        int retryDelaySeconds = Integer.valueOf(
                SeckillOrderEvent.STATUS_PUBLISH_UNKNOWN
        ).equals(status)
                ? UNKNOWN_RETRY_DELAY_SECONDS
                : RETRY_LEASE_SECONDS;

        UpdateWrapper<SeckillOrderEvent> update = new UpdateWrapper<>();
        update.set(
                        "next_retry_time",
                        LocalDateTime.now().plusSeconds(retryDelaySeconds)
                )
                .eq("event_id", event.getEventId())
                .eq("status", status)
                .le("next_retry_time", LocalDateTime.now());

        return eventMapper.update(null, update) == 1;
    }

    /** 判断事件是否仍需要生产者侧补偿。 */
    private boolean isPublishRetryable(Integer status) {
        return Integer.valueOf(SeckillOrderEvent.STATUS_PENDING)
                .equals(status)
                || Integer.valueOf(
                        SeckillOrderEvent.STATUS_PUBLISH_UNKNOWN
                ).equals(status);
    }

    /** 第四次高层 publish 是最后一次快速补偿，并开始使用未知状态兜底。 */
    static int resolveStatusBeforePublish(
            int currentStatus,
            int nextAttemptCount) {
        if (currentStatus == SeckillOrderEvent.STATUS_PENDING
                && nextAttemptCount <= MAX_PUBLISH_RETRY_COUNT) {
            return SeckillOrderEvent.STATUS_PENDING;
        }

        return SeckillOrderEvent.STATUS_PUBLISH_UNKNOWN;
    }

    /** 当前发布轮次失败后，决定继续快速退避还是进入低频未知状态。 */
    static int resolveStatusAfterPublishFailure(
            int currentStatus,
            int currentAttemptCount) {
        if (currentStatus == SeckillOrderEvent.STATUS_PENDING
                && currentAttemptCount <= MAX_PUBLISH_RETRY_COUNT) {
            return SeckillOrderEvent.STATUS_PENDING;
        }

        return SeckillOrderEvent.STATUS_PUBLISH_UNKNOWN;
    }

    /** 根据状态计算下一次补偿时间，快速阶段 1/2/4 秒，未知阶段固定 60 秒。 */
    static long resolveRetryDelaySeconds(
            int nextStatus,
            int currentAttemptCount) {
        if (nextStatus == SeckillOrderEvent.STATUS_PUBLISH_UNKNOWN) {
            return UNKNOWN_RETRY_DELAY_SECONDS;
        }

        return 1L << Math.max(0, currentAttemptCount - 1);
    }
}
