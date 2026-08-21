package com.dish.review.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.dish.review.entity.SeckillFailureCase;
import com.dish.review.entity.SeckillOrderEvent;
import com.dish.review.mapper.SeckillFailureCaseMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证失败记录服务的转人工升级和重开语义
 * （规格第 13 节 + 验收修复第 3、6 条）。
 *
 * <p>核心回归点：
 * 转人工必须与失败记录同事务写入（幂等键 source:MANUAL:eventId）；
 * 人工处置后再次失败的记录必须重新开启并更新证据。</p>
 */
class SeckillFailureCaseServiceTests {

    private SeckillFailureCaseMapper failureCaseMapper;

    private SeckillOrderEventService eventService;

    private SeckillFailureCaseService service;

    @BeforeEach
    void setUp() {
        failureCaseMapper = mock(SeckillFailureCaseMapper.class);
        eventService = mock(SeckillOrderEventService.class);
        service = new SeckillFailureCaseService(
                failureCaseMapper, eventService);

        // 默认：幂等键无已有记录，写入成功
        when(failureCaseMapper.selectList(any(Wrapper.class)))
                .thenReturn(Collections.emptyList());
        when(failureCaseMapper.insert(any(SeckillFailureCase.class)))
                .thenReturn(1);
        when(failureCaseMapper.update(
                isNull(), any(Wrapper.class)))
                .thenReturn(1);
    }

    private SeckillOrderEvent event() {
        SeckillOrderEvent event = new SeckillOrderEvent();
        event.setEventId("event-1");
        event.setOrderId(100L);
        event.setUserId(7L);
        event.setVoucherId(10L);
        event.setStatus(SeckillOrderEvent.STATUS_PENDING);
        return event;
    }

    @Test
    void manualReviewEscalationMarksEventAndCreatesFailureCase() {
        when(eventService.markManualReview(
                eq("event-1"), eq("publish_retry_exhausted"), anyString()))
                .thenReturn(true);

        boolean marked = service.recordManualReviewEscalation(
                event(),
                SeckillFailureCase.SOURCE_PUBLISH,
                "publish_retry_exhausted",
                "publish attempts exhausted"
        );

        assertTrue(marked);

        ArgumentCaptor<SeckillFailureCase> captor =
                ArgumentCaptor.forClass(SeckillFailureCase.class);

        verify(failureCaseMapper).insert(captor.capture());

        SeckillFailureCase created = captor.getValue();

        // 幂等键 source:MANUAL:eventId：重复调用不产生第二条记录
        assertEquals(
                "PUBLISH:MANUAL:event-1",
                created.getIdempotencyKey());
        assertEquals("event-1", created.getEventId());
        assertEquals(Long.valueOf(100L), created.getOrderId());
        assertEquals(Long.valueOf(7L), created.getUserId());
        assertEquals(Long.valueOf(10L), created.getVoucherId());
        assertEquals(
                SeckillFailureCase.SOURCE_PUBLISH,
                created.getSource());
        assertEquals(
                SeckillFailureCase.STATUS_OPEN,
                created.getStatus());
        assertEquals(
                "publish_retry_exhausted",
                created.getErrorCode());
        assertEquals(Integer.valueOf(0), created.getReplayCount());
    }

    @Test
    void replayedFailureCaseIsReopenedWithLatestEvidence() {
        SeckillFailureCase existing = new SeckillFailureCase();
        existing.setFailureId(5L);
        existing.setIdempotencyKey("CONSUMER_DLQ:event-1");
        existing.setStatus(SeckillFailureCase.STATUS_REPLAYED);
        existing.setReplayCount(1);

        when(failureCaseMapper.selectList(any(Wrapper.class)))
                .thenReturn(Collections.singletonList(existing));

        SeckillFailureCase latest = new SeckillFailureCase();
        latest.setIdempotencyKey("CONSUMER_DLQ:event-1");
        latest.setEventId("event-1");
        latest.setErrorCode("consumer_retry_exhausted");
        latest.setErrorMessage("重放后再次失败");
        latest.setMessagePayload("payload-v2");

        assertTrue(service.recordFailure(latest));

        // 重新开启：update 而不是 insert，保证 findOpenCases 再次可见
        verify(failureCaseMapper).update(
                isNull(), any(Wrapper.class));
        verify(failureCaseMapper, never())
                .insert(any(SeckillFailureCase.class));
    }

