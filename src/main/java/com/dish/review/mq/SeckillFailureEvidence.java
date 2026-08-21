package com.dish.review.mq;

import com.dish.review.entity.SeckillFailureCase;
import com.dish.review.exception.SeckillConsistencyException;
import com.dish.review.exception.SeckillPermanentMessageException;
import com.dish.review.utils.RabbitMqConstants;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.support.converter.MessageConversionException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;

/**
 * 从原始 AMQP 消息提取受限长度的失败证据（规格第 9.2、13 节）。
 *
 * <p>Recoverer、容器 ErrorHandler 和 DLQ 消费者共用本工具，
 * 保证幂等键、错误码分类和消息摘要的口径一致；
 * 禁止把无法转换的原始消息完整写入日志或数据库。</p>
 */
public final class SeckillFailureEvidence {

    /** 消费侧失败记录的幂等键前缀。 */
    private static final String IDEMPOTENCY_PREFIX = "CONSUMER_DLQ:";

    /** 无 eventId 时使用消息体摘要作为幂等键。 */
    private static final String IDEMPOTENCY_BODY_PREFIX = "CONSUMER_DLQ:BODY:";

    /** 消息体预览的最大字符数。 */
    private static final int BODY_PREVIEW_MAX_CHARS = 200;

    /** x-death 摘要的最大字符数。 */
    private static final int X_DEATH_MAX_CHARS = 1024;

    private SeckillFailureEvidence() {
    }

    /**
     * 从失败消息和异常链构造一条消费侧失败记录。
     */
    public static SeckillFailureCase from(Message message, Throwable cause) {
        String eventId = headerAsString(
                message, RabbitMqConstants.SECKILL_ORDER_EVENT_ID_HEADER);

        SeckillFailureCase failureCase = new SeckillFailureCase();

        failureCase.setIdempotencyKey(idempotencyKey(message, eventId));
        failureCase.setEventId(isBlank(eventId) ? null : eventId);
        failureCase.setOrderId(headerAsLong(
                message, RabbitMqConstants.SECKILL_ORDER_ID_HEADER));
        failureCase.setUserId(headerAsLong(
                message, RabbitMqConstants.SECKILL_ORDER_USER_ID_HEADER));
        failureCase.setVoucherId(headerAsLong(
                message, RabbitMqConstants.SECKILL_ORDER_VOUCHER_ID_HEADER));
        failureCase.setSource(SeckillFailureCase.SOURCE_CONSUMER_DLQ);
        failureCase.setStatus(SeckillFailureCase.STATUS_OPEN);
        failureCase.setErrorCode(errorCodeOf(cause));
        failureCase.setErrorMessage(errorMessageOf(cause));
        failureCase.setMessagePayload(payloadSummary(message));
        failureCase.setReplayCount(0);

        return failureCase;
    }

    /**
     * 判断异常链是否为 Redis/MySQL 一致性冲突（转人工核对而不是 DLQ）。
     */
    public static boolean isConsistencyConflict(Throwable cause) {
        return chainContains(cause, SeckillConsistencyException.class);
    }

    /**
     * 稳定错误码：一致性冲突、永久消息错误或重试耗尽。
     */
    public static String errorCodeOf(Throwable cause) {
        if (chainContains(cause, SeckillConsistencyException.class)) {
            return "consistency_conflict";
        }

        if (chainContains(cause, SeckillPermanentMessageException.class)
                || chainContains(
                cause,
                org.springframework.messaging.converter
                        .MessageConversionException.class)
                || chainContains(cause, MessageConversionException.class)) {
            return "permanent_message_failure";
        }

        return "consumer_retry_exhausted";
    }

    /**
     * 生成幂等键：有 eventId 按事件；没有则退化为消息体摘要。
     */
    public static String idempotencyKey(Message message, String eventId) {
        if (!isBlank(eventId)) {
            return IDEMPOTENCY_PREFIX + eventId.trim();
        }

        return IDEMPOTENCY_BODY_PREFIX + digest(message);
    }

    /**
     * 构造受限长度的消息体摘要：messageId、MD5 和截断预览。
     */
    public static String payloadSummary(Message message) {
        if (message == null) {
            return null;
        }

        String messageId = message.getMessageProperties() == null
                ? null
                : message.getMessageProperties().getMessageId();

        return "messageId=" + (isBlank(messageId) ? "absent" : messageId.trim())
                + ", bodyDigest=" + digest(message)
                + ", bodyLength=" + bodyLength(message)
                + ", bodyPreview=" + preview(message);
    }

