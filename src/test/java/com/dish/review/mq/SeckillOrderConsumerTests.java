package com.dish.review.mq;

import com.dish.review.dto.SeckillOrderMessage;
import com.dish.review.service.SeckillOrderEventService;
import com.dish.review.service.SeckillVoucherLuaExecutor;
import com.dish.review.service.VoucherOrderHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证订单事务提交后的 Redis 预留即时清理与对账兜底边界。 */
class SeckillOrderConsumerTests {

    private VoucherOrderHandler voucherOrderHandler;
    private SeckillOrderEventService eventService;
    private SeckillVoucherLuaExecutor luaExecutor;
    private SeckillOrderConsumer consumer;

    @BeforeEach
    void setUp() {
        voucherOrderHandler = mock(VoucherOrderHandler.class);
        eventService = mock(SeckillOrderEventService.class);
        luaExecutor = mock(SeckillVoucherLuaExecutor.class);
        consumer = new SeckillOrderConsumer(
                voucherOrderHandler,
                eventService,
                luaExecutor
        );
    }

    private SeckillOrderMessage message() {
        return new SeckillOrderMessage(
                "event-1", 100L, 7L, 10L, 1700000000000L, 1);
    }

    @Test
    void committedOrderCompletesReservationAndMarksEvent() {
        when(luaExecutor.completeReservation(10L, 7L, "event-1", 100L))
                .thenReturn(1L);
        when(eventService.markReservationCompleted("event-1"))
                .thenReturn(true);

        consumer.consume(message());

        verify(voucherOrderHandler).createOrder(message());
        verify(luaExecutor).completeReservation(10L, 7L, "event-1", 100L);
        verify(eventService).markReservationCompleted("event-1");
    }

    @Test
    void idempotentReservationCompletionAlsoMarksEvent() {
        when(luaExecutor.completeReservation(10L, 7L, "event-1", 100L))
                .thenReturn(0L);
        when(eventService.markReservationCompleted("event-1"))
                .thenReturn(true);

        consumer.consume(message());

        verify(eventService).markReservationCompleted("event-1");
    }

    @Test
    void redisFailureDoesNotRedeliverCommittedOrder() {
        when(luaExecutor.completeReservation(10L, 7L, "event-1", 100L))
                .thenThrow(new IllegalStateException("redis unavailable"));

        assertDoesNotThrow(() -> consumer.consume(message()));

        verify(voucherOrderHandler).createOrder(message());
        verify(eventService, never()).markReservationCompleted("event-1");
    }

    @Test
    void reservationConflictLeavesEventForReconciliation() {
        when(luaExecutor.completeReservation(10L, 7L, "event-1", 100L))
                .thenReturn(-2L);

        assertDoesNotThrow(() -> consumer.consume(message()));

        verify(eventService, never()).markReservationCompleted("event-1");
    }
}
