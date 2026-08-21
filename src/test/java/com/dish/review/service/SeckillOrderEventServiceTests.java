package com.dish.review.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.dish.review.entity.SeckillOrderEvent;
import com.dish.review.mapper.SeckillOrderEventMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证秒杀事件状态机 CAS 保护、租约抢占和退避规则（规格第 6、9、11 节）。 */
class SeckillOrderEventServiceTests {

    private SeckillOrderEvent eventOf(String eventId, int status) {
        SeckillOrderEvent event = new SeckillOrderEvent();
        event.setEventId(eventId);
        event.setStatus(status);
        event.setRetryCount(0);
        event.setRollbackRetryCount(0);
        event.setRowVersion(0L);
        event.setLeaseToken(5L);
        return event;
    }

    @Test
    void lateConfirmCannotOverwriteConsumedTerminalState() {
        SeckillOrderEventMapper mapper = mock(SeckillOrderEventMapper.class);
        // CAS 更新未命中，且事件已是 CONSUMED 终态
        when(mapper.update(isNull(), any())).thenReturn(0);
        when(mapper.selectById("event-1"))
                .thenReturn(eventOf("event-1", SeckillOrderEvent.STATUS_CONSUMED));

        SeckillOrderEventService service =
                new SeckillOrderEventService(mapper);

        assertFalse(service.markConfirmed("event-1"));
        assertFalse(service.markRollbackPending("event-1", "nack", "nack"));
        assertFalse(service.markDlq("event-1", "dlq", "dlq"));
    }

    @Test
    void markingTargetStateAgainIsIdempotentSuccess() {
        SeckillOrderEventMapper mapper = mock(SeckillOrderEventMapper.class);
        when(mapper.update(isNull(), any())).thenReturn(0);
        when(mapper.selectById("event-2"))
                .thenReturn(eventOf("event-2", SeckillOrderEvent.STATUS_CONSUMED));

        SeckillOrderEventService service =
                new SeckillOrderEventService(mapper);

        assertTrue(service.markConsumed("event-2"));
    }

    @Test
    void cancelRollbackOnlyAppliesToRollbackPending() {
        SeckillOrderEventMapper mapper = mock(SeckillOrderEventMapper.class);
        when(mapper.update(isNull(), any())).thenReturn(1);

        SeckillOrderEventService service =
                new SeckillOrderEventService(mapper);

        assertTrue(service.cancelRollback("event-3"));
    }

    @Test
    void cancelRollbackFailsWhenStatusAdvanced() {
        SeckillOrderEventMapper mapper = mock(SeckillOrderEventMapper.class);
        when(mapper.update(isNull(), any())).thenReturn(0);

        SeckillOrderEventService service =
                new SeckillOrderEventService(mapper);

        assertFalse(service.cancelRollback("event-4"));
    }

    @Test
    void claimLeaseReturnsFencingTokenOnSuccess() {
        SeckillOrderEventMapper mapper = mock(SeckillOrderEventMapper.class);
        SeckillOrderEvent claimed = eventOf(
                "event-5", SeckillOrderEvent.STATUS_PUBLISH_UNKNOWN);
        claimed.setLeaseOwner("instance-1");
        claimed.setLeaseToken(6L);

        when(mapper.update(isNull(), any())).thenReturn(1);
        when(mapper.selectById("event-5")).thenReturn(claimed);

        SeckillOrderEventService service =
                new SeckillOrderEventService(mapper);

        assertEquals(Long.valueOf(6L),
                service.claimLease("event-5", "instance-1", 60));
    }

    @Test
    void claimLeaseFailsWhenEventMissing() {
        SeckillOrderEventMapper mapper = mock(SeckillOrderEventMapper.class);
        when(mapper.update(isNull(), any())).thenReturn(0);

        SeckillOrderEventService service =
                new SeckillOrderEventService(mapper);

        assertNull(service.claimLease("event-6", "instance-1", 60));
    }

    @Test
    void claimForRollbackRequiresRollbackPendingStatus() {
        SeckillOrderEventMapper mapper = mock(SeckillOrderEventMapper.class);
        SeckillOrderEvent claimed = eventOf(
                "event-7",
                SeckillOrderEvent.STATUS_ROLLBACK_EXECUTING);
        claimed.setLeaseToken(9L);

        when(mapper.update(isNull(), any())).thenReturn(1);
        when(mapper.selectById("event-7")).thenReturn(claimed);

        SeckillOrderEventService service =
                new SeckillOrderEventService(mapper);

        assertEquals(Long.valueOf(9L),
                service.claimForRollback("event-7", "rollback-instance"));
    }

    @Test
    void rolledBackMarkerRequiresMatchingLeaseToken() {
        SeckillOrderEventMapper mapper = mock(SeckillOrderEventMapper.class);
        when(mapper.update(isNull(), any())).thenReturn(0);

        SeckillOrderEventService service =
                new SeckillOrderEventService(mapper);

        // 执行令牌不匹配：过期任务不能覆盖新任务状态
        assertFalse(service.markRolledBack("event-8", 123L));
    }