    /**
     * 提取 DLQ 消息的 x-death 摘要（队列、原因、次数）。
     */
    public static String xDeathSummary(Message message) {
        if (message == null
                || message.getMessageProperties() == null) {
            return null;
        }

        List<Map<String, ?>> xDeath =
                message.getMessageProperties().getXDeathHeader();

        if (xDeath == null || xDeath.isEmpty()) {
            return null;
        }

        StringBuilder builder = new StringBuilder("[");

        for (Map<String, ?> death : xDeath) {
            if (death == null) {
                continue;
            }

            if (builder.length() > 1) {
                builder.append("; ");
            }

            builder.append("queue=").append(death.get("queue"))
                    .append(", reason=").append(death.get("reason"))
                    .append(", count=").append(death.get("count"))
                    .append(", exchange=").append(death.get("exchange"));
        }

        builder.append("]");

        return truncate(builder.toString(), X_DEATH_MAX_CHARS);
    }

    /**
     * 计算消息体 MD5 摘要（16 进制），重投递的同一消息保持一致。
     */
    public static String digest(Message message) {
        byte[] body = message == null ? null : message.getBody();

        if (body == null || body.length == 0) {
            return "empty";
        }

        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");

            byte[] digest = md5.digest(body);

            StringBuilder builder = new StringBuilder(digest.length * 2);

            for (byte b : digest) {
                builder.append(Character.forDigit(
                        (b >> 4) & 0xF, 16));
                builder.append(Character.forDigit(b & 0xF, 16));
            }

            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            // JDK 必须提供 MD5；防御性返回长度指纹
            return "len-" + body.length;
        }
    }

    /**
     * 从 Header 读取字符串值。
     */
    public static String headerAsString(Message message, String headerName) {
        if (message == null
                || message.getMessageProperties() == null) {
            return null;
        }

        Object value = message.getMessageProperties()
                .getHeaders()
                .get(headerName);

        return value == null ? null : value.toString();
    }

    /**
     * 从 Header 读取 Long 值；缺失或非法时返回 null。
     */
    public static Long headerAsLong(Message message, String headerName) {
        String value = headerAsString(message, headerName);

        if (isBlank(value)) {
            return null;
        }

        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /**
     * 沿异常链查找指定类型的异常。
     */
    private static boolean chainContains(
            Throwable throwable, Class<?> type) {

        Throwable current = throwable;

        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }

            current = current.getCause();
        }

        return false;
    }

    /**
     * 提取异常消息并限制长度。
     */
    private static String errorMessageOf(Throwable cause) {
        if (cause == null || isBlank(cause.getMessage())) {
            return "unknown";
        }

        return truncate(cause.getMessage().trim(), 512);
    }

    /**
     * 生成消息体文本预览，控制字符转义并截断。
     */
    private static String preview(Message message) {
        byte[] body = message == null ? null : message.getBody();

        if (body == null || body.length == 0) {
            return "empty";
        }

        String text = new String(body, StandardCharsets.UTF_8);
        StringBuilder builder = new StringBuilder();

        int limit = Math.min(text.length(), BODY_PREVIEW_MAX_CHARS);

        for (int i = 0; i < limit; i++) {
            char c = text.charAt(i);

            if (c < 0x20 || c == 0x7F) {
                builder.append('\\').append((int) c);
            } else {
                builder.append(c);
            }
        }

        if (text.length() > BODY_PREVIEW_MAX_CHARS) {
            builder.append("...(truncated)");
        }

        return builder.toString();
    }

    /**
     * 消息体字节长度。
     */
    private static int bodyLength(Message message) {
        byte[] body = message == null ? null : message.getBody();

        return body == null ? 0 : body.length;
    }

    /**
     * 截断文本到指定长度。
     */
    private static String truncate(String text, int maxLength) {
        if (text == null || text.isEmpty()) {
            return null;
        }

        return text.length() <= maxLength
                ? text
                : text.substring(0, maxLength);
    }

    /**
     * 判断字符串是否为 null、空串或纯空白。
     */
    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
