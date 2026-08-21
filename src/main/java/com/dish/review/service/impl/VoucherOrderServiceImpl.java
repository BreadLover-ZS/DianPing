package com.dish.review.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dish.review.dto.Result;
import com.dish.review.dto.SeckillOrderMessage;
import com.dish.review.dto.UserDTO;
import com.dish.review.entity.SeckillOrderEvent;
import com.dish.review.entity.SeckillVoucher;
import com.dish.review.entity.VoucherOrder;
import com.dish.review.mapper.VoucherOrderMapper;
import com.dish.review.service.ISeckillVoucherService;
import com.dish.review.service.IVoucherOrderService;
import com.dish.review.service.SeckillOrderEventService;
import com.dish.review.service.SeckillVoucherLuaExecutor;
import com.dish.review.utils.RedisIdWorker;
import com.dish.review.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 秒杀订单服务。
 *
 * <p>请求线程只负责快速校验、Redis 原子预扣（含预留账本）和尽力写入 PENDING 事件；
 * RabbitMQ 发布统一由 Outbox 任务完成，MySQL 库存扣减与订单写入由消费者异步完成。</p>
 *
 * <p>事件写入失败时禁止直接回滚 Redis 预留：对账任务会依据预留账本幂等补建事件。
 * 接口返回值表示“已经受理”，不代表订单已经落库。</p>
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl
        extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {

    /**
     * 当前消息结构版本。
     */
    private static final int MESSAGE_VERSION = 1;

    /**
     * 订单状态查询返回值（规格第 15 节）。
     */
    private static final String ORDER_STATUS_PROCESSING = "PROCESSING";

    private static final String ORDER_STATUS_SUCCESS = "SUCCESS";

    private static final String ORDER_STATUS_FAILED = "FAILED";

    private static final String ORDER_STATUS_MANUAL_REVIEW = "MANUAL_REVIEW";

    private static final String ORDER_STATUS_NOT_FOUND = "NOT_FOUND";

    private static final String ORDER_STATUS_UNAVAILABLE = "UNAVAILABLE";

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private SeckillVoucherLuaExecutor luaExecutor;

    @Resource
    private SeckillOrderEventService eventService;

    /**
     * 秒杀入口：校验活动、Lua 预扣并写预留账本、尽力落 PENDING 事件后立即返回受理结果。
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        if (voucherId == null || voucherId <= 0) {
            return Result.fail("优惠券参数错误");
        }

        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            return Result.fail("请先登录");
        }

        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if (voucher == null) {
            return Result.fail("秒杀券不存在");
        }

        LocalDateTime now = LocalDateTime.now();
        if (voucher.getBeginTime() != null
                && voucher.getBeginTime().isAfter(now)) {
            return Result.fail("秒杀尚未开始！");
        }

        if (voucher.getEndTime() != null
                && voucher.getEndTime().isBefore(now)) {
            return Result.fail("秒杀已结束！");
        }

        /*
         * ID 前置：eventId/orderId 必须在 Lua 执行前生成。
         * 后续所有重发、补偿和消费都复用这一组业务标识，禁止生成新 ID。
         */
        String eventId = UUID.randomUUID().toString();
        Long orderId = redisIdWorker.nextId("order");
        long createdAt = System.currentTimeMillis();

        Long reserveResult;
        try {
            reserveResult = luaExecutor.reserve(
                    voucherId,
                    user.getId(),
                    eventId,
                    orderId,
                    createdAt,
                    MESSAGE_VERSION
            );
        } catch (Exception exception) {
            /*
             * Redis 调用超时时结果未知：预扣可能已经生效，也可能没有执行。
             * 尽力查询用户事件映射判断，禁止直接描述为确定失败。
             */
            return handleUnknownReserve(
                    voucherId,
                    user.getId(),
                    orderId,
                    exception
            );
        }

        if (Long.valueOf(1L).equals(reserveResult)) {
            return Result.fail("库存不足！");
        }

        if (Long.valueOf(2L).equals(reserveResult)) {
            return Result.fail("请勿重复下单！");
        }

        if (Long.valueOf(3L).equals(reserveResult)) {
            return Result.fail("秒杀库存尚未初始化");
        }

        if (!Long.valueOf(0L).equals(reserveResult)) {
            return Result.fail("秒杀失败，请稍后重试");
        }

        /*
         * 尽力创建 PENDING 事件。
         * 写入抛技术异常时禁止回滚 Redis 预留：
         * 预留账本中已经保存了重建事件所需的全部信息，对账任务会幂等补建。
         */
        SeckillOrderMessage message = new SeckillOrderMessage(
                eventId,
                orderId,
                user.getId(),
                voucherId,
                createdAt,
                MESSAGE_VERSION
        );

        try {
            eventService.createPending(message);
        } catch (Exception exception) {
            log.error(
                    "秒杀订单 PENDING 事件写入失败，等待对账任务依据预留账本恢复，"
                            + "eventId={}，orderId={}，voucherId={}，userId={}",
                    eventId,
                    orderId,
                    voucherId,
                    user.getId(),
                    exception
            );
            // 预留已成功，仍按已受理返回
        }

        // 预留成功即受理；发布由 Outbox 任务统一执行。
        // 受理结果携带 orderId + voucherId：前端可用其查询订单状态
        return Result.ok(buildAcceptResult(voucherId, orderId));
    }

    /**
     * 保留接口兼容方法。新的秒杀请求统一走 seckillVoucher() 的异步链路。
     */
    @Override
    @Deprecated
    public Result createVoucherOrder(Long voucherId) {
        return seckillVoucher(voucherId);
    }

    /**
     * 查询当前用户秒杀订单的处理状态（规格第 15 节，带 voucherId 的完整版）。
     *
     * <p>查询顺序：MySQL 订单 → 事件 → Redis 预留（orderId 反向索引）。
     * 只有所有数据源都查询成功且确实没有记录时才返回 NOT_FOUND；
     * 任何数据源查询失败返回 UNAVAILABLE，禁止把技术故障伪装成“订单不存在”。
     * 只能查询当前用户自己的订单。</p>
     */
    @Override
    public Result queryOrderStatus(Long voucherId, Long orderId) {
        if (voucherId == null || voucherId <= 0) {
            return Result.fail("优惠券参数错误");
        }

        return queryOrderStatusInternal(voucherId, orderId);
    }

    /**
     * 查询当前用户秒杀订单的处理状态（兼容旧版，无 voucherId）。
     *
     * <p>只查询 MySQL 订单和事件；两者都查不到时无法定位 Redis 预留，
     * 返回 UNAVAILABLE 而不是 NOT_FOUND，引导调用方改用带 voucherId 的完整版。</p>
     */
    @Override
    public Result queryOrderStatus(Long orderId) {
        return queryOrderStatusInternal(null, orderId);
    }

    /**
     * 状态查询公共实现：voucherId 为空时不查 Redis 预留，
     * 终态返回 UNAVAILABLE（无法排除预留窗口期）；否则完整三层数据源。
     */
    private Result queryOrderStatusInternal(Long voucherId, Long orderId) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            return Result.fail("请先登录");
        }

        if (orderId == null || orderId <= 0) {
            return Result.fail("订单参数错误");
        }

        // 1. 优先查询 MySQL 订单
        VoucherOrder order;
        try {
            order = getById(orderId);
        } catch (Exception exception) {
            log.error(
                    "订单状态查询：MySQL 订单查询失败，orderId={}",
                    orderId,
                    exception
            );
            return Result.ok(ORDER_STATUS_UNAVAILABLE);
        }

        if (order != null) {
            // 只能查询自己的订单；他人订单按不存在处理，不泄露存在性
            return user.getId().equals(order.getUserId())
                    ? Result.ok(ORDER_STATUS_SUCCESS)
                    : Result.ok(ORDER_STATUS_NOT_FOUND);
        }

        // 2. 其次查询事件状态
        SeckillOrderEvent event;
        try {
            event = eventService.findByOrderId(orderId);
        } catch (Exception exception) {
            log.error(
                    "订单状态查询：事件查询失败，orderId={}",
                    orderId,
                    exception
            );
            return Result.ok(ORDER_STATUS_UNAVAILABLE);
        }

        if (event != null) {
            if (!user.getId().equals(event.getUserId())) {
                return Result.ok(ORDER_STATUS_NOT_FOUND);
            }

            return Result.ok(resolveEventStatus(event.getStatus()));
        }

        // 3. 带 voucherId 时查询 Redis 预留（事件写入失败、等待对账补建的窗口期）
        if (voucherId != null) {
            try {
                if (hasActiveReservation(user.getId(), voucherId, orderId)) {
                    return Result.ok(ORDER_STATUS_PROCESSING);
                }
            } catch (Exception exception) {
                log.error(
                        "订单状态查询：Redis 预留查询失败，orderId={}，"
                                + "voucherId={}，userId={}",
                        orderId,
                        voucherId,
                        user.getId(),
                        exception
                );
                return Result.ok(ORDER_STATUS_UNAVAILABLE);
            }

            // 订单、事件、预留证据均不存在，且各数据源查询成功
            return Result.ok(ORDER_STATUS_NOT_FOUND);
        }

        // 4. 旧版接口没有 voucherId，无法定位 Redis 预留：
        //    不能断言订单不存在，返回 UNAVAILABLE 引导调用方使用完整版接口
        return Result.ok(ORDER_STATUS_UNAVAILABLE);
    }

    /**
     * 把事件状态映射为用户可读的订单状态。
     *
     * <p>PENDING/CONFIRMED/PUBLISH_UNKNOWN 表示发布中；
     * ROLLBACK_PENDING/ROLLBACK_EXECUTING 表示回滚尚未完成（仍可能被消费事务取消回滚）；
     * 旧 FAILED 与未知状态一律按人工核对处理，禁止自动判定成功或失败。</p>
     */
    private String resolveEventStatus(Integer status) {
        if (status == null) {
            return ORDER_STATUS_MANUAL_REVIEW;
        }

        if (status == SeckillOrderEvent.STATUS_CONSUMED) {
            return ORDER_STATUS_SUCCESS;
        }

        if (status == SeckillOrderEvent.STATUS_ROLLED_BACK) {
            return ORDER_STATUS_FAILED;
        }

        if (status == SeckillOrderEvent.STATUS_PENDING
                || status == SeckillOrderEvent.STATUS_CONFIRMED
                || status == SeckillOrderEvent.STATUS_PUBLISH_UNKNOWN
                || status == SeckillOrderEvent.STATUS_ROLLBACK_PENDING
                || status == SeckillOrderEvent.STATUS_ROLLBACK_EXECUTING) {
            return ORDER_STATUS_PROCESSING;
        }

        // MANUAL_REVIEW、DLQ、旧 FAILED、未知状态：自动流程无法判断
        return ORDER_STATUS_MANUAL_REVIEW;
    }

    /**
     * 按 orderId 反向索引判断预留是否仍未收敛。
     *
     * <p>索引是券维度 Hash（field=orderId，value=eventId），与预留账本同槽，
     * 由预留 Lua 原子写入、回滚/完成 Lua 原子删除。查询不依赖秒杀券的活动
     * 时间范围：活动已结束的未收敛预留同样能定位，避免误报 NOT_FOUND。</p>
     */
    private boolean hasActiveReservation(
            Long userId,
            Long voucherId,
            Long orderId) {

        String eventId = luaExecutor.findReservationEventId(
                voucherId,
                orderId
        );

        if (eventId == null || eventId.trim().isEmpty()) {
            return false;
        }

        String detail = luaExecutor.getReservationDetail(voucherId, eventId);

        // 预留详情已清理（回滚/完成已执行），索引残留按无预留处理
        if (detail == null || detail.trim().isEmpty()) {
            return false;
        }

        // 详情格式 orderId|userId|createdAt|messageVersion；校验归属
        String[] fields = detail.split("\\|");
        return fields.length >= 2
                && userId.toString().equals(fields[1]);
    }

    /**
     * Redis 预扣结果未知时的降级处理。
     *
     * <p>查询到用户事件映射说明预留已经存在，返回其原 orderId（处理中语义）；
     * 查询确认无映射说明本次预扣未生效，允许用户重试；
     * Redis 整体不可用时返回受理结果，由用户稍后通过订单状态接口确认，
     * 不把它描述成确定失败。</p>
     */
    private Result handleUnknownReserve(
            Long voucherId,
            Long userId,
            Long orderId,
            Exception cause) {

        log.error(
                "Redis 秒杀预扣执行异常，结果未知，voucherId={}，userId={}，orderId={}",
                voucherId,
                userId,
                orderId,
                cause
        );

        try {
            String mappedEventId =
                    luaExecutor.getUserEventMapping(voucherId, userId);

            if (mappedEventId != null) {
                String detail = luaExecutor.getReservationDetail(
                        voucherId,
                        mappedEventId
                );

                Long originalOrderId = parseOrderId(detail);

                return Result.ok(buildAcceptResult(
                        voucherId,
                        originalOrderId != null ? originalOrderId : orderId
                ));
            }

            // 映射不存在：本次预扣未生效，用户可以安全重试
            return Result.fail("系统繁忙，请稍后重试");
        } catch (Exception lookupException) {
            log.error(
                    "查询 Redis 预留映射失败，返回请求结果确认中，"
                            + "voucherId={}，userId={}，orderId={}",
                    voucherId,
                    userId,
                    orderId,
                    lookupException
            );

            // Redis 不可用：无法判断，按“结果确认中”返回
            return Result.ok(buildAcceptResult(voucherId, orderId));
        }
    }

    /**
     * 构造秒杀受理结果（orderId + voucherId）。
     *
     * <p>orderId 用于订单状态查询；voucherId 用于定位 Redis orderId
     * 反向索引（券维度 Hash），调用带 voucherId 的完整版状态接口。</p>
     */
    private java.util.Map<String, Long> buildAcceptResult(
            Long voucherId,
            Long orderId) {

        java.util.Map<String, Long> accept = new java.util.LinkedHashMap<>();
        accept.put("orderId", orderId);
        accept.put("voucherId", voucherId);
        return accept;
    }

    /**
     * 从预留详情（orderId|userId|createdAt|messageVersion）解析原订单 ID。
     */
    private Long parseOrderId(String reservationDetail) {
        if (reservationDetail == null
                || reservationDetail.trim().isEmpty()) {
            return null;
        }

        String[] parts = reservationDetail.split("\\|");

        if (parts.length == 0) {
            return null;
        }

        try {
            return Long.valueOf(parts[0]);
        } catch (NumberFormatException exception) {
            log.warn(
                    "Redis 预留详情格式异常，无法解析 orderId，detail={}",
                    reservationDetail
            );
            return null;
        }
    }
}
