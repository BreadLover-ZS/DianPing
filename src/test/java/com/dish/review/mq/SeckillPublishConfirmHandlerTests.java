package com.dish.review.mq;

import com.dish.review.service.SeckillOrderEventService;
import com.dish.review.service.SeckillOrderFailureDecisionService;
import com.dish.review.service.SeckillPublishAttemptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CorrelationData;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证 Confirm 与超时竞争后的迟到旁证不会覆盖原尝试结论。 */
class SeckillPublishConfirmHandlerTests {

    private SeckillPublishAttemptService attemptService;
    private SeckillOrderEventService eventService;
    private SeckillOrderFailureDecisionService decisionService;
    private SeckillPublishConfirmHandler handler;

    @BeforeEach
    void setUp() {
        attemptService = mock(SeckillPublishAttemptService.class);
        eventService = mock(SeckillOrderEventService.class);
        decisionService = mock(SeckillOrderFailureDecisionService.class);
        handler = new SeckillPublishConfirmHandler(
                attemptService, eventService, decisionService);
    }

    @Test
    void lateAckAddsEvidenceAndStillConvergesEvent() {
        when(attemptService.recordAck("attempt-1")).thenReturn(false);
        SeckillOrderCorrelationData data = correlationData();
        handler.attach(data);

        data.getFuture().set(new CorrelationData.Confirm(true, null));

        verify(attemptService).recordLateConfirm(
                "attempt-1", 1, "late_ack");
        verify(eventService).markConfirmed("event-1");
    }

    @Test
    void lateNackAddsEvidenceAndInvokesDecision() {
        when(attemptService.recordNack("attempt-1", "broker_nack"))
                .thenReturn(false);
        SeckillOrderCorrelationData data = correlationData();
        handler.attach(data);

        data.getFuture().set(
                new CorrelationData.Confirm(false, "broker_nack"));

        verify(attemptService).recordLateConfirm(
                "attempt-1", 2, "broker_nack");
        verify(decisionService).evaluateAfterConfirm("event-1");
    }

    private SeckillOrderCorrelationData correlationData() {
        return new SeckillOrderCorrelationData(
                "attempt-1", "event-1", 100L);
    }
}
