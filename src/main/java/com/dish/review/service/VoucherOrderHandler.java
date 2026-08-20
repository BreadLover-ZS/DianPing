package com.dish.review.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.dish.review.dto.SeckillOrderMessage;
import com.dish.review.entity.SeckillVoucher;
import com.dish.review.entity.VoucherOrder;
import com.dish.review.mapper.SeckillVoucherMapper;
import com.dish.review.mapper.VoucherOrderMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


/**
 * 秒杀订单消费事务处理器，保证数据库扣库存、写订单和事件完成同成同败。
 */
@Service
public class VoucherOrderHandler {
    private final VoucherOrderMapper voucherOrderMapper;
    private final SeckillVoucherMapper seckillVoucherMapper;
    private final SeckillOrderEventService eventService;

    /**
     * 注入订单、秒杀库存和事件表的数据访问组件。
     */
    public VoucherOrderHandler(
            VoucherOrderMapper voucherOrderMapper,
            SeckillVoucherMapper seckillVoucherMapper,
            SeckillOrderEventService eventService) {
        this.voucherOrderMapper = voucherOrderMapper;
        this.seckillVoucherMapper = seckillVoucherMapper;
        this.eventService = eventService;
    }

    /**
     * 幂等创建订单：已有订单直接完成事件，否则条件扣库存后写入订单。
     */
    @Transactional
    public void createOrder(SeckillOrderMessage message) {
        Integer count = voucherOrderMapper.selectCount(orderByUserAndVoucher(message));

        if (count > 0) {
            markEventConsumed(message);
            return;
        }

        UpdateWrapper<SeckillVoucher> stockUpdate = new UpdateWrapper<>();

        stockUpdate
                .setSql("stock = stock - 1")
                .eq("voucher_id", message.getVoucherId())
                .gt("stock", 0);

        int updatedRows = seckillVoucherMapper.update(null, stockUpdate);

        if (updatedRows != 1) {
            throw new IllegalStateException(
                    "数据库秒杀库存不足，voucherId=" + message.getVoucherId()
            );
        }

        VoucherOrder voucherOrder = new VoucherOrder();

        voucherOrder.setId(message.getOrderId());
        voucherOrder.setUserId(message.getUserId());
        voucherOrder.setVoucherId(message.getVoucherId());

        int insertedRows = voucherOrderMapper.insert(voucherOrder);

        if (insertedRows != 1) {
            throw new IllegalStateException(
                    "秒杀订单写入失败，eventId=" + message.getEventId()
            );
        }

        markEventConsumed(message);

    }

    /**
     * 构造“一名用户对一张券”的订单查询条件。
     */
    private QueryWrapper<VoucherOrder> orderByUserAndVoucher(
            SeckillOrderMessage message) {
        QueryWrapper<VoucherOrder> queryWrapper = new QueryWrapper<>();

        queryWrapper
                .eq("user_id", message.getUserId())
                .eq("voucher_id", message.getVoucherId());

        return queryWrapper;
    }

    /**
     * 判断业务订单是否已存在，用于把重复键异常识别为幂等成功。
     */
    public boolean orderAlreadyExists(SeckillOrderMessage message) {
        Integer count = voucherOrderMapper.selectCount(
                orderByUserAndVoucher(message)
        );

        return count > 0;
    }

    /**
     * 在消费事务内标记事件完成；失败时抛异常使订单和库存一起回滚。
     */
    private void markEventConsumed(
            SeckillOrderMessage message) {

        boolean marked = eventService.markConsumed(
                message.getEventId()
        );

        if (!marked) {
            throw new IllegalStateException(
                    "秒杀订单事件无法标记为 CONSUMED，eventId="
                            + message.getEventId()
            );
        }
    }
}
