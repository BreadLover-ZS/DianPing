package com.dish.review.mq;

import com.dish.review.entity.SeckillFailureCase;
import com.dish.review.service.SeckillFailureCaseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.util.ErrorHandler;

/**
 * 监听容器 ErrorHandler：兜底处理发生在 Listener 方法执行前的
 * 反序列化和消息转换异常（规格第 9.2、13 节）。
 *
 * <p>正常部署下消费重试已启用，转换异常会先经重试模板进入
 * {@code seckillOrderMessageRecoverer} 完成持久化；本组件覆盖
 * 重试被禁用或异常逃逸出重试拦截器的场景：
 * 从失败的原始 AMQP Message 提取受限长度的证据，先持久化失败记录，
 * 再拒绝消息进入 DLQ；持久化失败时强制重新入队，禁止 ACK 或丢弃。
 * 其他异常原样抛出，保留容器默认行为。</p>
 */
@Slf4j
public class SeckillRabbitListenerErrorHandler implements ErrorHandler {

    private final SeckillFailureCaseService failureCaseService;

    /**
     * 注入失败记录服务，负责“失败记录 + 事件状态”同事务持久化。
     */
    public SeckillRabbitListenerErrorHandler(
            SeckillFailureCaseService failureCaseService) {
        this.failureCaseService = failureCaseService;
    }

    /**
     * 转换失败：先持久化失败记录再拒绝；其他异常原样抛出。
     */
    @Override
    public void handleError(Throwable throwable) {
        // Recoverer 已做拒绝/重入决定：放行，不重复处理
        if (hasAlreadyDecided(throwable)) {
            rethrow(throwable);
            return;
        }

        Message failedMessage = findFailedMessage(throwable);

        if (failedMessage == null || !isConversionFailure(throwable)) {
            // 非转换类异常：保留容器默认行为
            rethrow(throwable);
            return;
        }

        SeckillFailureCase evidence =
                SeckillFailureEvidence.from(failedMessage, throwable);

        try {
            failureCaseService.recordConsumerDlqFailure(evidence, false);
        } catch (Exception persistenceException) {
            log.error(
                    "[SECKILL_DLQ_PERSISTENCE_UNAVAILABLE] "
                            + "Listener 前转换失败的失败记录持久化失败，强制重新入队，"
                            + "idempotencyKey={}，errorCode={}",
                    evidence.getIdempotencyKey(),
                    evidence.getErrorCode(),
                    persistenceException
            );

            throw new ImmediateRequeueAmqpException(persistenceException);
        }

        log.error(
                "Listener 前消息转换失败，失败记录已持久化，拒绝消息进入 DLQ，"
                        + "eventId={}，errorCode={}",
                evidence.getEventId(),
                evidence.getErrorCode()
        );

        throw new AmqpRejectAndDontRequeueException(
                "消息转换失败，失败记录已持久化，转入 DLQ",
                throwable
        );
    }

    /**
     * 异常链中已存在 Recoverer 抛出的拒绝或强制重入异常。
     */
    private boolean hasAlreadyDecided(Throwable throwable) {
        Throwable current = throwable;

        while (current != null) {
            if (current instanceof AmqpRejectAndDontRequeueException
                    || current instanceof ImmediateRequeueAmqpException) {
                return true;
            }

            current = current.getCause();
        }

        return false;
    }

    /**
     * 判断异常链是否为反序列化或消息转换失败。
     */
    private boolean isConversionFailure(Throwable throwable) {
        Throwable current = throwable;

        while (current != null) {
            if (current instanceof MessageConversionException
                    || current instanceof org.springframework.messaging
                    .converter.MessageConversionException) {
                return true;
            }

            current = current.getCause();
        }

        return false;
    }

    /**
     * 从异常链提取失败消息；容器会把监听异常包装为
     * {@link ListenerExecutionFailedException} 并携带原始 Message。
     */
    private Message findFailedMessage(Throwable throwable) {
        Throwable current = throwable;

        while (current != null) {
            if (current instanceof ListenerExecutionFailedException) {
                ListenerExecutionFailedException failed =
                        (ListenerExecutionFailedException) current;

                if (!failed.getFailedMessages().isEmpty()) {
                    return failed.getFailedMessage();
                }
            }

            current = current.getCause();
        }

        return null;
    }

    /**
     * 原样抛出，交还容器按异常类型决定 ACK、拒绝或重新入队。
     */
    private void rethrow(Throwable throwable) {
        if (throwable instanceof RuntimeException) {
            throw (RuntimeException) throwable;
        }

        throw new ListenerExecutionFailedException(
                "监听器执行失败（非运行时异常）",
                new RuntimeException(throwable)
        );
    }
}
