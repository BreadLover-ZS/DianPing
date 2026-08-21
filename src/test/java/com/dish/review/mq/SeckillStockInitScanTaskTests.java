package com.dish.review.mq;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.dish.review.entity.SeckillFailureCase;
import com.dish.review.entity.SeckillVoucher;
import com.dish.review.service.ISeckillVoucherService;
import com.dish.review.service.SeckillFailureCaseService;
import com.dish.review.service.SeckillVoucherLuaExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证缺失库存扫描任务的安全初始化、冲突转人工与库存一致性指标（规格第 14 节）。
 */
class SeckillStockInitScanTaskTests {

    private ISeckillVoucherService seckillVoucherService;

    private SeckillVoucherLuaExecutor luaExecutor;

    private SeckillFailureCaseService failureCaseService;

    private SeckillStockInitScanTask task;

    @BeforeEach
    void setUp() {
        seckillVoucherService = mock(ISeckillVoucherService.class);
        luaExecutor = mock(SeckillVoucherLuaExecutor.class);
        failureCaseService = mock(SeckillFailureCaseService.class);

        task = new SeckillStockInitScanTask(
                seckillVoucherService,
                luaExecutor,
                failureCaseService
        );
    }

    private SeckillVoucher voucher(Long voucherId, int stock) {
        SeckillVoucher voucher = new SeckillVoucher();

        voucher.setVoucherId(voucherId);
        voucher.setStock(stock);

        return voucher;
    }

    private void stubVouchers(SeckillVoucher voucher) {
        when(seckillVoucherService.list(any(Wrapper.class)))
                .thenReturn(Collections.singletonList(voucher));
    }

    @Test
    void missingStockIsSafelyInitialized() {
        stubVouchers(voucher(10L, 100));
        when(luaExecutor.hasStockKey(10L)).thenReturn(false);
        when(luaExecutor.initStock(10L, 100)).thenReturn(1L);

        assertDoesNotThrow(() -> task.scanMissingStock());

        verify(luaExecutor).initStock(10L, 100);
        verify(failureCaseService, never()).recordFailure(any());
    }

    @Test
    void missingStockWithConcurrentInitIsIdempotent() {
        stubVouchers(voucher(10L, 100));
        when(luaExecutor.hasStockKey(10L)).thenReturn(false);
        when(luaExecutor.initStock(10L, 100)).thenReturn(0L);

        assertDoesNotThrow(() -> task.scanMissingStock());

        verify(luaExecutor).initStock(10L, 100);
        verify(failureCaseService, never()).recordFailure(any());
    }

    @Test
    void missingStockWithHistoricalDataGoesToManualReview() {
        stubVouchers(voucher(10L, 100));
        when(luaExecutor.hasStockKey(10L)).thenReturn(false);
        when(luaExecutor.initStock(10L, 100)).thenReturn(-1L);

        assertDoesNotThrow(() -> task.scanMissingStock());

        ArgumentCaptor<SeckillFailureCase> captor =
                ArgumentCaptor.forClass(SeckillFailureCase.class);

        verify(failureCaseService).recordFailure(captor.capture());

        SeckillFailureCase failureCase = captor.getValue();

        assertEquals("STOCK_INIT:10", failureCase.getIdempotencyKey());
        assertEquals("stock_init_conflict", failureCase.getErrorCode());
        assertEquals(10L, failureCase.getVoucherId());
    }

    @Test
    void consistentStockDoesNotRecordFailure() {
        // mysql 100 - redis 97 - pending 3 = 0：账面一致
        stubVouchers(voucher(10L, 100));
        when(luaExecutor.hasStockKey(10L)).thenReturn(true);
        when(luaExecutor.getStock(10L)).thenReturn("97");
        when(luaExecutor.pendingReservationCount(10L)).thenReturn(3L);

        assertDoesNotThrow(() -> task.scanMissingStock());

        verify(failureCaseService, never()).recordFailure(any());
    }

    @Test
    void cleanupLagOnlyCountsAsMetric() {
        // CONSUMED 事件等待对账清理预留的滞后窗口：
        // mysql 99（订单已扣）- redis 96 - pending 4 = -1 < 0，仅计指标不告警
        stubVouchers(voucher(10L, 99));
        when(luaExecutor.hasStockKey(10L)).thenReturn(true);
        when(luaExecutor.getStock(10L)).thenReturn("96");
        when(luaExecutor.pendingReservationCount(10L)).thenReturn(4L);

        assertDoesNotThrow(() -> task.scanMissingStock());

        verify(failureCaseService, never()).recordFailure(any());
    }

    @Test
    void realDivergenceRecordsFailureForManualReview() {
        // mysql 100 - redis 90 - pending 3 = 7 > 0：无法用预留解释的真实差异
        stubVouchers(voucher(10L, 100));
        when(luaExecutor.hasStockKey(10L)).thenReturn(true);
        when(luaExecutor.getStock(10L)).thenReturn("90");
        when(luaExecutor.pendingReservationCount(10L)).thenReturn(3L);

        assertDoesNotThrow(() -> task.scanMissingStock());

        ArgumentCaptor<SeckillFailureCase> captor =
                ArgumentCaptor.forClass(SeckillFailureCase.class);

        verify(failureCaseService).recordFailure(captor.capture());

        SeckillFailureCase failureCase = captor.getValue();

        assertEquals(
                "STOCK_CONSISTENT:10",
                failureCase.getIdempotencyKey()
        );
        assertEquals(
                "stock_consistency_divergence",
                failureCase.getErrorCode()
        );
    }

    @Test
    void invalidRedisStockValueRecordsFailure() {
        stubVouchers(voucher(10L, 100));
        when(luaExecutor.hasStockKey(10L)).thenReturn(true);
        when(luaExecutor.getStock(10L)).thenReturn("not-a-number");

        assertDoesNotThrow(() -> task.scanMissingStock());

        ArgumentCaptor<SeckillFailureCase> captor =
                ArgumentCaptor.forClass(SeckillFailureCase.class);

        verify(failureCaseService).recordFailure(captor.capture());

        assertEquals(
                "stock_value_invalid",
                captor.getValue().getErrorCode()
        );
    }

    @Test
    void queryFailureIsSwallowed() {
        when(seckillVoucherService.list(any(Wrapper.class)))
                .thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> task.scanMissingStock());

        verify(luaExecutor, never()).initStock(any(), any());
    }

    @Test
    void scanFailureOfOneVoucherDoesNotBlockOthers() {
        SeckillVoucher bad = voucher(11L, 50);
        SeckillVoucher good = voucher(10L, 100);

        when(seckillVoucherService.list(any(Wrapper.class)))
                .thenReturn(java.util.Arrays.asList(bad, good));

        when(luaExecutor.hasStockKey(11L))
                .thenThrow(new RuntimeException("redis error"));
        when(luaExecutor.hasStockKey(10L)).thenReturn(false);
        when(luaExecutor.initStock(10L, 100)).thenReturn(1L);

        assertDoesNotThrow(() -> task.scanMissingStock());

        verify(luaExecutor).initStock(10L, 100);
    }
}