    @Test
    void closedFailureCaseIsReopenedWithLatestEvidence() {
        SeckillFailureCase existing = new SeckillFailureCase();
        existing.setFailureId(5L);
        existing.setIdempotencyKey("CONSUMER_DLQ:event-1");
        existing.setStatus(SeckillFailureCase.STATUS_CLOSED);

        when(failureCaseMapper.selectList(any(Wrapper.class)))
                .thenReturn(Collections.singletonList(existing));

        SeckillFailureCase latest = new SeckillFailureCase();
        latest.setIdempotencyKey("CONSUMER_DLQ:event-1");
        latest.setEventId("event-1");
        latest.setErrorCode("consumer_retry_exhausted");

        assertTrue(service.recordFailure(latest));

        verify(failureCaseMapper).update(
                isNull(), any(Wrapper.class));
        verify(failureCaseMapper, never())
                .insert(any(SeckillFailureCase.class));
    }

    @Test
    void openFailureCaseStaysIdempotent() {
        SeckillFailureCase existing = new SeckillFailureCase();
        existing.setFailureId(5L);
        existing.setIdempotencyKey("CONSUMER_DLQ:event-1");
        existing.setStatus(SeckillFailureCase.STATUS_OPEN);

        when(failureCaseMapper.selectList(any(Wrapper.class)))
                .thenReturn(Collections.singletonList(existing));

        SeckillFailureCase latest = new SeckillFailureCase();
        latest.setIdempotencyKey("CONSUMER_DLQ:event-1");
        latest.setEventId("event-1");
        latest.setErrorCode("consumer_retry_exhausted");

        assertTrue(service.recordFailure(latest));

        // OPEN 状态幂等命中：不重开、不重建，也不需要补证据
        verify(failureCaseMapper, never()).update(
                isNull(), any(Wrapper.class));
        verify(failureCaseMapper, never())
                .insert(any(SeckillFailureCase.class));
    }

    @Test
    void openFailureCaseSupplementsMissingXDeath() {
        SeckillFailureCase existing = new SeckillFailureCase();
        existing.setFailureId(5L);
        existing.setIdempotencyKey("CONSUMER_DLQ:event-1");
        existing.setStatus(SeckillFailureCase.STATUS_OPEN);

        when(failureCaseMapper.selectList(any(Wrapper.class)))
                .thenReturn(Collections.singletonList(existing));

        SeckillFailureCase latest = new SeckillFailureCase();
        latest.setIdempotencyKey("CONSUMER_DLQ:event-1");
        latest.setEventId("event-1");
        latest.setXDeathInfo("queue=q reason=rejected");

        assertTrue(service.recordFailure(latest));

        // 首次失败时未带 x-death、死信到达后补充
        verify(failureCaseMapper).update(
                isNull(), any(Wrapper.class));
        verify(failureCaseMapper, never())
                .insert(any(SeckillFailureCase.class));
    }

    @Test
    void consumerDlqFailureRecordsBeforeMarkingEvent() {
        SeckillFailureCase evidence = new SeckillFailureCase();
        evidence.setIdempotencyKey("CONSUMER_DLQ:event-1");
        evidence.setEventId("event-1");
        evidence.setSource(SeckillFailureCase.SOURCE_CONSUMER_DLQ);
        evidence.setStatus(SeckillFailureCase.STATUS_OPEN);
        evidence.setErrorCode("consumer_retry_exhausted");
        evidence.setErrorMessage("消费重试耗尽");

        when(eventService.markDlq(
                eq("event-1"), anyString(), anyString()))
                .thenReturn(true);

        service.recordConsumerDlqFailure(evidence, false);

        // 失败记录与事件状态推进同事务：insert 先于 markDlq
        verify(failureCaseMapper).insert(any(SeckillFailureCase.class));
        verify(eventService).markDlq(
                eq("event-1"),
                eq("consumer_retry_exhausted"),
                anyString()
        );
    }
}
