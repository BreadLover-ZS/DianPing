package com.dish.review.service;

import com.dish.review.entity.SeckillFailureAudit;
import com.dish.review.entity.SeckillFailureCase;
import com.dish.review.entity.VoucherOrder;
import com.dish.review.mapper.SeckillFailureAuditMapper;
import com.dish.review.mapper.VoucherOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证失败处置管理 Service 的重放、回滚、关闭与审计约束（规格第 13 节）。
 */
class SeckillOrderFailureAdminServiceTests {

    private SeckillFailureCaseService failureCaseService;

    private SeckillOrderEventService eventService;

    private SeckillFailureAuditMapper auditMapper;

    private VoucherOrderMapper voucherOrderMapper;

    private SeckillOrderFailureAdminService adminService;

    @BeforeEach
    void setUp() {
        failureCaseService = mock(SeckillFailureCaseService.class);
        eventService = mock(SeckillOrderEventService.class);
        auditMapper = mock(SeckillFailureAuditMapper.class);
        voucherOrderMapper = mock(VoucherOrderMapper.class);

        adminService = new SeckillOrderFailureAdminService(
                failureCaseService,
                eventService,
                auditMapper,
                voucherOrderMapper
        );

        // @Value 注入：与 application.yaml 默认值一致
        ReflectionTestUtils.setField(adminService, "maxReplayCount", 3);
    }

    private SeckillFailureCase openCase() {
        SeckillFailureCase failureCase = new SeckillFailureCase();

        failureCase.setFailureId(1L);
        failureCase.setIdempotencyKey("CONSUMER_DLQ:event-1");
        failureCase.setEventId("event-1");
        failureCase.setOrderId(100L);
        failureCase.setUserId(7L);
        failureCase.setVoucherId(10L);
        failureCase.setSource(SeckillFailureCase.SOURCE_CONSUMER_DLQ);
        failureCase.setStatus(SeckillFailureCase.STATUS_OPEN);
        failureCase.setReplayCount(0);

        return failureCase;
    }

    @Test
    void replayResetsEventToPendingAndAudits() {
        when(failureCaseService.findByFailureId(1L)).thenReturn(openCase());
        when(voucherOrderMapper.selectById(100L)).thenReturn(null);
        when(eventService.markReplayedForManualRetry("event-1"))
                .thenReturn(true);

        String outcome = adminService.replayFailure(
                1L, "ops-admin", "消息可重放");

        assertEquals("REPLAYED", outcome);
        verify(eventService).markReplayedForManualRetry("event-1");
        verify(failureCaseService).markReplayed(1L);

        ArgumentCaptor<SeckillFailureAudit> captor =
                ArgumentCaptor.forClass(SeckillFailureAudit.class);

        verify(auditMapper).insert(captor.capture());

        SeckillFailureAudit audit = captor.getValue();

        assertEquals(1L, audit.getFailureId());
        assertEquals("event-1", audit.getEventId());
        assertEquals("REPLAY", audit.getAction());
        assertEquals("ops-admin", audit.getOperator());
        assertEquals("消息可重放", audit.getReason());
    }

    @Test
    void replayWithExistingOrderClosesAsIdempotentSuccess() {
        when(failureCaseService.findByFailureId(1L)).thenReturn(openCase());
        when(voucherOrderMapper.selectById(100L))
                .thenReturn(new VoucherOrder());

        String outcome = adminService.replayFailure(
                1L, "ops-admin", "核对后发现订单已创建");

        assertEquals("ALREADY_CONSUMED", outcome);
        verify(failureCaseService).closeAsIdempotentSuccess(1L);
        verify(eventService, never())
                .markReplayedForManualRetry(anyString());
        verify(failureCaseService, never()).markReplayed(anyLong());
    }

    @Test
    void replayRejectedWhenLimitExceeded() {
        SeckillFailureCase failureCase = openCase();
        failureCase.setReplayCount(3);

        when(failureCaseService.findByFailureId(1L)).thenReturn(failureCase);
        when(voucherOrderMapper.selectById(100L)).thenReturn(null);

        String outcome = adminService.replayFailure(
                1L, "ops-admin", "再次尝试");

        assertEquals("REPLAY_LIMIT_EXCEEDED", outcome);
        verify(eventService, never())
                .markReplayedForManualRetry(anyString());
        verify(auditMapper, never()).insert(any(SeckillFailureAudit.class));
    }

