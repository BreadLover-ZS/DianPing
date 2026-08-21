package com.dish.review.service;

import com.dish.review.dto.Result;
import com.dish.review.dto.UserDTO;
import com.dish.review.entity.SeckillOrderEvent;
import com.dish.review.entity.VoucherOrder;
import com.dish.review.mapper.VoucherOrderMapper;
import com.dish.review.service.impl.VoucherOrderServiceImpl;
import com.dish.review.utils.RedisIdWorker;
import com.dish.review.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证订单状态查询接口的六种返回值与数据源降级语义（规格第 15 节）。
 *
 * <p>带 voucherId 的完整版接口可查询 Redis orderId 反向索引；
 * 旧版无 voucherId 接口在 MySQL 订单和事件都查不到时返回 UNAVAILABLE，
 * 不误报 NOT_FOUND。</p>
 */
class VoucherOrderServiceImplTests {

    private VoucherOrderMapper voucherOrderMapper;

    private ISeckillVoucherService seckillVoucherService;

    private SeckillVoucherLuaExecutor luaExecutor;

    private SeckillOrderEventService eventService;

    private VoucherOrderServiceImpl service;

    @BeforeEach
    void setUp() {
        UserHolder.saveUser(user(7L));

        voucherOrderMapper = mock(VoucherOrderMapper.class);
        seckillVoucherService = mock(ISeckillVoucherService.class);
        luaExecutor = mock(SeckillVoucherLuaExecutor.class);
        eventService = mock(SeckillOrderEventService.class);

        service = new VoucherOrderServiceImpl();

        ReflectionTestUtils.setField(
                service, "baseMapper", voucherOrderMapper);
        ReflectionTestUtils.setField(
                service, "seckillVoucherService", seckillVoucherService);
        ReflectionTestUtils.setField(
                service, "luaExecutor", luaExecutor);
        ReflectionTestUtils.setField(
                service, "eventService", eventService);
        ReflectionTestUtils.setField(
                service, "redisIdWorker", mock(RedisIdWorker.class));
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    private UserDTO user(Long id) {
        UserDTO user = new UserDTO();
        user.setId(id);
        return user;
    }

    private VoucherOrder orderOf(Long userId) {
        VoucherOrder order = new VoucherOrder();
        order.setId(100L);
        order.setUserId(userId);
        order.setVoucherId(10L);
        return order;
    }

    private SeckillOrderEvent eventOf(Long userId, int status) {
        SeckillOrderEvent event = new SeckillOrderEvent();
        event.setEventId("event-1");
        event.setOrderId(100L);
        event.setUserId(userId);
        event.setVoucherId(10L);
        event.setStatus(status);
        return event;
    }

    /** 旧版接口（无 voucherId）：只覆盖 MySQL 订单和事件两层。 */
    private String queryStatus(Long orderId) {
        Result result = service.queryOrderStatus(orderId);
        assertTrue(result.getSuccess());
        return (String) result.getData();
    }

    /** 完整版接口（带 voucherId）：可查询 Redis orderId 反向索引。 */
    private String queryStatusFull(Long voucherId, Long orderId) {
        Result result = service.queryOrderStatus(voucherId, orderId);
        assertTrue(result.getSuccess());
        return (String) result.getData();
    }

    @Test
    void notLoggedInFails() {
        UserHolder.removeUser();

        Result result = service.queryOrderStatus(100L);

        assertFalse(result.getSuccess());
        assertEquals("请先登录", result.getErrorMsg());
    }

    @Test
    void invalidOrderIdFails() {
        Result result = service.queryOrderStatus(0L);

        assertFalse(result.getSuccess());
    }

    @Test
    void invalidVoucherIdFails() {
        Result result = service.queryOrderStatus(0L, 100L);

        assertFalse(result.getSuccess());
    }

    @Test
    void existingOrderOfCurrentUserReturnsSuccess() {
        when(voucherOrderMapper.selectById(100L))
                .thenReturn(orderOf(7L));

        assertEquals("SUCCESS", queryStatus(100L));
        assertEquals("SUCCESS", queryStatusFull(10L, 100L));
    }

    @Test
    void orderOfAnotherUserReturnsNotFound() {
        when(voucherOrderMapper.selectById(100L))
                .thenReturn(orderOf(8L));

        assertEquals("NOT_FOUND", queryStatus(100L));
        assertEquals("NOT_FOUND", queryStatusFull(10L, 100L));
    }

    @Test
    void orderQueryFailureReturnsUnavailable() {
        when(voucherOrderMapper.selectById(100L))
                .thenThrow(new RuntimeException("db down"));

        assertEquals("UNAVAILABLE", queryStatus(100L));
        assertEquals("UNAVAILABLE", queryStatusFull(10L, 100L));
    }

    @Test
    void consumedEventReturnsSuccess() {
        when(voucherOrderMapper.selectById(100L)).thenReturn(null);
        when(eventService.findByOrderId(100L))
                .thenReturn(eventOf(7L, SeckillOrderEvent.STATUS_CONSUMED));

        assertEquals("SUCCESS", queryStatus(100L));
        assertEquals("SUCCESS", queryStatusFull(10L, 100L));
    }

    @Test
    void rolledBackEventReturnsFailed() {
        when(voucherOrderMapper.selectById(100L)).thenReturn(null);
        when(eventService.findByOrderId(100L))
                .thenReturn(eventOf(7L, SeckillOrderEvent.STATUS_ROLLED_BACK));

        assertEquals("FAILED", queryStatus(100L));
        assertEquals("FAILED", queryStatusFull(10L, 100L));
    }

    @Test
    void manualReviewDlqAndLegacyFailedReturnManualReview() {
        when(voucherOrderMapper.selectById(100L)).thenReturn(null);

        int[] statuses = {
                SeckillOrderEvent.STATUS_MANUAL_REVIEW,
                SeckillOrderEvent.STATUS_DLQ,
                SeckillOrderEvent.STATUS_FAILED
        };

        for (int status : statuses) {
            when(eventService.findByOrderId(100L))
                    .thenReturn(eventOf(7L, status));

            assertEquals("MANUAL_REVIEW", queryStatus(100L));
            assertEquals("MANUAL_REVIEW", queryStatusFull(10L, 100L));
        }
    }

    @Test
    void inFlightEventStatusesReturnProcessing() {
        when(voucherOrderMapper.selectById(100L)).thenReturn(null);

        int[] statuses = {
                SeckillOrderEvent.STATUS_PENDING,
                SeckillOrderEvent.STATUS_CONFIRMED,
                SeckillOrderEvent.STATUS_PUBLISH_UNKNOWN,
                SeckillOrderEvent.STATUS_ROLLBACK_PENDING,
                SeckillOrderEvent.STATUS_ROLLBACK_EXECUTING
        };

        for (int status : statuses) {
            when(eventService.findByOrderId(100L))
                    .thenReturn(eventOf(7L, status));

            assertEquals("PROCESSING", queryStatus(100L));
            assertEquals("PROCESSING", queryStatusFull(10L, 100L));
        }
    }

    @Test
    void eventWithoutStatusReturnsManualReview() {
        when(voucherOrderMapper.selectById(100L)).thenReturn(null);
        when(eventService.findByOrderId(100L))
                .thenReturn(eventOf(7L, 0).setStatus(null));

        assertEquals("MANUAL_REVIEW", queryStatus(100L));
        assertEquals("MANUAL_REVIEW", queryStatusFull(10L, 100L));
    }

    @Test
    void eventOfAnotherUserReturnsNotFound() {
        when(voucherOrderMapper.selectById(100L)).thenReturn(null);
        when(eventService.findByOrderId(100L))
                .thenReturn(eventOf(8L, SeckillOrderEvent.STATUS_PENDING));

        assertEquals("NOT_FOUND", queryStatus(100L));
        assertEquals("NOT_FOUND", queryStatusFull(10L, 100L));
    }

    @Test
    void eventQueryFailureReturnsUnavailable() {
        when(voucherOrderMapper.selectById(100L)).thenReturn(null);
        when(eventService.findByOrderId(100L))
                .thenThrow(new RuntimeException("db down"));

        assertEquals("UNAVAILABLE", queryStatus(100L));
        assertEquals("UNAVAILABLE", queryStatusFull(10L, 100L));
    }

    @Test
    void activeReservationWithoutEventReturnsProcessing() {
        when(voucherOrderMapper.selectById(100L)).thenReturn(null);
        when(eventService.findByOrderId(100L)).thenReturn(null);

        when(luaExecutor.findReservationEventId(10L, 100L))
                .thenReturn("event-9");
        when(luaExecutor.getReservationDetail(10L, "event-9"))
                .thenReturn("100|7|1700000000000|1");

        assertEquals("PROCESSING", queryStatusFull(10L, 100L));

        // orderId 反向索引直接定位，禁止再遍历 MySQL 秒杀券表
        verify(seckillVoucherService, never()).list(any());
    }

    @Test
    void reservationOfAnotherUserReturnsNotFound() {
        when(voucherOrderMapper.selectById(100L)).thenReturn(null);
        when(eventService.findByOrderId(100L)).thenReturn(null);

        when(luaExecutor.findReservationEventId(10L, 100L))
                .thenReturn("event-9");
        when(luaExecutor.getReservationDetail(10L, "event-9"))
                .thenReturn("100|8|1700000000000|1");

        assertEquals("NOT_FOUND", queryStatusFull(10L, 100L));
    }

    @Test
    void staleIndexWithoutDetailReturnsNotFound() {
        when(voucherOrderMapper.selectById(100L)).thenReturn(null);
        when(eventService.findByOrderId(100L)).thenReturn(null);

        // 索引残留但预留详情已被回滚/完成清理：按无预留处理
        when(luaExecutor.findReservationEventId(10L, 100L))
                .thenReturn("event-9");
        when(luaExecutor.getReservationDetail(10L, "event-9"))
                .thenReturn(null);

        assertEquals("NOT_FOUND", queryStatusFull(10L, 100L));
    }

    @Test
    void noEvidenceAnywhereReturnsNotFound() {
        when(voucherOrderMapper.selectById(100L)).thenReturn(null);
        when(eventService.findByOrderId(100L)).thenReturn(null);
        when(luaExecutor.findReservationEventId(10L, 100L))
                .thenReturn(null);

        assertEquals("NOT_FOUND", queryStatusFull(10L, 100L));
    }

    @Test
    void legacyWithoutVoucherIdReturnsUnavailable() {
        when(voucherOrderMapper.selectById(100L)).thenReturn(null);
        when(eventService.findByOrderId(100L)).thenReturn(null);

        // 旧版接口无法定位 Redis 预留：不能断言订单不存在
        assertEquals("UNAVAILABLE", queryStatus(100L));
    }

    @Test
    void reservationQueryFailureReturnsUnavailable() {
        when(voucherOrderMapper.selectById(100L)).thenReturn(null);
        when(eventService.findByOrderId(100L)).thenReturn(null);
        when(luaExecutor.findReservationEventId(10L, 100L))
                .thenThrow(new RuntimeException("redis down"));

        assertEquals("UNAVAILABLE", queryStatusFull(10L, 100L));
    }

    @Test
    void reservationDetailQueryFailureReturnsUnavailable() {
        when(voucherOrderMapper.selectById(100L)).thenReturn(null);
        when(eventService.findByOrderId(100L)).thenReturn(null);

        when(luaExecutor.findReservationEventId(10L, 100L))
                .thenReturn("event-9");
        when(luaExecutor.getReservationDetail(10L, "event-9"))
                .thenThrow(new RuntimeException("redis down"));

        assertEquals("UNAVAILABLE", queryStatusFull(10L, 100L));
    }
}
