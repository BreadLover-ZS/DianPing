package com.dish.review.service;

import com.dish.review.entity.SeckillOrderEvent;
import com.dish.review.mapper.SeckillOrderEventMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证秒杀订单发布补偿的状态和退避规则。 */
class SeckillOrderEventServiceTests {

    @Test
    void pendingEventUsesOneTwoFourSecondFastRetries() {
        for (int retryCount = 1; retryCount <= 3; retryCount++) {
            int status = SeckillOrderEventService.resolveStatusAfterPublishFailure(
                    SeckillOrderEvent.STATUS_PENDING,
                    retryCount
            );
            long delay = SeckillOrderEventService.resolveRetryDelaySeconds(
                    status,
                    retryCount
            );

            assertEquals(SeckillOrderEvent.STATUS_PENDING, status);
            assertEquals(1L << (retryCount - 1), delay);
        }
    }

    @Test
    void fourthPublishMovesEventToPublishUnknownSlowRetry() {
        int status = SeckillOrderEventService.resolveStatusBeforePublish(
                SeckillOrderEvent.STATUS_PENDING,
                4
        );
        long delay = SeckillOrderEventService.resolveRetryDelaySeconds(
                status,
                4
        );

        assertEquals(SeckillOrderEvent.STATUS_PUBLISH_UNKNOWN, status);
        assertEquals(60L, delay);
    }

    @Test
    void publishUnknownEventRemainsInSlowRetryRegardlessOfCount() {
        int status = SeckillOrderEventService.resolveStatusBeforePublish(
                SeckillOrderEvent.STATUS_PUBLISH_UNKNOWN,
                12
        );
        long delay = SeckillOrderEventService.resolveRetryDelaySeconds(
                status,
                12
        );

        assertEquals(SeckillOrderEvent.STATUS_PUBLISH_UNKNOWN, status);
        assertEquals(60L, delay);
    }

    @Test
    void firstPublishFailureCanBeRecordedForRedisRollback() {
        SeckillOrderEventMapper mapper = mock(SeckillOrderEventMapper.class);
        when(mapper.update(isNull(), any())).thenReturn(1);

        SeckillOrderEventService service =
                new SeckillOrderEventService(mapper);

        assertTrue(service.markInitialPublishFailed("event-1", "nack"));
    }

    @Test
    void failureAfterRetryHistoryDoesNotAuthorizeRedisRollback() {
        SeckillOrderEventMapper mapper = mock(SeckillOrderEventMapper.class);
        SeckillOrderEvent existing = new SeckillOrderEvent();
        existing.setEventId("event-2");
        existing.setStatus(SeckillOrderEvent.STATUS_PUBLISH_UNKNOWN);
        existing.setRetryCount(4);

        when(mapper.update(isNull(), any())).thenReturn(0);
        when(mapper.selectById("event-2")).thenReturn(existing);

        SeckillOrderEventService service =
                new SeckillOrderEventService(mapper);

        assertFalse(service.markInitialPublishFailed("event-2", "returned"));
    }
}