    @Test
    void replayRejectedWhenEventNotReplayable() {
        when(failureCaseService.findByFailureId(1L)).thenReturn(openCase());
        when(voucherOrderMapper.selectById(100L)).thenReturn(null);
        when(eventService.markReplayedForManualRetry("event-1"))
                .thenReturn(false);

        String outcome = adminService.replayFailure(
                1L, "ops-admin", "事件状态不允许");

        assertEquals("EVENT_NOT_REPLAYABLE", outcome);
        verify(failureCaseService, never()).markReplayed(anyLong());
        verify(auditMapper, never()).insert(any(SeckillFailureAudit.class));
    }

    @Test
    void replayMissingCaseReturnsNotFound() {
        when(failureCaseService.findByFailureId(1L)).thenReturn(null);

        assertEquals(
                "NOT_FOUND",
                adminService.replayFailure(1L, "ops-admin", "任意")
        );
    }

    @Test
    void rollbackMarksEventAndFailureCase() {
        when(failureCaseService.findByFailureId(1L)).thenReturn(openCase());
        when(voucherOrderMapper.selectById(100L)).thenReturn(null);
        when(eventService.markManualRollback("event-1")).thenReturn(true);

        String outcome = adminService.rollbackFailure(
                1L, "ops-admin", "业务上应回滚");

        assertEquals("ROLLED_BACK", outcome);
        verify(eventService).markManualRollback("event-1");
        verify(failureCaseService).markRolledBack(1L);

        ArgumentCaptor<SeckillFailureAudit> captor =
                ArgumentCaptor.forClass(SeckillFailureAudit.class);

        verify(auditMapper).insert(captor.capture());

        assertEquals("ROLLBACK", captor.getValue().getAction());
        assertEquals("ops-admin", captor.getValue().getOperator());
    }

    @Test
    void rollbackWithExistingOrderRejected() {
        when(failureCaseService.findByFailureId(1L)).thenReturn(openCase());
        when(voucherOrderMapper.selectById(100L))
                .thenReturn(new VoucherOrder());

        String outcome = adminService.rollbackFailure(
                1L, "ops-admin", "尝试回滚");

        assertEquals("ALREADY_CONSUMED", outcome);
        verify(eventService, never()).markManualRollback(anyString());
        verify(failureCaseService, never()).markRolledBack(anyLong());
    }

    @Test
    void rollbackRejectedWhenEventNotManualReview() {
        when(failureCaseService.findByFailureId(1L)).thenReturn(openCase());
        when(voucherOrderMapper.selectById(100L)).thenReturn(null);
        when(eventService.markManualRollback("event-1")).thenReturn(false);

        assertEquals(
                "EVENT_NOT_ROLLBACKABLE",
                adminService.rollbackFailure(1L, "ops-admin", "任意")
        );
    }

    @Test
    void closeMarksFailureCaseAndAudits() {
        when(failureCaseService.findByFailureId(1L)).thenReturn(openCase());
        when(failureCaseService.markClosed(1L)).thenReturn(true);

        String outcome = adminService.closeFailure(
                1L, "ops-admin", "业务接受该失败");

        assertEquals("CLOSED", outcome);
        verify(failureCaseService).markClosed(1L);

        ArgumentCaptor<SeckillFailureAudit> captor =
                ArgumentCaptor.forClass(SeckillFailureAudit.class);

        verify(auditMapper).insert(captor.capture());

        assertEquals("CLOSE", captor.getValue().getAction());
    }

    @Test
    void closeMissingCaseReturnsNotFound() {
        when(failureCaseService.findByFailureId(1L)).thenReturn(null);

        assertEquals(
                "NOT_FOUND",
                adminService.closeFailure(1L, "ops-admin", "任意")
        );
    }

    @Test
    void listOpenCasesDelegatesToFailureCaseService() {
        adminService.listOpenCases(20);

        verify(failureCaseService).findOpenCases(20);
    }

    @Test
    void auditOperatorDefaultsToUnknown() {
        when(failureCaseService.findByFailureId(1L)).thenReturn(openCase());
        when(voucherOrderMapper.selectById(100L)).thenReturn(null);
        when(eventService.markReplayedForManualRetry("event-1"))
                .thenReturn(true);

        adminService.replayFailure(1L, "  ", "原因");

        ArgumentCaptor<SeckillFailureAudit> captor =
                ArgumentCaptor.forClass(SeckillFailureAudit.class);

        verify(auditMapper).insert(captor.capture());

        assertEquals("unknown", captor.getValue().getOperator());
    }
}
