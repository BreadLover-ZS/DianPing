package com.dish.review.mq;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.dish.review.dto.SeckillOrderMessage;
import com.dish.review.entity.SeckillFailureCase;
import com.dish.review.entity.SeckillOrderEvent;
import com.dish.review.entity.SeckillVoucher;
import com.dish.review.entity.VoucherOrder;
import com.dish.review.mapper.VoucherOrderMapper;
import com.dish.review.service.ISeckillVoucherService;
import com.dish.review.service.SeckillFailureCaseService;
import com.dish.review.service.SeckillOrderEventService;
import com.dish.review.service.SeckillVoucherLuaExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证双向对账任务的孤儿预留收敛、事件核对与指标输出（规格第 12 节）。
 */
class SeckillOrderReconciliationTaskTests {

    private ISeckillVoucherService seckillVoucherService;

    private SeckillOrderEventService eventService;

    private SeckillVoucherLuaExecutor luaExecutor;

    private VoucherOrderMapper voucherOrderMapper;

    private SeckillFailureCaseService failureCaseService;

    private SeckillOrderReconciliationTask task;

    @BeforeEach
    void setUp() {
        seckillVoucherService = mock(ISeckillVoucherService.class);
        eventService = mock(SeckillOrderEventService.class);
        luaExecutor = mock(SeckillVoucherLuaExecutor.class);
        voucherOrderMapper = mock(VoucherOrderMapper.class);
        failureCaseService = mock(SeckillFailureCaseService.class);

        task = new SeckillOrderReconciliationTask(
                seckillVoucherService,
                eventService,
                luaExecutor,
                voucherOrderMapper,
                failureCaseService
        );

        ReflectionTestUtils.setField(
                task, "reservationThresholdMinutes", 30);
        ReflectionTestUtils.setField(
                task, "reservationBatchSize", 50);
        ReflectionTestUtils.setField(
                task, "eventWindowMinutes", 60);
        ReflectionTestUtils.setField(
                task, "eventBatchSize", 100);
        ReflectionTestUtils.setField(
                task, "rollbackStuckMinutes", 10);
        ReflectionTestUtils.setField(
                task, "publishUnknownMaxHours", 24);
        ReflectionTestUtils.setField(
                task, "reservationVoucherLookbackDays", 7);
        ReflectionTestUtils.setField(
                task, "safetyScanPageSize", 100);

        // 默认无任何待处理数据，单项测试按需覆盖
        when(seckillVoucherService.list(any(Wrapper.class)))
                .thenReturn(Collections.emptyList());
        when(luaExecutor.findPendingReservationEventIds(
                anyLong(), anyLong(), anyInt()))
                .thenReturn(Collections.emptySet());
        when(luaExecutor.moveReservationToManual(anyLong(), anyString()))
                .thenReturn(1L);
        when(eventService.findConsumedRecent(anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(eventService.findRolledBackRecent(anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(eventService.findRollbackExecutingStuck(anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(eventService.findPublishUnknownOlderThan(anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());
    }

    private SeckillVoucher activeVoucher() {
        SeckillVoucher voucher = new SeckillVoucher();
        voucher.setVoucherId(10L);
        voucher.setStock(100);
        return voucher;
    }

    private void stubOnePendingReservation(String detail) {
        when(seckillVoucherService.list(any(Wrapper.class)))
                .thenReturn(Collections.singletonList(activeVoucher()));

        Set<String> pendingEventIds = new LinkedHashSet<>();
        pendingEventIds.add("event-1");

        when(luaExecutor.findPendingReservationEventIds(
                eq(10L), anyLong(), eq(50)))
                .thenReturn(pendingEventIds);
        when(luaExecutor.getReservationDetail(10L, "event-1"))
                .thenReturn(detail);
    }

    private SeckillOrderEvent event(int status) {
        SeckillOrderEvent event = new SeckillOrderEvent();
        event.setEventId("event-1");
        event.setOrderId(100L);
        event.setUserId(7L);
        event.setVoucherId(10L);
        event.setStatus(status);
        return event;
    }

    @Test
    void orphanReservationWithOrderConvergesToConsumed() {
        stubOnePendingReservation("100|7|1700000000000|1");
        when(voucherOrderMapper.selectById(100L))
                .thenReturn(new VoucherOrder());
        when(luaExecutor.completeReservation(10L, 7L, "event-1", 100L))
                .thenReturn(1L);

        assertDoesNotThrow(() -> task.reconcile());

        ArgumentCaptor<SeckillOrderMessage> captor =
                ArgumentCaptor.forClass(SeckillOrderMessage.class);

        verify(eventService).createPendingIfAbsent(captor.capture());
        verify(eventService).markConsumed("event-1");
        verify(luaExecutor).completeReservation(10L, 7L, "event-1", 100L);

        SeckillOrderMessage message = captor.getValue();

        assertEquals("event-1", message.getEventId());
        assertEquals(100L, message.getOrderId());
        assertEquals(7L, message.getUserId());
        assertEquals(10L, message.getVoucherId());
        assertEquals(1700000000000L, message.getCreatedAt());
        assertEquals(1, message.getVersion());
    }

    @Test
    void orphanReservationWithoutEventRebuildsPending() {
        stubOnePendingReservation("100|7|1700000000000|1");
        when(voucherOrderMapper.selectById(100L)).thenReturn(null);
        when(eventService.findByEventId("event-1")).thenReturn(null);
        when(eventService.createPendingIfAbsent(any()))
                .thenReturn(true);

        assertDoesNotThrow(() -> task.reconcile());

        ArgumentCaptor<SeckillOrderMessage> captor =
                ArgumentCaptor.forClass(SeckillOrderMessage.class);

        verify(eventService).createPendingIfAbsent(captor.capture());
        verify(eventService, never()).markConsumed(anyString());
        verify(eventService, never()).markRolledBack(anyString(), anyLong());

        assertEquals("event-1", captor.getValue().getEventId());
    }

    @Test
    void orphanReservationWithInFlightEventWaits() {
        stubOnePendingReservation("100|7|1700000000000|1");
        when(voucherOrderMapper.selectById(100L)).thenReturn(null);
        when(eventService.findByEventId("event-1"))
                .thenReturn(event(SeckillOrderEvent.STATUS_PENDING));

        assertDoesNotThrow(() -> task.reconcile());

        verify(eventService, never()).createPendingIfAbsent(any());
        verify(eventService, never()).markConsumed(anyString());
        verify(failureCaseService, never()).recordFailure(any());
    }

    @Test
    void incompleteReservationGoesToManualReview() {
        stubOnePendingReservation(null);

        assertDoesNotThrow(() -> task.reconcile());

        ArgumentCaptor<SeckillFailureCase> captor =
                ArgumentCaptor.forClass(SeckillFailureCase.class);

        verify(failureCaseService).recordFailure(captor.capture());

        SeckillFailureCase failureCase = captor.getValue();

        assertEquals("RECONCILE:event-1", failureCase.getIdempotencyKey());
        assertEquals(
                "incomplete_reservation_detail",
                failureCase.getErrorCode()
        );
        assertEquals(10L, failureCase.getVoucherId());

        // 失败单落库成功后必须原子移出待对账集合并转入人工集合，
        // 否则排头异常记录会永久阻塞其后的正常预留
        verify(luaExecutor).moveReservationToManual(10L, "event-1");
    }

    @Test
    void malformedReservationDetailGoesToManualReview() {
        stubOnePendingReservation("garbage-detail");

        assertDoesNotThrow(() -> task.reconcile());

        verify(failureCaseService).recordFailure(any(SeckillFailureCase.class));
        verify(luaExecutor).moveReservationToManual(10L, "event-1");
        verify(eventService, never()).createPendingIfAbsent(any());
    }

    @Test
    void incompleteReservationsDoNotBlockFollowingNormalReservations() {
        // 排头 event-1 信息不完整（预留详情缺失），
        // 其后 event-2 是可正常补建的孤儿预留
        when(seckillVoucherService.list(any(Wrapper.class)))
                .thenReturn(Collections.singletonList(activeVoucher()));

        Set<String> pendingEventIds = new LinkedHashSet<>();
        pendingEventIds.add("event-1");
        pendingEventIds.add("event-2");

        when(luaExecutor.findPendingReservationEventIds(
                eq(10L), anyLong(), eq(50)))
                .thenReturn(pendingEventIds);
        when(luaExecutor.getReservationDetail(10L, "event-1"))
                .thenReturn(null);
        when(luaExecutor.getReservationDetail(10L, "event-2"))
                .thenReturn("200|7|1700000000000|1");
        when(voucherOrderMapper.selectById(200L)).thenReturn(null);
        when(eventService.findByEventId("event-2")).thenReturn(null);
        when(eventService.createPendingIfAbsent(any())).thenReturn(true);

        assertDoesNotThrow(() -> task.reconcile());

        // 异常记录写失败单并原子移交人工集合（移出待对账 ZSet）
        verify(failureCaseService).recordFailure(any(SeckillFailureCase.class));
        verify(luaExecutor).moveReservationToManual(10L, "event-1");

        // 同一轮里排在异常记录之后的正常预留仍被补建，不被排头阻塞
        ArgumentCaptor<SeckillOrderMessage> captor =
                ArgumentCaptor.forClass(SeckillOrderMessage.class);
        verify(eventService).createPendingIfAbsent(captor.capture());
        assertEquals("event-2", captor.getValue().getEventId());
    }

    @Test
    void manualMoveFailureDoesNotBlockFollowingReservations() {
        // 移交人工集合时 Redis 异常：失败单已落库，记录仍在待对账 ZSet，
        // 下一轮幂等重写失败单并重试移交；本轮后续记录不受影响
        when(seckillVoucherService.list(any(Wrapper.class)))
                .thenReturn(Collections.singletonList(activeVoucher()));

        Set<String> pendingEventIds = new LinkedHashSet<>();
        pendingEventIds.add("event-1");
        pendingEventIds.add("event-2");

        when(luaExecutor.findPendingReservationEventIds(
                eq(10L), anyLong(), eq(50)))
                .thenReturn(pendingEventIds);
        when(luaExecutor.getReservationDetail(10L, "event-1"))
                .thenReturn("not|enough|parts");
        when(luaExecutor.moveReservationToManual(10L, "event-1"))
                .thenThrow(new RuntimeException("redis down"));
        when(luaExecutor.getReservationDetail(10L, "event-2"))
                .thenReturn("200|7|1700000000000|1");
        when(voucherOrderMapper.selectById(200L)).thenReturn(null);
        when(eventService.findByEventId("event-2")).thenReturn(null);
        when(eventService.createPendingIfAbsent(any())).thenReturn(true);

        assertDoesNotThrow(() -> task.reconcile());

        verify(failureCaseService).recordFailure(any(SeckillFailureCase.class));

        // 移交失败的记录不影响同轮其他记录的正常收敛
        ArgumentCaptor<SeckillOrderMessage> captor =
                ArgumentCaptor.forClass(SeckillOrderMessage.class);
        verify(eventService).createPendingIfAbsent(captor.capture());
        assertEquals("event-2", captor.getValue().getEventId());
    }

    @Test
    void consumedEventCompletesReservation() {
        when(eventService.findConsumedRecent(60, 100))
                .thenReturn(Collections.singletonList(
                        event(SeckillOrderEvent.STATUS_CONSUMED)));
        when(luaExecutor.completeReservation(10L, 7L, "event-1", 100L))
                .thenReturn(1L);

        assertDoesNotThrow(() -> task.reconcile());

        verify(luaExecutor).completeReservation(10L, 7L, "event-1", 100L);
        verify(failureCaseService, never()).recordFailure(any());
    }

    @Test
    void completeReservationConflictRecordsFailure() {
        when(eventService.findConsumedRecent(60, 100))
                .thenReturn(Collections.singletonList(
                        event(SeckillOrderEvent.STATUS_CONSUMED)));
        when(luaExecutor.completeReservation(10L, 7L, "event-1", 100L))
                .thenReturn(-2L);

        assertDoesNotThrow(() -> task.reconcile());

        ArgumentCaptor<SeckillFailureCase> captor =
                ArgumentCaptor.forClass(SeckillFailureCase.class);

        verify(failureCaseService).recordFailure(captor.capture());

        assertEquals(
                "complete_reservation_conflict",
                captor.getValue().getErrorCode()
        );
    }

    @Test
    void rolledBackEventWithRemainingDetailRecordsConflict() {
        when(eventService.findRolledBackRecent(60, 100))
                .thenReturn(Collections.singletonList(
                        event(SeckillOrderEvent.STATUS_ROLLED_BACK)));
        when(luaExecutor.reservationExists(10L, "event-1"))
                .thenReturn(true);

        assertDoesNotThrow(() -> task.reconcile());

        ArgumentCaptor<SeckillFailureCase> captor =
                ArgumentCaptor.forClass(SeckillFailureCase.class);

        verify(failureCaseService).recordFailure(captor.capture());

        assertEquals(
                "rolled_back_reservation_remains",
                captor.getValue().getErrorCode()
        );
    }

    @Test
    void stuckRollbackExecutingConvergesByReservationState() {
        when(eventService.findRollbackExecutingStuck(10, 100))
                .thenReturn(Collections.singletonList(
                        event(SeckillOrderEvent.STATUS_ROLLBACK_EXECUTING)));
        when(luaExecutor.reservationExists(10L, "event-1"))
                .thenReturn(false);

        assertDoesNotThrow(() -> task.reconcile());

        // 预留已移除：说明回滚 Lua 已执行，收敛为 ROLLED_BACK
        verify(eventService).convergeStuckRollbackExecuting(
                "event-1", true);
    }

    @Test
    void stuckRollbackExecutingWithReservationRevertsToPending() {
        when(eventService.findRollbackExecutingStuck(10, 100))
                .thenReturn(Collections.singletonList(
                        event(SeckillOrderEvent.STATUS_ROLLBACK_EXECUTING)));
        when(luaExecutor.reservationExists(10L, "event-1"))
                .thenReturn(true);

        assertDoesNotThrow(() -> task.reconcile());

        // 预留仍在：说明 Lua 未执行，恢复 ROLLBACK_PENDING 重试
        verify(eventService).convergeStuckRollbackExecuting(
                "event-1", false);
    }

    @Test
    void longUnknownPublishEscalatesToManualReview() {
        SeckillOrderEvent unknownEvent =
                event(SeckillOrderEvent.STATUS_PUBLISH_UNKNOWN);

        when(eventService.findPublishUnknownOlderThan(24, 100))
                .thenReturn(Collections.singletonList(unknownEvent));
        when(failureCaseService.recordManualReviewEscalation(
                eq(unknownEvent),
                eq(SeckillFailureCase.SOURCE_RECONCILE),
                eq("publish_unknown_expired"),
                anyString()))
                .thenReturn(true);

        assertDoesNotThrow(() -> task.reconcile());

        // 转人工与失败记录必须通过同一事务入口写入，保证人工处置入口存在
        verify(failureCaseService).recordManualReviewEscalation(
                eq(unknownEvent),
                eq(SeckillFailureCase.SOURCE_RECONCILE),
                eq("publish_unknown_expired"),
                anyString()
        );
        verify(eventService, never()).markManualReview(
                anyString(), anyString(), anyString());
    }

    @Test
    void metricsEmittedForAllStatuses() {
        assertDoesNotThrow(() -> task.reconcile());

        verify(eventService).countByStatus(SeckillOrderEvent.STATUS_PENDING);
        verify(eventService).countByStatus(
                SeckillOrderEvent.STATUS_PUBLISH_UNKNOWN);
        verify(eventService).countByStatus(
                SeckillOrderEvent.STATUS_ROLLBACK_PENDING);
        verify(eventService).countByStatus(SeckillOrderEvent.STATUS_DLQ);
        verify(eventService).countByStatus(
                SeckillOrderEvent.STATUS_MANUAL_REVIEW);
        verify(failureCaseService).countOpenCases();
    }

    @Test
    void voucherQueryFailureStopsReservationReconcileSafely() {
        when(seckillVoucherService.list(any(Wrapper.class)))
                .thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> task.reconcile());

        verify(luaExecutor, never())
                .findPendingReservationEventIds(anyLong(), anyLong(), anyInt());
    }

    // ==================== 安全兜底扫描（第 9 节验收修复） ====================

    private SeckillVoucher voucherOf(Long voucherId) {
        SeckillVoucher voucher = new SeckillVoucher();
        voucher.setVoucherId(voucherId);
        voucher.setStock(100);
        return voucher;
    }

    private SeckillVoucher historicVoucherEndedDaysAgo(
            Long voucherId, int daysAgo) {

        SeckillVoucher voucher = voucherOf(voucherId);
        voucher.setEndTime(java.time.LocalDateTime.now().minusDays(daysAgo));
        return voucher;
    }

    @Test
    void safetyScanDiscoversOrphanBeyondLookbackWindow() {
        ReflectionTestUtils.setField(task, "safetyScanPageSize", 2);

        // 第一页满页 [1, 2]，第二页 [3] 不满页终止；
        // 券 3 的活动已结束 30 天（远超 7 天回看窗口），只有兜底扫描能发现
        when(seckillVoucherService.list(any(Wrapper.class)))
                .thenReturn(java.util.Arrays.asList(
                        historicVoucherEndedDaysAgo(1L, 30),
                        historicVoucherEndedDaysAgo(2L, 30)))
                .thenReturn(Collections.singletonList(
                        historicVoucherEndedDaysAgo(3L, 30)));

        // 券 3 上有孤儿预留：事件表无记录，Redis 有预留
        Set<String> pendingEventIds = new LinkedHashSet<>();
        pendingEventIds.add("event-3");

        when(luaExecutor.findPendingReservationEventIds(
                eq(3L), anyLong(), anyInt()))
                .thenReturn(pendingEventIds);
        when(luaExecutor.getReservationDetail(3L, "event-3"))
                .thenReturn("300|7|1700000000000|1");
        when(voucherOrderMapper.selectById(300L)).thenReturn(null);
        when(eventService.findByEventId("event-3")).thenReturn(null);
        when(eventService.createPendingIfAbsent(any())).thenReturn(true);

        assertDoesNotThrow(() -> task.reconcileAllVouchersSafely());

        // 三个券都被扫描（分页无遗漏），历史券孤儿预留被补建事件
        verify(luaExecutor).findPendingReservationEventIds(
                eq(1L), anyLong(), anyInt());
        verify(luaExecutor).findPendingReservationEventIds(
                eq(2L), anyLong(), anyInt());
        verify(luaExecutor).findPendingReservationEventIds(
                eq(3L), anyLong(), anyInt());
        verify(eventService).createPendingIfAbsent(any());
    }

    @Test
    void safetyScanConvergesOrphanWithOrderToConsumed() {
        when(seckillVoucherService.list(any(Wrapper.class)))
                .thenReturn(Collections.singletonList(
                        historicVoucherEndedDaysAgo(10L, 30)));

        Set<String> pendingEventIds = new LinkedHashSet<>();
        pendingEventIds.add("event-1");

        when(luaExecutor.findPendingReservationEventIds(
                eq(10L), anyLong(), anyInt()))
                .thenReturn(pendingEventIds);
        when(luaExecutor.getReservationDetail(10L, "event-1"))
                .thenReturn("100|7|1700000000000|1");
        when(voucherOrderMapper.selectById(100L))
                .thenReturn(new VoucherOrder());
        when(luaExecutor.completeReservation(10L, 7L, "event-1", 100L))
                .thenReturn(1L);

        assertDoesNotThrow(() -> task.reconcileAllVouchersSafely());

        verify(eventService).createPendingIfAbsent(any());
        verify(eventService).markConsumed("event-1");
        verify(luaExecutor).completeReservation(10L, 7L, "event-1", 100L);
    }

    @Test
    void safetyScanHandlesExactPageSizeWithoutMissingLastPage() {
        ReflectionTestUtils.setField(task, "safetyScanPageSize", 2);

        // 第一页恰好满页 [1, 2]；下一轮查询返回空页才终止，
        // 不能在整页时假设“没有下一页”而遗漏后续券
        when(seckillVoucherService.list(any(Wrapper.class)))
                .thenReturn(java.util.Arrays.asList(
                        voucherOf(1L), voucherOf(2L)))
                .thenReturn(Collections.emptyList());

        assertDoesNotThrow(() -> task.reconcileAllVouchersSafely());

        verify(luaExecutor).findPendingReservationEventIds(
                eq(1L), anyLong(), anyInt());
        verify(luaExecutor).findPendingReservationEventIds(
                eq(2L), anyLong(), anyInt());
    }

    @Test
    void safetyScanQueryFailureStopsSafely() {
        when(seckillVoucherService.list(any(Wrapper.class)))
                .thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> task.reconcileAllVouchersSafely());

        verify(luaExecutor, never())
                .findPendingReservationEventIds(anyLong(), anyLong(), anyInt());
    }
}
