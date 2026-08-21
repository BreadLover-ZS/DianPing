package com.dish.review.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证发布重试策略的退避下标、耗尽哨兵和终局等待窗口（规格 9.1 节）。
 *
 * <p>核心回归点：attemptNo 以 1 为首个下标，首次发送后退避 1 秒；
 * 最后一次发送后不返回 STOP 给调用方直接停止，而是留给终局窗口。</p>
 */
class SeckillPublishRetryPolicyTests {

    @Test
    void backoffSequenceStartsWithOneSecond() {
        // 第 1 次发送后退避 1 秒，第 2 次 2 秒，依此类推
        assertEquals(1L, SeckillPublishRetryPolicy.nextDelaySeconds(1));
        assertEquals(2L, SeckillPublishRetryPolicy.nextDelaySeconds(2));
        assertEquals(4L, SeckillPublishRetryPolicy.nextDelaySeconds(3));
        assertEquals(30L, SeckillPublishRetryPolicy.nextDelaySeconds(4));
        assertEquals(120L, SeckillPublishRetryPolicy.nextDelaySeconds(5));
        assertEquals(600L, SeckillPublishRetryPolicy.nextDelaySeconds(6));
        assertEquals(1800L, SeckillPublishRetryPolicy.nextDelaySeconds(7));
    }

    @Test
    void invalidAttemptCountFallsBackToFirstBackoff() {
        assertEquals(1L, SeckillPublishRetryPolicy.nextDelaySeconds(0));
        assertEquals(1L, SeckillPublishRetryPolicy.nextDelaySeconds(-3));
    }

    @Test
    void attemptsExhaustedStopsAutomaticRetry() {
        assertEquals(8, SeckillPublishRetryPolicy.maxAutomaticAttempts());
        assertEquals(
                SeckillPublishRetryPolicy.STOP_AUTOMATIC_RETRY,
                SeckillPublishRetryPolicy.nextDelaySeconds(8));
        assertEquals(
                SeckillPublishRetryPolicy.STOP_AUTOMATIC_RETRY,
                SeckillPublishRetryPolicy.nextDelaySeconds(100));
    }

    @Test
    void finalDecisionWindowExceedsDefaultConfirmTimeout() {
        // 终局窗口必须大于确认超时（默认 30 秒），
        // 保证最后一次发送的 Confirm/超时决策先收敛
        assertTrue(SeckillPublishRetryPolicy.FINAL_DECISION_WAIT_SECONDS > 30L);
    }
}
