package com.dish.review.mq;

import com.dish.review.entity.SeckillOrderEvent;
import com.dish.review.entity.VoucherOrder;
import com.dish.review.mapper.VoucherOrderMapper;
import com.dish.review.service.SeckillFailureCaseService;
import com.dish.review.service.SeckillOrderEventService;
import com.dish.review.service.SeckillVoucherLuaExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证持久化回滚任务的订单核对、CAS 抢占和 Lua 结果收敛（规格第 11 节）。 */
class SeckillReservationRollbackTaskTests {

    private SeckillOrderEventService eventService;
    private SeckillVoucherLuaExecutor luaExecutor;
    private VoucherOrderMapper voucherOrderMapper;
    private SeckillFailureCaseService failureCaseService;
    private SeckillReservationRollbackTask task;

    @BeforeEach
    void setUp() {
        eventService = mock(SeckillOrderEventService.class);
        luaExecutor = mock(SeckillVoucherLuaExecutor.class);
        voucherOrderMapper = mock(VoucherOrderMapper.class);
        failureCaseService = mock(SeckillFailureCaseService.class);
        task = new SeckillReservationRollbackTask(
                eventService, luaExecutor,
                voucherOrderMapper, failureCaseService);
    }

    private SeckillOrderEvent rollbackPendingEvent() {
        SeckillOrderEvent event = new SeckillOrderEvent();
        event.setEventId("event-1");
        event.setOrderId(100L);
        event.setUserId(7L);
        event.setVoucherId(10L);
        event.setStatus(SeckillOrderEvent.STATUS_ROLLBACK_PENDING);
        return event;
    }

    @Test
    void existingOrderBlocksRollbackAndConvergesToConsumed() {
        SeckillOrderEvent event = rollbackPendingEvent();
        when(eventService.findDueForRollback(anyInt()))
                .thenReturn(java.util.Collections.singletonList(event));
        when(voucherOrderMapper.selectById(100L))
                .thenReturn(new VoucherOrder());
        when(eventService.markConsumed("event-1")).thenReturn(true);

        assertDoesNotThrow(() -> task.rollbackDueEvents());

        verify(eventService).markConsumed("event-1");
        verify(eventService, never()).claimForRollback(anyString(), anyString());
        verify(luaExecutor, never())
                .rollbackByEvent(anyLong(), anyLong(), anyString(), anyLong());
    }

    @Test
    void luaRestoredResultMarksRolledBack() {
        SeckillOrderEvent event = rollbackPendingEvent();
        when(eventService.findDueForRollback(anyInt()))
                .thenReturn(java.util.Collections.singletonList(event));
        when(voucherOrderMapper.selectById(100L)).thenReturn(null);
        when(eventService.claimForRollback(
                eq("event-1"), anyString())).thenReturn(9L);
        when(luaExecutor.rollbackByEvent(10L, 7L, "event-1", 100L))
                .thenReturn(1L);
        when(eventService.markRolledBack("event-1", 9L)).thenReturn(true);

        assertDoesNotThrow(() -> task.rollbackDueEvents());

        verify(eventService).markRolledBack("event-1", 9L);
        verify(failureCaseService, never())
                .recordRollbackRevert(any(), anyLong(), anyString(), anyString());
    }

    @Test
    void luaIdempotentZeroAlsoMarksRolledBack() {
        SeckillOrderEvent event = rollbackPendingEvent();
        when(eventService.findDueForRollback(anyInt()))
                .thenReturn(java.util.Collections.singletonList(event));
        when(voucherOrderMapper.selectById(100L)).thenReturn(null);
        when(eventService.claimForRollback(
                eq("event-1"), anyString())).thenReturn(9L);
        when(luaExecutor.rollbackByEvent(10L, 7L, "event-1", 100L))
                .thenReturn(0L);
        when(eventService.markRolledBack("event-1", 9L)).thenReturn(true);

        assertDoesNotThrow(() -> task.rollbackDueEvents());

        verify(eventService).markRolledBack("event-1", 9L);
    }

    @Test
    void luaErrorResultRevertsWithBackoff() {
        SeckillOrderEvent event = rollbackPendingEvent();
        when(eventService.findDueForRollback(anyInt()))
                .thenReturn(java.util.Collections.singletonList(event));
        when(voucherOrderMapper.selectById(100L)).thenReturn(null);
        when(eventService.claimForRollback(
                eq("event-1"), anyString())).thenReturn(9L);
        when(luaExecutor.rollbackByEvent(10L, 7L, "event-1", 100L))
                .thenReturn(-1L);
        when(failureCaseService.recordRollbackRevert(
                eq(event), eq(9L), anyString(), anyString()))
                .thenReturn(SeckillOrderEvent.STATUS_ROLLBACK_PENDING);

        assertDoesNotThrow(() -> task.rollbackDueEvents());

        verify(failureCaseService).recordRollbackRevert(
                eq(event), eq(9L), anyString(), anyString());
        verify(eventService, never()).markRolledBack(anyString(), anyLong());
    }

    @Test
    void luaExceptionRevertsForRetry() {
        SeckillOrderEvent event = rollbackPendingEvent();
        when(eventService.findDueForRollback(anyInt()))
                .thenReturn(java.util.Collections.singletonList(event));
        when(voucherOrderMapper.selectById(100L)).thenReturn(null);
        when(eventService.claimForRollback(
                eq("event-1"), anyString())).thenReturn(9L);
        when(luaExecutor.rollbackByEvent(10L, 7L, "event-1", 100L))
                .thenThrow(new RuntimeException("Redis 不可用"));
        when(failureCaseService.recordRollbackRevert(
                any(), anyLong(), anyString(), anyString()))
                .thenReturn(SeckillOrderEvent.STATUS_ROLLBACK_PENDING);

        assertDoesNotThrow(() -> task.rollbackDueEvents());

        verify(failureCaseService).recordRollbackRevert(
                any(), anyLong(), anyString(), anyString());
    }

    @Test
    void claimFailureSkipsEvent() {
        SeckillOrderEvent event = rollbackPendingEvent();
        when(eventService.findDueForRollback(anyInt()))
                .thenReturn(java.util.Collections.singletonList(event));
        when(voucherOrderMapper.selectById(100L)).thenReturn(null);
        when(eventService.claimForRollback(
                eq("event-1"), anyString())).thenReturn(null);

        assertDoesNotThrow(() -> task.rollbackDueEvents());

        verify(luaExecutor, never())
                .rollbackByEvent(anyLong(), anyLong(), anyString(), anyLong());
        verify(eventService, never()).markRolledBack(anyString(), anyLong());
    }

    @Test
    void scanFailureDoesNotBreakScheduling() {
        when(eventService.findDueForRollback(anyInt()))
                .thenThrow(new RuntimeException("数据库不可用"));

        assertDoesNotThrow(() -> task.rollbackDueEvents());

        verify(voucherOrderMapper, never()).selectById(anyLong());
    }
}
