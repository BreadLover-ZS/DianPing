package com.dish.review.mq;

import com.dish.review.entity.SeckillFailureCase;
import com.dish.review.entity.SeckillOrderEvent;
import com.dish.review.entity.SeckillPublishAttempt;
import com.dish.review.service.SeckillFailureCaseService;
import com.dish.review.service.SeckillOrderEventService;
import com.dish.review.service.SeckillOrderFailureDecisionService;
import com.dish.review.service.SeckillPublishAttemptService;
import com.dish.review.service.SeckillPublishRetryPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 Outbox 发布任务的耗尽升级、终局窗口推迟和同步异常处理
 * （规格 9.1 节 + 验收修复第 2 条）。
 *
 * <p>核心回归点：
 * 最后一次发送后必须推迟终局等待窗口而不是立刻停止自动流程；
 * 耗尽且窗口内未收敛的事件在扫描阶段统一转人工并写失败记录。</p>
 */
class SeckillOrderPublishRetryTaskTests {

    private SeckillOrderEventService eventService;

    private SeckillPublishAttemptService attemptService;

    private SeckillOrderFailureDecisionService decisionService;

    private SeckillOrderPublisher orderPublisher;

    private SeckillFailureCaseService failureCaseService;

    private SeckillOrderPublishRetryTask task;

    @BeforeEach
    void setUp() {
        eventService = mock(SeckillOrderEventService.class);
        attemptService = mock(SeckillPublishAttemptService.class);
        decisionService = mock(SeckillOrderFailureDecisionService.class);
        orderPublisher = mock(SeckillOrderPublisher.class);
        failureCaseService = mock(SeckillFailureCaseService.class);

        task = new SeckillOrderPublishRetryTask(
                eventService,
                attemptService,
                decisionService,
                orderPublisher,
                failureCaseService
        );

        ReflectionTestUtils.setField(task, "batchSize", 20);
        ReflectionTestUtils.setField(task, "leaseSeconds", 60);
    }

    private SeckillOrderEvent event(int retryCount) {
        SeckillOrderEvent event = new SeckillOrderEvent();
        event.setEventId("event-1");
        event.setOrderId(100L);
        event.setUserId(7L);
        event.setVoucherId(10L);
        event.setCreatedAt(1700000000000L);
        event.setMessageVersion(1);
        event.setStatus(SeckillOrderEvent.STATUS_PENDING);
        event.setRetryCount(retryCount);
        return event;
    }

    private SeckillPublishAttempt attempt(int attemptNo) {
        SeckillPublishAttempt attempt = new SeckillPublishAttempt();
        attempt.setAttemptId("attempt-1");
        attempt.setEventId("event-1");
        attempt.setAttemptNo(attemptNo);
        return attempt;
    }

    @Test
    void exhaustedEventEscalatesToManualReviewWithoutSending() {
        when(eventService.findDueForPublish(20))
                .thenReturn(Collections.singletonList(
                        event(SeckillPublishRetryPolicy
                                .maxAutomaticAttempts())));
        when(eventService.claimLease(
                eq("event-1"), anyString(), eq(60)))
                .thenReturn(11L);
        when(failureCaseService.recordManualReviewEscalation(
                any(SeckillOrderEvent.class),
                eq(SeckillFailureCase.SOURCE_PUBLISH),
                eq("publish_retry_exhausted"),
                anyString()))
                .thenReturn(true);

        assertDoesNotThrow(() -> task.publishDueEvents());

        // 耗尽事件：统一入口转人工并写 SOURCE_PUBLISH 失败记录
        verify(failureCaseService).recordManualReviewEscalation(
                any(SeckillOrderEvent.class),
                eq(SeckillFailureCase.SOURCE_PUBLISH),
                eq("publish_retry_exhausted"),
                anyString()
        );
        verify(attemptService, never()).createNextAttempt(anyString());
        verify(orderPublisher, never()).send(any(), any());
        verify(eventService).releaseLease("event-1", 11L);
    }

