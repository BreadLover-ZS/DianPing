package com.dish.review.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.dish.review.entity.SeckillOrderEvent;
import com.dish.review.entity.SeckillPublishAttempt;
import com.dish.review.mapper.SeckillOrderEventMapper;
import com.dish.review.mapper.SeckillPublishAttemptMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 发布尝试证据服务：为每一次实际 RabbitMQ 发送创建独立记录，
 * Confirm、Return 和同步异常分别更新，不互相覆盖。
 */
@Service
@Slf4j
public class SeckillPublishAttemptService {

    private final SeckillPublishAttemptMapper attemptMapper;

    private final SeckillOrderEventMapper eventMapper;

    /**
     * 注入发布尝试表和事件表 Mapper。
     */
    public SeckillPublishAttemptService(
            SeckillPublishAttemptMapper attemptMapper,
            SeckillOrderEventMapper eventMapper) {
        this.attemptMapper = attemptMapper;
        this.eventMapper = eventMapper;
    }

    /**
     * Outbox 在同一 MySQL 事务内创建发布尝试并递增事件 retry_count。
     * 事件状态不再可发布时抛异常，阻止整个事务（含发送）。
     */
    @Transactional
    public SeckillPublishAttempt createNextAttempt(String eventId) {
        UpdateWrapper<SeckillOrderEvent> update = new UpdateWrapper<>();

        update.setSql("retry_count = retry_count + 1")
                .eq("event_id", eventId)
                .in("status",
                        SeckillOrderEvent.STATUS_PENDING,
                        SeckillOrderEvent.STATUS_PUBLISH_UNKNOWN);

        if (eventMapper.update(null, update) != 1) {
            throw new IllegalStateException(
                    "秒杀订单事件不可发布或已被其他实例处理，eventId="
                            + eventId
            );
        }

        SeckillOrderEvent fresh = eventMapper.selectById(eventId);

        if (fresh == null
                || fresh.getRetryCount() == null
                || fresh.getRetryCount() < 1) {
            throw new IllegalStateException(
                    "秒杀订单事件读取失败，eventId=" + eventId
            );
        }

        SeckillPublishAttempt attempt = new SeckillPublishAttempt()
                .setAttemptId(UUID.randomUUID().toString())
                .setEventId(eventId)
                .setAttemptNo(fresh.getRetryCount())
                .setConfirmStatus(SeckillPublishAttempt.CONFIRM_WAITING)
                .setReturned(false)
                .setSendException(false)
                // 发送时间在创建时记录：进程在发送前崩溃也能被超时任务发现
                .setSentAt(LocalDateTime.now());

        attemptMapper.insert(attempt);

        return attempt;
    }

    /**
     * Confirm ACK：记录确认时间；只有 WAITING 状态的尝试会被更新（幂等）。
     */
    public boolean recordAck(String attemptId) {
        UpdateWrapper<SeckillPublishAttempt> update = new UpdateWrapper<>();

        update.set("confirm_status", SeckillPublishAttempt.CONFIRM_ACK)
                .setSql("confirm_at = CURRENT_TIMESTAMP")
                .eq("attempt_id", attemptId)
                .eq("confirm_status", SeckillPublishAttempt.CONFIRM_WAITING);

        return attemptMapper.update(null, update) == 1;
    }

    /**
     * Confirm NACK：Broker 明确拒绝承担该次消息。
     */
    public boolean recordNack(String attemptId, String errorMessage) {
        UpdateWrapper<SeckillPublishAttempt> update = new UpdateWrapper<>();

        update.set("confirm_status", SeckillPublishAttempt.CONFIRM_NACK)
                .set("error_code", "confirm_nack")
                .set("error_message", limitText(errorMessage, 512))
                .setSql("confirm_at = CURRENT_TIMESTAMP")
                .eq("attempt_id", attemptId)
                .eq("confirm_status", SeckillPublishAttempt.CONFIRM_WAITING);

        return attemptMapper.update(null, update) == 1;
    }

    /**
     * 同步异常、连接关闭或确认超时：结果未知。
     */
    public boolean recordUnknown(
            String attemptId,
            String errorCode,
            String errorMessage) {

        UpdateWrapper<SeckillPublishAttempt> update = new UpdateWrapper<>();

        update.set("confirm_status", SeckillPublishAttempt.CONFIRM_UNKNOWN)
                .set("send_exception", true)
                .set("error_code", limitText(errorCode, 64))
                .set("error_message", limitText(errorMessage, 512))
                .eq("attempt_id", attemptId)
                .eq("confirm_status", SeckillPublishAttempt.CONFIRM_WAITING);

        return attemptMapper.update(null, update) == 1;
    }

    /**
     * ReturnCallback 幂等记录 returned=true；不单独决定事件回滚。
     */
    public boolean recordReturned(String attemptId) {
        UpdateWrapper<SeckillPublishAttempt> update = new UpdateWrapper<>();

        update.set("returned", true)
                .setSql("return_at = CURRENT_TIMESTAMP")
                .eq("attempt_id", attemptId);

        return attemptMapper.update(null, update) == 1;
    }

    /**
     * 确认超时任务：把超过超时时间仍 WAITING 的尝试标记为 UNKNOWN。
     */
    public List<SeckillPublishAttempt> findWaitingTimeout(
            int timeoutSeconds,
            int limit) {

        QueryWrapper<SeckillPublishAttempt> query = new QueryWrapper<>();

        query.eq("confirm_status", SeckillPublishAttempt.CONFIRM_WAITING)
                .apply("sent_at < DATE_SUB(CURRENT_TIMESTAMP, INTERVAL "
                        + timeoutSeconds + " SECOND)")
                .orderByAsc("sent_at")
                .last("LIMIT " + Math.max(1, Math.min(limit, 100)));

        return attemptMapper.selectList(query);
    }

    /**
     * 查询某事件的全部发送尝试证据（失败决策使用）。
     */
    public List<SeckillPublishAttempt> findByEventId(String eventId) {
        QueryWrapper<SeckillPublishAttempt> query = new QueryWrapper<>();

        query.eq("event_id", eventId)
                .orderByAsc("attempt_no");

        return attemptMapper.selectList(query);
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
}
