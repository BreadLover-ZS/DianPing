package com.dish.review.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.dish.review.dto.SeckillOrderMessage;
import com.dish.review.entity.SeckillOrderEvent;
import com.dish.review.mapper.SeckillOrderEventMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理秒杀消息事件状态，为发布确认、消费完成和补偿重试提供持久化依据。
 */
@Service
public class SeckillOrderEventService {

    public static final int MAX_PUBLISH_RETRY_COUNT = 3;
    private static final int RETRY_LEASE_SECONDS = 30;

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

    /**
     * 将 PENDING 改为 CONFIRMED；重复 ACK 或已消费事件按幂等成功处理。
     */
    public boolean markConfirmed(String eventId) {
        UpdateWrapper<SeckillOrderEvent> update = new UpdateWrapper<>();

        update.set("status", SeckillOrderEvent.STATUS_CONFIRMED)
                .set("last_error", null)
                .set("next_retry_time", null)
                .eq("event_id", eventId)
                .eq("status", SeckillOrderEvent.STATUS_PENDING);

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
                        SeckillOrderEvent.STATUS_CONFIRMED
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

    /**
     * 将 PENDING/CONFIRMED 改为 CONSUMED；重复消费完成按幂等成功处理。
     */
    public boolean markConsumed(String eventId) {
        UpdateWrapper<SeckillOrderEvent> update = new UpdateWrapper<>();

        update.set("status", SeckillOrderEvent.STATUS_CONSUMED)
                .set("last_error", null)
                .set("next_retry_time", null)
                .eq("event_id", eventId)
                .in(
                        "status",
                        SeckillOrderEvent.STATUS_PENDING,
                        SeckillOrderEvent.STATUS_CONFIRMED
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
     * 为发送结果未知的事件安排一次有限重试。
     * retryCount 表示已经安排过的补偿次数，退避时间为 1、2、4 秒。
     */
    public boolean scheduleRetry(
            String eventId,
            String errorMessage) {

        SeckillOrderEvent existingEvent = eventMapper.selectById(eventId);

        if (existingEvent == null
                || !Integer.valueOf(SeckillOrderEvent.STATUS_PENDING)
                .equals(existingEvent.getStatus())) {
            return false;
        }

        int currentRetryCount = existingEvent.getRetryCount() == null
                ? 0
                : existingEvent.getRetryCount();
        int nextRetryCount = currentRetryCount + 1;

        if (nextRetryCount > MAX_PUBLISH_RETRY_COUNT) {
            return markFailed(
                    eventId,
                    "publish_retry_exhausted: " + errorMessage
            );
        }

        long delaySeconds = 1L << (nextRetryCount - 1);
        UpdateWrapper<SeckillOrderEvent> update = new UpdateWrapper<>();

        update.set("retry_count", nextRetryCount)
                .set(
                        "next_retry_time",
                        LocalDateTime.now().plusSeconds(delaySeconds)
                )
                .set("last_error", limitError(errorMessage))
                .eq("event_id", eventId)
                .eq("status", SeckillOrderEvent.STATUS_PENDING)
                .eq("retry_count", currentRetryCount);

        return eventMapper.update(null, update) == 1;
    }

    /**
     * 查询已经到达重试时间的 PENDING 事件。
     * LIMIT 使用固定常量，避免把外部输入拼接进 SQL。
     */
    public List<SeckillOrderEvent> findDueRetries(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));

        QueryWrapper<SeckillOrderEvent> query = new QueryWrapper<>();
        query.eq("status", SeckillOrderEvent.STATUS_PENDING)
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

        UpdateWrapper<SeckillOrderEvent> update = new UpdateWrapper<>();
        update.set(
                        "next_retry_time",
                        LocalDateTime.now().plusSeconds(RETRY_LEASE_SECONDS)
                )
                .eq("event_id", event.getEventId())
                .eq("status", SeckillOrderEvent.STATUS_PENDING)
                .le("next_retry_time", LocalDateTime.now());

        return eventMapper.update(null, update) == 1;
    }
}
