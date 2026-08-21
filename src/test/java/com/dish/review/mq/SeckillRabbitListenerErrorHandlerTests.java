package com.dish.review.mq;

import com.dish.review.entity.SeckillFailureCase;
import com.dish.review.service.SeckillFailureCaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;
import org.springframework.amqp.support.converter.MessageConversionException;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证 Listener 前转换失败的“先持久化、再拒绝”与持久化失败强制重入（规格第 9.2、13 节）。 */
class SeckillRabbitListenerErrorHandlerTests {

    private SeckillFailureCaseService failureCaseService;
    private SeckillRabbitListenerErrorHandler handler;

    @BeforeEach
    void setUp() {
        failureCaseService = mock(SeckillFailureCaseService.class);
        handler = new SeckillRabbitListenerErrorHandler(failureCaseService);
    }

    private ListenerExecutionFailedException conversionFailure() {
        MessageProperties properties = new MessageProperties();
        properties.setHeader("eventId", "event-1");
        Message message = new Message("{}".getBytes(), properties);

        return new ListenerExecutionFailedException(
                "Listener threw exception",
                new MessageConversionException("无法反序列化"),
                message);
    }

    @Test
    void conversionFailurePersistsRecordThenRejects() {
        ListenerExecutionFailedException failure = conversionFailure();

        AmqpRejectAndDontRequeueException thrown = assertThrows(
                AmqpRejectAndDontRequeueException.class,
                () -> handler.handleError(failure));

        assertSame(failure, thrown.getCause());
        verify(failureCaseService).recordConsumerDlqFailure(
                argThat(evidence ->
                        "CONSUMER_DLQ:event-1".equals(
                                evidence.getIdempotencyKey())
                                && "permanent_message_failure".equals(
                                evidence.getErrorCode())),
                anyBoolean());
    }

    @Test
    void persistenceFailureForcesImmediateRequeue() {
        doThrow(new RuntimeException("数据库不可用"))
                .when(failureCaseService)
                .recordConsumerDlqFailure(any(), anyBoolean());

        assertThrows(ImmediateRequeueAmqpException.class,
                () -> handler.handleError(conversionFailure()));
    }

    @Test
    void nonConversionFailureIsRethrownAsIs() {
        ListenerExecutionFailedException failure =
                new ListenerExecutionFailedException(
                        "Listener threw exception",
                        new IllegalStateException("业务异常"),
                        new Message(new byte[0], new MessageProperties()));

        assertThrows(ListenerExecutionFailedException.class,
                () -> handler.handleError(failure));

        verify(failureCaseService, never())
                .recordConsumerDlqFailure(any(), anyBoolean());
    }

    @Test
    void recovererDecisionIsNotDoubleProcessed() {
        // Recoverer 已抛出拒绝异常：ErrorHandler 直接放行，不重复持久化
        ListenerExecutionFailedException failure = conversionFailure();
        AmqpRejectAndDontRequeueException decided =
                new AmqpRejectAndDontRequeueException("已由 Recoverer 决定", failure);

        assertThrows(AmqpRejectAndDontRequeueException.class,
                () -> handler.handleError(decided));

        verify(failureCaseService, never())
                .recordConsumerDlqFailure(any(), anyBoolean());
    }

    @Test
    void requeueDecisionIsNotDoubleProcessed() {
        ImmediateRequeueAmqpException decided =
                new ImmediateRequeueAmqpException("已决定重入");

        assertThrows(ImmediateRequeueAmqpException.class,
                () -> handler.handleError(decided));

        verify(failureCaseService, never())
                .recordConsumerDlqFailure(any(), anyBoolean());
    }

    @Test
    void conversionFailureWithoutMessageIsRethrown() {
        // 无失败消息可提取：保留容器默认行为
        MessageConversionException noMessage =
                new MessageConversionException("无消息上下文");

        assertThrows(MessageConversionException.class,
                () -> handler.handleError(noMessage));

        verify(failureCaseService, never())
                .recordConsumerDlqFailure(any(), anyBoolean());
    }
}
