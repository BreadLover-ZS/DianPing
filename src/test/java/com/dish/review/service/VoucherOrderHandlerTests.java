package com.dish.review.service;

import com.dish.review.dto.SeckillOrderMessage;
import com.dish.review.entity.SeckillOrderEvent;
import com.dish.review.entity.SeckillVoucher;
import com.dish.review.exception.SeckillConsistencyException;
import com.dish.review.exception.SeckillRetryableException;
import com.dish.review.mapper.SeckillVoucherMapper;
import com.dish.review.mapper.VoucherOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证消费事务的状态分支、回滚并发保护和异常分类（规格第 10 节）。 */
class VoucherOrderHandlerTests {

    private VoucherOrderMapper voucherOrderMapper;
    private SeckillVoucherMapper seckillVoucherMapper;
    private SeckillOrderEventService eventService;
    private VoucherOrderHandler handler;

    private SeckillOrderMessage message;

    @BeforeEach
    void setUp() {
        voucherOrderMapper = mock(VoucherOrderMapper.class);
        seckillVoucherMapper = mock(SeckillVoucherMapper.class);
        eventService = mock(SeckillOrderEventService.class);
        handler = new VoucherOrderHandler(
                voucherOrderMapper,
                seckillVoucherMapper,
                eventService
        );

        message = new SeckillOrderMessage(
                "event-1", 100L, 7L, 10L, 1234567890L, 1);
    }

    private SeckillOrderEvent eventOf(int status) {
        SeckillOrderEvent event = new SeckillOrderEvent();
        event.setEventId("event-1");
        event.setStatus(status);
        return event;
    }

    @Test
    void consumedEventIsIdempotentSuccess() {
        when(eventService.lockEvent("event-1"))
                .thenReturn(eventOf(SeckillOrderEvent.STATUS_CONSUMED));

        assertDoesNotThrow(() -> handler.createOrder(message));

        // 幂等返回：不再查订单、不扣库存
        verify(voucherOrderMapper, never()).insert(any());
        verify(seckillVoucherMapper, never()).update(any(), any());
    }

    @Test
    void rolledBackEventRejectsLateMessageAsConsistencyConflict() {
        when(eventService.lockEvent("event-1"))
                .thenReturn(eventOf(SeckillOrderEvent.STATUS_ROLLED_BACK));

        assertThrows(SeckillConsistencyException.class,
                () -> handler.createOrder(message));
    }

    @Test
    void rollbackExecutingEventWaitsWithRetryableException() {
        when(eventService.lockEvent("event-1"))
                .thenReturn(eventOf(
                        SeckillOrderEvent.STATUS_ROLLBACK_EXECUTING));

        assertThrows(SeckillRetryableException.class,
                () -> handler.createOrder(message));
    }

    @Test
    void rollbackPendingEventCancelsRollbackBeforeCreatingOrder() {
        when(eventService.lockEvent("event-1"))
                .thenReturn(eventOf(
                        SeckillOrderEvent.STATUS_ROLLBACK_PENDING));
        when(eventService.cancelRollback("event-1")).thenReturn(true);
        when(voucherOrderMapper.selectCount(any())).thenReturn(0);
        when(seckillVoucherMapper.update(any(), any())).thenReturn(1);
        when(voucherOrderMapper.insert(any())).thenReturn(1);
        when(eventService.markConsumed("event-1")).thenReturn(true);

        assertDoesNotThrow(() -> handler.createOrder(message));

        verify(eventService).cancelRollback("event-1");
        verify(eventService).markConsumed("event-1");
    }

    @Test
    void cancelRollbackFailureBlocksOrderCreation() {
        when(eventService.lockEvent("event-1"))
                .thenReturn(eventOf(
                        SeckillOrderEvent.STATUS_ROLLBACK_PENDING));
        when(eventService.cancelRollback("event-1")).thenReturn(false);

        assertThrows(SeckillRetryableException.class,
                () -> handler.createOrder(message));

        verify(seckillVoucherMapper, never()).update(any(), any());
    }

    @Test
    void manualReviewEventBlocksAutomaticOrderCreation() {
        when(eventService.lockEvent("event-1"))
                .thenReturn(eventOf(
                        SeckillOrderEvent.STATUS_MANUAL_REVIEW));

        assertThrows(SeckillConsistencyException.class,
                () -> handler.createOrder(message));
    }

    @Test
    void missingEventFailsClosed() {
        when(eventService.lockEvent("event-1")).thenReturn(null);

        assertThrows(SeckillConsistencyException.class,
                () -> handler.createOrder(message));
    }

    @Test
    void existingOrderMarksConsumedWithoutStockDeduction() {
        when(eventService.lockEvent("event-1"))
                .thenReturn(eventOf(SeckillOrderEvent.STATUS_CONFIRMED));
        when(voucherOrderMapper.selectCount(any())).thenReturn(1);
        when(eventService.markConsumed("event-1")).thenReturn(true);

        assertDoesNotThrow(() -> handler.createOrder(message));

        verify(seckillVoucherMapper, never()).update(any(), any());
        verify(eventService).markConsumed("event-1");
    }

    @Test
    void insufficientMySqlStockIsConsistencyConflict() {
        when(eventService.lockEvent("event-1"))
                .thenReturn(eventOf(SeckillOrderEvent.STATUS_CONFIRMED));
        when(voucherOrderMapper.selectCount(any())).thenReturn(0);
        when(seckillVoucherMapper.update(
                any(), any())).thenReturn(0);

        assertThrows(SeckillConsistencyException.class,
                () -> handler.createOrder(message));
    }

    @Test
    void orderInsertFailureIsRetryable() {
        when(eventService.lockEvent("event-1"))
                .thenReturn(eventOf(SeckillOrderEvent.STATUS_CONFIRMED));
        when(voucherOrderMapper.selectCount(any())).thenReturn(0);
        when(seckillVoucherMapper.update(any(), any())).thenReturn(1);
        when(voucherOrderMapper.insert(any())).thenReturn(0);

        assertThrows(SeckillRetryableException.class,
                () -> handler.createOrder(message));
    }

    @Test
    void markConsumedFailureRollsBackWholeTransaction() {
        when(eventService.lockEvent("event-1"))
                .thenReturn(eventOf(SeckillOrderEvent.STATUS_CONFIRMED));
        when(voucherOrderMapper.selectCount(any())).thenReturn(0);
        when(seckillVoucherMapper.update(any(), any())).thenReturn(1);
        when(voucherOrderMapper.insert(any())).thenReturn(1);
        when(eventService.markConsumed(eq("event-1"))).thenReturn(false);

        assertThrows(SeckillConsistencyException.class,
                () -> handler.createOrder(message));
    }

    @Test
    void orderAlreadyExistsChecksUserAndVoucher() {
        when(voucherOrderMapper.selectCount(any())).thenReturn(1);

        assert handler.orderAlreadyExists(message);
    }
}