    @Test
    void publishRetryExhaustedDefersFinalDecisionWindow() {
        SeckillOrderEventMapper mapper = mock(SeckillOrderEventMapper.class);
        // 已完成尝试次数达到上限：退避表耗尽
        SeckillOrderEvent existing = eventOf(
                "event-9", SeckillOrderEvent.STATUS_PUBLISH_UNKNOWN);
        existing.setRetryCount(SeckillPublishRetryPolicy.maxAutomaticAttempts());

        when(mapper.selectById("event-9")).thenReturn(existing);
        when(mapper.update(isNull(), any())).thenReturn(1);

        SeckillOrderEventService service =
                new SeckillOrderEventService(mapper);

        int status = service.schedulePublishRetry(
                "event-9", "confirm_timeout", "timeout");

        // 耗尽后禁止立即转人工：保持 PUBLISH_UNKNOWN 并推迟终局窗口，
        // 由 Outbox 扫描阶段在窗口到期后统一转人工
        assertEquals(SeckillOrderEvent.STATUS_PUBLISH_UNKNOWN, status);

        ArgumentCaptor<UpdateWrapper<SeckillOrderEvent>> captor =
                ArgumentCaptor.forClass(UpdateWrapper.class);

        verify(mapper).update(isNull(), captor.capture());

        String sqlSet = captor.getValue().getSqlSet();

        assertTrue(sqlSet.contains("next_retry_time"));
        assertTrue(sqlSet.contains(String.valueOf(
                SeckillPublishRetryPolicy.FINAL_DECISION_WAIT_SECONDS)));
    }

    @Test
    void publishRetrySchedulesUnknownWithBackoff() {
        SeckillOrderEventMapper mapper = mock(SeckillOrderEventMapper.class);
        SeckillOrderEvent existing = eventOf(
                "event-10", SeckillOrderEvent.STATUS_PUBLISH_UNKNOWN);
        existing.setRetryCount(2);

        when(mapper.selectById("event-10")).thenReturn(existing);
        when(mapper.update(isNull(), any())).thenReturn(1);

        SeckillOrderEventService service =
                new SeckillOrderEventService(mapper);

        int status = service.schedulePublishRetry(
                "event-10", "send_exception", "exception");

        assertEquals(SeckillOrderEvent.STATUS_PUBLISH_UNKNOWN, status);
    }

    @Test
    void publishRetrySkipsTerminalEvent() {
        SeckillOrderEventMapper mapper = mock(SeckillOrderEventMapper.class);
        when(mapper.selectById("event-11"))
                .thenReturn(eventOf("event-11", SeckillOrderEvent.STATUS_CONSUMED));

        SeckillOrderEventService service =
                new SeckillOrderEventService(mapper);

        assertEquals(SeckillOrderEvent.STATUS_CONSUMED,
                service.schedulePublishRetry(
                        "event-11", "late_callback", "late"));
    }

    @Test
    void rollbackRevertExhaustedEscalatesToManualReview() {
        SeckillOrderEventMapper mapper = mock(SeckillOrderEventMapper.class);
        SeckillOrderEvent existing = eventOf(
                "event-12",
                SeckillOrderEvent.STATUS_ROLLBACK_EXECUTING);
        existing.setRollbackRetryCount(
                SeckillRollbackRetryPolicy.maxAutomaticAttempts());
        existing.setLeaseToken(5L);

        when(mapper.selectById("event-12")).thenReturn(existing);
        when(mapper.update(isNull(), any())).thenReturn(1);

        SeckillOrderEventService service =
                new SeckillOrderEventService(mapper);

        int status = service.revertToRollbackPending(
                "event-12", 5L, "rollback_exception", "exception");

        assertEquals(SeckillOrderEvent.STATUS_MANUAL_REVIEW, status);
    }

    @Test
    void rollbackRevertRestoresPendingWithBackoff() {
        SeckillOrderEventMapper mapper = mock(SeckillOrderEventMapper.class);
        SeckillOrderEvent existing = eventOf(
                "event-13",
                SeckillOrderEvent.STATUS_ROLLBACK_EXECUTING);
        existing.setRollbackRetryCount(1);
        existing.setLeaseToken(5L);

        when(mapper.selectById("event-13")).thenReturn(existing);
        when(mapper.update(isNull(), any())).thenReturn(1);

        SeckillOrderEventService service =
                new SeckillOrderEventService(mapper);

        int status = service.revertToRollbackPending(
                "event-13", 5L, "rollback_lua_result_-1", "stock key absent");

        assertEquals(SeckillOrderEvent.STATUS_ROLLBACK_PENDING, status);
    }

    @Test
    void rollbackRevertRejectsStaleLeaseToken() {
        SeckillOrderEventMapper mapper = mock(SeckillOrderEventMapper.class);
        // 事件已被新任务接管：leaseToken 不匹配
        when(mapper.selectById("event-14"))
                .thenReturn(eventOf("event-14",
                        SeckillOrderEvent.STATUS_ROLLBACK_PENDING));

        SeckillOrderEventService service =
                new SeckillOrderEventService(mapper);

        assertEquals(SeckillOrderEvent.STATUS_ROLLBACK_PENDING,
                service.revertToRollbackPending(
                        "event-14", 999L, "stale", "stale token"));
    }

    @Test
    void manualReplayOnlyFromManualReviewOrDlq() {
        SeckillOrderEventMapper mapper = mock(SeckillOrderEventMapper.class);
        when(mapper.update(isNull(), any())).thenReturn(1);

        SeckillOrderEventService service =
                new SeckillOrderEventService(mapper);

        assertTrue(service.markReplayedForManualRetry("event-15"));
    }

    @Test
    void eventMissingFailsClosedForLateCallbacks() {
        SeckillOrderEventMapper mapper = mock(SeckillOrderEventMapper.class);
        when(mapper.update(isNull(), any())).thenReturn(0);
        when(mapper.selectById(eq("event-16"))).thenReturn(null);

        SeckillOrderEventService service =
                new SeckillOrderEventService(mapper);

        assertFalse(service.markConfirmed("event-16"));
    }
}
