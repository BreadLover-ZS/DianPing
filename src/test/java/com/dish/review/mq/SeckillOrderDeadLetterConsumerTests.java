package com.dish.review.mq;

import com.dish.review.entity.SeckillFailureCase;
import com.dish.review.entity.SeckillOrderEvent;
import com.dish.review.service.SeckillFailureCaseService;
import com.dish.review.service.SeckillOrderEventService;
import com.dish.review.utils.RabbitMqConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证 DLQ 消费者的证据补充、补建告警和幂等关闭（规格第 13 节）。 */
class SeckillOrderDeadLetterConsumerTests {

    private SeckillFailureCaseService failureCaseService;
    private SeckillOrderEventService eventService;
    private SeckillOrderDeadLetterConsumer consumer;

    @BeforeEach
    void setUp() {
        failureCaseService = mock(SeckillFailureCaseService.class);
        eventService = mock(SeckillOrderEventService.class);
        consumer = new SeckillOrderDeadLetterConsumer(
                failureCaseService, eventService);
    }

    private Message deadLetterMessage(String eventId) {
        MessageProperties properties = new MessageProperties();
        properties.setHeader(
                RabbitMqConstants.SECKILL_ORDER_EVENT_ID_HEADER, eventId);

        return new Message("{}".getBytes(), properties);
    }

    private SeckillFailureCase openCase() {
        SeckillFailureCase failureCase = new SeckillFailureCase();
        failureCase.setFailureId(1L);
        failureCase.setIdempotencyKey("CONSUMER_DLQ:event-1");
        failureCase.setEventId("event-1");
        failureCase.setStatus(SeckillFailureCase.STATUS_OPEN);
        return failureCase;
    }

    private SeckillOrderEvent eventOf(int status) {
        SeckillOrderEvent event = new SeckillOrderEvent();
        event.setEventId("event-1");
        event.setStatus(status);
        return event;
    }

    @Test
    void existingCaseIsSupplementedWithXDeath() {
        when(failureCaseService.findByIdempotencyKey(
                "CONSUMER_DLQ:event-1")).thenReturn(openCase());

        assertDoesNotThrow(() ->
                consumer.consume(deadLetterMessage("event-1")));

        verify(failureCaseService).supplementDlqArrival(
                argThat(evidence ->
                        "CONSUMER_DLQ:event-1".equals(
                                evidence.getIdempotencyKey())));
    }

    @Test
    void missingPriorRecordIsRebuiltAndFlagged() {
        when(failureCaseService.findByIdempotencyKey(any()))
                .thenReturn(null);
        when(eventService.findByEventId("event-1"))
                .thenReturn(eventOf(SeckillOrderEvent.STATUS_DLQ));

        assertDoesNotThrow(() ->
                consumer.consume(deadLetterMessage("event-1")));

        // 补建记录带专用错误码，便于人工关注
        verify(failureCaseService).supplementDlqArrival(
                argThat(rebuilt ->
                        "dlq_arrival_no_prior_record".equals(
                                rebuilt.getErrorCode())));
    }

    @Test
    void consumedEventClosesCaseAsIdempotentSuccess() {
        SeckillFailureCase existing = openCase();
        when(failureCaseService.findByIdempotencyKey(
                "CONSUMER_DLQ:event-1")).thenReturn(existing);
        when(eventService.findByEventId("event-1"))
                .thenReturn(eventOf(SeckillOrderEvent.STATUS_CONSUMED));

        assertDoesNotThrow(() ->
                consumer.consume(deadLetterMessage("event-1")));

        verify(failureCaseService).closeAsIdempotentSuccess(1L);
    }

    @Test
    void nonConsumedEventKeepsCaseOpen() {
        when(failureCaseService.findByIdempotencyKey(
                "CONSUMER_DLQ:event-1")).thenReturn(openCase());
        when(eventService.findByEventId("event-1"))
                .thenReturn(eventOf(SeckillOrderEvent.STATUS_DLQ));

        assertDoesNotThrow(() ->
                consumer.consume(deadLetterMessage("event-1")));

        verify(failureCaseService, never())
                .closeAsIdempotentSuccess(anyLong());
    }

    @Test
    void persistenceFailureForcesRequeue() {
        when(failureCaseService.findByIdempotencyKey(any()))
                .thenThrow(new RuntimeException("数据库不可用"));

        assertThrows(ImmediateRequeueAmqpException.class,
                () -> consumer.consume(deadLetterMessage("event-1")));
    }

    @Test
    void missingEventIdKeepsCaseOpenForManualHandling() {
        when(failureCaseService.findByIdempotencyKey(any()))
                .thenReturn(openCase());

        assertDoesNotThrow(() ->
                consumer.consume(deadLetterMessage(null)));

        verify(eventService, never()).findByEventId(any());
        verify(failureCaseService, never())
                .closeAsIdempotentSuccess(anyLong());
    }

    @Test
    void missingEventRecordKeepsCaseOpen() {
        when(failureCaseService.findByIdempotencyKey(
                "CONSUMER_DLQ:event-1")).thenReturn(openCase());
        when(eventService.findByEventId("event-1")).thenReturn(null);

        assertDoesNotThrow(() ->
                consumer.consume(deadLetterMessage("event-1")));

        verify(failureCaseService, never())
                .closeAsIdempotentSuccess(anyLong());
    }
}
