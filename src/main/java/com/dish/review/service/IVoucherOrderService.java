package com.dish.review.service;

import com.dish.review.dto.Result;
import com.dish.review.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author DishReview
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillVoucher(Long voucherId);

    Result createVoucherOrder(Long voucherId);

    /**
     * 查询当前用户秒杀订单的处理状态（规格第 15 节，带 voucherId 的完整版）。
     *
     * <p>voucherId 用于定位 Redis orderId 反向索引（券维度 Hash），
     * 可覆盖“事件写入失败、等待对账补建”窗口期的预留查询。</p>
     *
     * @return data 为状态字符串：PROCESSING、SUCCESS、FAILED、
     * MANUAL_REVIEW、NOT_FOUND 或 UNAVAILABLE
     */
    Result queryOrderStatus(Long voucherId, Long orderId);

    /**
     * 查询当前用户秒杀订单的处理状态（兼容旧版，无 voucherId）。
     *
     * <p>只查询 MySQL 订单和事件；两者都查不到时无法定位 Redis 预留，
     * 返回 UNAVAILABLE 而不是 NOT_FOUND，引导调用方改用带 voucherId 的完整版。</p>
     *
     * @return data 为状态字符串：PROCESSING、SUCCESS、FAILED、
     * MANUAL_REVIEW、NOT_FOUND 或 UNAVAILABLE
     */
    Result queryOrderStatus(Long orderId);
}