    @Test
    void lastAttemptDefersFinalDecisionWindow() {
        when(eventService.findDueForPublish(20))
                .thenReturn(Collections.singletonList(
                        event(SeckillPublishRetryPolicy
                                .maxAutomaticAttempts() - 1)));
        when(eventService.claimLease(
                eq("event-1"), anyString(), eq(60)))
                .thenReturn(11L);
        when(attemptService.createNextAttempt("event-1"))
                .thenReturn(attempt(
                        SeckillPublishRetryPolicy.maxAutomaticAttempts()));
        when(eventService.deferNextRetryTime(
                eq("event-1"), anyLong()))
                .thenReturn(true);

        assertDoesNotThrow(() -> task.publishDueEvents());

        // 第 8 次（最后一次）发送后推迟终局窗口，
        // 等待 Confirm/确认超时收敛，禁止 send() 返回即停止自动流程
        verify(orderPublisher).send(any(), any());
        verify(eventService).deferNextRetryTime(
                "event-1",
                SeckillPublishRetryPolicy.FINAL_DECISION_WAIT_SECONDS
        );
    }

    @Test
    void firstAttemptDefersOneSecondBackoff() {
        when(eventService.findDueForPublish(20))
                .thenReturn(Collections.singletonList(event(0)));
        when(eventService.claimLease(
                eq("event-1"), anyString(), eq(60)))
                .thenReturn(11L);
        when(attemptService.createNextAttempt("event-1"))
                .thenReturn(attempt(1));
        when(eventService.deferNextRetryTime(
                eq("event-1"), anyLong()))
                .thenReturn(true);

        assertDoesNotThrow(() -> task.publishDueEvents());

        verify(orderPublisher).send(any(), any());
        verify(eventService).deferNextRetryTime("event-1", 1L);
    }

    @Test
    void syncSendExceptionRecordsUnknownAndEvaluates() {
        when(eventService.findDueForPublish(20))
                .thenReturn(Collections.singletonList(event(0)));
        when(eventService.claimLease(
                eq("event-1"), anyString(), eq(60)))
                .thenReturn(11L);
        when(attemptService.createNextAttempt("event-1"))
                .thenReturn(attempt(1));

        doThrow(new RuntimeException("连接断开"))
                .when(orderPublisher).send(any(), any());
        when(attemptService.recordUnknown(
                eq("attempt-1"), eq("send_exception"), anyString()))
                .thenReturn(true);
        when(decisionService.evaluateForRetry(
                eq("event-1"), eq("send_exception"), anyString()))
                .thenReturn(
                        SeckillOrderFailureDecisionService.Decision
                                .RETRY_PUBLISH);

        assertDoesNotThrow(() -> task.publishDueEvents());

        // 同步异常：只记 UNKNOWN 证据并交给失败决策服务，禁止本地退避
        verify(attemptService).recordUnknown(
                eq("attempt-1"), eq("send_exception"), anyString());
        verify(decisionService).evaluateForRetry(
                eq("event-1"), eq("send_exception"), anyString());
        verify(eventService, never())
                .deferNextRetryTime(anyString(), anyLong());
        verify(eventService).releaseLease("event-1", 11L);
    }

    @Test
    void leaseClaimFailureSkipsEvent() {
        when(eventService.findDueForPublish(20))
                .thenReturn(Collections.singletonList(event(0)));
        when(eventService.claimLease(
                eq("event-1"), anyString(), eq(60)))
                .thenReturn(null);

        assertDoesNotThrow(() -> task.publishDueEvents());

        verify(attemptService, never()).createNextAttempt(anyString());
        verify(orderPublisher, never()).send(any(), any());
    }

    @Test
    void scanFailureDoesNotBreakScheduling() {
        when(eventService.findDueForPublish(20))
                .thenThrow(new RuntimeException("数据库不可用"));

        assertDoesNotThrow(() -> task.publishDueEvents());

        verify(orderPublisher, never()).send(any(), any());
    }
}
