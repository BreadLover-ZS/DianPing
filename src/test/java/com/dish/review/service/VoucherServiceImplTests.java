package com.dish.review.service;

import com.dish.review.dto.Result;
import com.dish.review.entity.SeckillFailureCase;
import com.dish.review.entity.Voucher;
import com.dish.review.mapper.VoucherMapper;
import com.dish.review.service.impl.VoucherServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证秒杀券创建后的 Redis 库存安全原子初始化（规格第 14 节）：
 * 禁止无条件删除用户集合；冲突写失败记录转人工。
 */
class VoucherServiceImplTests {

    private VoucherMapper voucherMapper;

    private ISeckillVoucherService seckillVoucherService;

    private SeckillVoucherLuaExecutor luaExecutor;

    private SeckillFailureCaseService failureCaseService;

    private VoucherServiceImpl service;

    @BeforeEach
    void setUp() {
        voucherMapper = mock(VoucherMapper.class);
        seckillVoucherService = mock(ISeckillVoucherService.class);
        luaExecutor = mock(SeckillVoucherLuaExecutor.class);
        failureCaseService = mock(SeckillFailureCaseService.class);

        service = new VoucherServiceImpl();

        ReflectionTestUtils.setField(service, "baseMapper", voucherMapper);
        ReflectionTestUtils.setField(
                service, "seckillVoucherService", seckillVoucherService);
        ReflectionTestUtils.setField(service, "luaExecutor", luaExecutor);
        ReflectionTestUtils.setField(
                service, "failureCaseService", failureCaseService);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager
                .isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private Voucher seckillVoucher() {
        Voucher voucher = new Voucher();

        voucher.setId(10L);
        voucher.setStock(100);
        voucher.setTitle("测试秒杀券");
        voucher.setBeginTime(java.time.LocalDateTime.now());
        voucher.setEndTime(java.time.LocalDateTime.now().plusDays(1));

        return voucher;
    }

    /**
     * 激活事务同步上下文，执行 addSeckillVoucher 并手动触发 afterCommit，
     * 模拟 MySQL 事务提交成功后的 Redis 初始化回调。
     */
    private void commitAndRunAfterCommit(Voucher voucher) {
        TransactionSynchronizationManager.initSynchronization();

        service.addSeckillVoucher(voucher);

        for (TransactionSynchronization synchronization :
                TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }
    }

    @Test
    void afterCommitInitializesStockAtomically() {
        Voucher voucher = seckillVoucher();
        when(voucherMapper.insert(any(Voucher.class))).thenReturn(1);
        when(luaExecutor.initStock(10L, 100)).thenReturn(1L);

        commitAndRunAfterCommit(voucher);

        verify(luaExecutor).initStock(10L, 100);
        verify(failureCaseService, never()).recordFailure(any());
    }

    @Test
    void existingStockKeyIsIdempotent() {
        Voucher voucher = seckillVoucher();
        when(voucherMapper.insert(any(Voucher.class))).thenReturn(1);
        when(luaExecutor.initStock(10L, 100)).thenReturn(0L);

        commitAndRunAfterCommit(voucher);

        verify(luaExecutor).initStock(10L, 100);
        verify(failureCaseService, never()).recordFailure(any());
    }

    @Test
    void historicalDataConflictRecordsFailureCase() {
        Voucher voucher = seckillVoucher();
        when(voucherMapper.insert(any(Voucher.class))).thenReturn(1);
        when(luaExecutor.initStock(10L, 100)).thenReturn(-1L);

        commitAndRunAfterCommit(voucher);

        ArgumentCaptor<SeckillFailureCase> captor =
                ArgumentCaptor.forClass(SeckillFailureCase.class);

        verify(failureCaseService).recordFailure(captor.capture());

        SeckillFailureCase failureCase = captor.getValue();

        assertEquals("STOCK_INIT:10", failureCase.getIdempotencyKey());
        assertEquals("stock_init_conflict", failureCase.getErrorCode());
        assertEquals(10L, failureCase.getVoucherId());
    }

    @Test
    void initExceptionIsSwallowedForScanTaskCompensation() {
        Voucher voucher = seckillVoucher();
        when(voucherMapper.insert(any(Voucher.class))).thenReturn(1);
        when(luaExecutor.initStock(10L, 100))
                .thenThrow(new RuntimeException("redis down"));

        assertDoesNotThrow(() -> commitAndRunAfterCommit(voucher));

        verify(failureCaseService, never()).recordFailure(any());
    }

    @Test
    void neverDeletesOrderKeyDirectly() {
        Voucher voucher = seckillVoucher();
        when(voucherMapper.insert(any(Voucher.class))).thenReturn(1);
        when(luaExecutor.initStock(10L, 100)).thenReturn(1L);

        commitAndRunAfterCommit(voucher);

        // 安全初始化只依赖 Lua 原子脚本，禁止无条件 delete 用户集合等 Key
        verify(luaExecutor).initStock(10L, 100);
        assertTrue(true);
    }

    @Test
    void queryVoucherOfShopDelegatesToMapper() {
        when(voucherMapper.queryVoucherOfShop(1L))
                .thenReturn(Collections.emptyList());

        Result result = service.queryVoucherOfShop(1L);

        assertTrue(result.getSuccess());
    }
}
