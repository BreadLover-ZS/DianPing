package com.dish.review.controller;


import com.dish.review.dto.Result;
import com.dish.review.service.IVoucherOrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author DishReview
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {

        return voucherOrderService.seckillVoucher(voucherId);
    }

    /**
     * 查询当前用户秒杀订单的处理状态（规格第 15 节，完整版）。
     *
     * <p>voucherId 用于定位 Redis orderId 反向索引（券维度 Hash），
     * 可覆盖“事件写入失败、等待对账补建”窗口期的预留查询。
     * 返回 data 为状态字符串：PROCESSING、SUCCESS、FAILED、
     * MANUAL_REVIEW、NOT_FOUND 或 UNAVAILABLE。</p>
     */
    @GetMapping("status/{voucherId}/{orderId}")
    public Result queryOrderStatus(
            @PathVariable("voucherId") Long voucherId,
            @PathVariable("orderId") Long orderId) {
        return voucherOrderService.queryOrderStatus(voucherId, orderId);
    }

    /**
     * 查询当前用户秒杀订单的处理状态（兼容旧版，无 voucherId）。
     *
     * <p>只查询 MySQL 订单和事件；两者都查不到时无法定位 Redis 预留，
     * 返回 UNAVAILABLE 而不是 NOT_FOUND，引导调用方改用完整版接口
     * {@code GET /voucher-order/status/{voucherId}/{orderId}}。</p>
     */
    @GetMapping("status/{orderId}")
    public Result queryOrderStatusLegacy(
            @PathVariable("orderId") Long orderId) {
        return voucherOrderService.queryOrderStatus(orderId);
    }
}
