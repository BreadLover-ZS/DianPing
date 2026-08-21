package com.dish.review.mq;

import com.dish.review.entity.SeckillFailureCase;
import com.dish.review.exception.SeckillConsistencyException;
import com.dish.review.exception.SeckillPermanentMessageException;
import com.dish.review.utils.RabbitMqConstants;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConversionException;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证失败证据提取的幂等键、错误码分类和受限长度摘要（规格第 9.2、13 节）。 */
class SeckillFailureEvidenceTests {

    private Message messageWithHeaders(String eventId) {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId("msg-1");

        if (eventId != null) {
            properties.setHeader(
                    RabbitMqConstants.SECKILL_ORDER_EVENT_ID_HEADER,
                    eventId);
            properties.setHeader(
                    RabbitMqConstants.SECKILL_ORDER_ID_HEADER, "100");
            properties.setHeader(
                    RabbitMqConstants.SECKILL_ORDER_USER_ID_HEADER, "7");
            properties.setHeader(
                    RabbitMqConstants.SECKILL_ORDER_VOUCHER_ID_HEADER, "10");
        }

        return new Message(
                "{\"eventId\":\"event-1\"}".getBytes(), properties);
    }

    @Test
    void idempotencyKeyUsesEventIdWhenPresent() {
        Message message = messageWithHeaders("event-1");

        assertEquals(
                "CONSUMER_DLQ:event-1",
                SeckillFailureEvidence.idempotencyKey(message, "event-1"));
    }

    @Test
    void idempotencyKeyFallsBackToBodyDigest() {
        Message message = messageWithHeaders(null);

        String key = SeckillFailureEvidence.idempotencyKey(message, null);

        assertTrue(key.startsWith("CONSUMER_DLQ:BODY:"));
        // 相同消息体生成相同幂等键（重投递幂等）
        assertEquals(
                key,
                SeckillFailureEvidence.idempotencyKey(
                        messageWithHeaders(null), null));
    }

    @Test
    void evidenceCarriesHeaderIdentityAndLimits() {
        Message message = messageWithHeaders("event-1");

        SeckillFailureCase evidence = SeckillFailureEvidence.from(
                message,
                new SeckillPermanentMessageException("格式不受支持"));

        assertEquals("CONSUMER_DLQ:event-1", evidence.getIdempotencyKey());
        assertEquals("event-1", evidence.getEventId());
        assertEquals(Long.valueOf(100L), evidence.getOrderId());
        assertEquals(Long.valueOf(7L), evidence.getUserId());
        assertEquals(Long.valueOf(10L), evidence.getVoucherId());
        assertEquals(SeckillFailureCase.SOURCE_CONSUMER_DLQ,
                evidence.getSource());
        assertEquals(SeckillFailureCase.STATUS_OPEN, evidence.getStatus());
        assertEquals("permanent_message_failure", evidence.getErrorCode());
        assertNotNull(evidence.getMessagePayload());
        assertTrue(evidence.getMessagePayload().contains("bodyDigest="));
    }

    @Test
    void errorCodeClassifiesConsistencyConflict() {
        assertEquals(
                "consistency_conflict",
                SeckillFailureEvidence.errorCodeOf(
                        new SeckillConsistencyException("冲突")));

        assertEquals(
                "consistency_conflict",
                SeckillFailureEvidence.errorCodeOf(
                        new RuntimeException(
                                new SeckillConsistencyException("嵌套"))));
    }

    @Test
    void errorCodeClassifiesPermanentMessageFailure() {
        assertEquals(
                "permanent_message_failure",
                SeckillFailureEvidence.errorCodeOf(
                        new MessageConversionException("转换失败")));

        assertEquals(
                "permanent_message_failure",
                SeckillFailureEvidence.errorCodeOf(
                        new SeckillPermanentMessageException("版本错误")));
    }

    @Test
    void errorCodeDefaultsToRetryExhausted() {
        assertEquals(
                "consumer_retry_exhausted",
                SeckillFailureEvidence.errorCodeOf(
                        new RuntimeException("数据库超时")));
    }

    @Test
    void consistencyConflictDetectionWalksCauseChain() {
        assertTrue(SeckillFailureEvidence.isConsistencyConflict(
                new RuntimeException(
                        new SeckillConsistencyException("嵌套冲突"))));

        assertEquals(false, SeckillFailureEvidence.isConsistencyConflict(
                new RuntimeException("普通异常")));
    }

    @Test
    void xDeathSummaryIsNullWithoutHeader() {
        assertNull(SeckillFailureEvidence.xDeathSummary(
                messageWithHeaders("event-1")));
    }

    @Test
    void xDeathSummaryIncludesQueueReasonAndCount() {
        MessageProperties properties = new MessageProperties();

        Map<String, Object> death = new HashMap<>();
        death.put("queue", "dianping.seckill.order.queue");
        death.put("reason", "rejected");
        death.put("count", 1);
        death.put("exchange", "dianping.seckill.direct");

        properties.getHeaders().put("x-death",
                java.util.Collections.singletonList(
                        new HashMap<String, Object>(death)));

        Message message = new Message(new byte[0], properties);

        String summary = SeckillFailureEvidence.xDeathSummary(message);

        assertTrue(summary.contains("queue=dianping.seckill.order.queue"));
        assertTrue(summary.contains("reason=rejected"));
        assertTrue(summary.contains("count=1"));
    }

    @Test
    void payloadSummaryLimitsPreviewLength() {
        MessageProperties properties = new MessageProperties();
        StringBuilder largeBody = new StringBuilder();

        for (int i = 0; i < 500; i++) {
            largeBody.append('x');
        }

        Message message = new Message(
                largeBody.toString().getBytes(), properties);

        String summary = SeckillFailureEvidence.payloadSummary(message);

        assertTrue(summary.contains("bodyLength=500"));
        assertTrue(summary.contains("...(truncated)"));
    }

    @Test
    void emptyBodyDigestIsStable() {
        Message message = new Message(
                new byte[0], new MessageProperties());

        assertEquals("empty", SeckillFailureEvidence.digest(message));
    }

    @Test
    void sameBodyProducesSameDigest() {
        assertEquals(
                SeckillFailureEvidence.digest(messageWithHeaders("a")),
                SeckillFailureEvidence.digest(messageWithHeaders("b")));
    }
}
