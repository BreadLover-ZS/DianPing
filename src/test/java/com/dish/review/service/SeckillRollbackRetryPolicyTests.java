package com.dish.review.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 回滚退避策略测试：验证退避下标与转人工上限（第 9 节验收修复）。
 *
 * <p>执行顺序必须为：
 * 第 1 次失败 → 5 秒；第 2 次 → 30 秒；第 3 次 → 5 分钟；
 * 第 4 次 → 30 分钟；第 5 次 → MANUAL_REVIEW（停止自动重试）。
 * 语义与 {@link SeckillPublishRetryPolicy} 对齐：
 * 第 N 次失败退避 BACKOFF_SECONDS[N-1]。</p>
 */
class SeckillRollbackRetryPolicyTests {

    @Test
    void firstFailureBacksOffFiveSeconds() {
        assertEquals(5L, SeckillRollbackRetryPolicy.nextDelaySeconds(1));
    }

    @Test
    void secondFailureBacksOffThirtySeconds() {
        assertEquals(30L, SeckillRollbackRetryPolicy.nextDelaySeconds(2));
    }

    @Test
    void thirdFailureBacksOffFiveMinutes() {
        assertEquals(300L, SeckillRollbackRetryPolicy.nextDelaySeconds(3));
    }

    @Test
    void fourthFailureBacksOffThirtyMinutes() {
        assertEquals(1800L, SeckillRollbackRetryPolicy.nextDelaySeconds(4));
    }

    @Test
    void fifthFailureStopsAutomaticRetry() {
        assertEquals(
                SeckillRollbackRetryPolicy.STOP_AUTOMATIC_RETRY,
                SeckillRollbackRetryPolicy.nextDelaySeconds(5)
        );
    }

    @Test
    void zeroOrNegativeAttemptsTreatedAsFirst() {
        assertEquals(5L, SeckillRollbackRetryPolicy.nextDelaySeconds(0));
        assertEquals(5L, SeckillRollbackRetryPolicy.nextDelaySeconds(-3));
    }

    @Test
    void maxAutomaticAttemptsIsFive() {
        // 4 个退避档位 + 首次执行 = 5 次自动回滚，第 5 次失败转 MANUAL_REVIEW
        assertEquals(5, SeckillRollbackRetryPolicy.maxAutomaticAttempts());
    }

    @Test
    void attemptsBeyondMaxAlwaysStop() {
        assertEquals(
                SeckillRollbackRetryPolicy.STOP_AUTOMATIC_RETRY,
                SeckillRollbackRetryPolicy.nextDelaySeconds(6)
        );
        assertEquals(
                SeckillRollbackRetryPolicy.STOP_AUTOMATIC_RETRY,
                SeckillRollbackRetryPolicy.nextDelaySeconds(100)
        );
    }
}
