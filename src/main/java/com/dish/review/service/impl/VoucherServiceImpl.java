package com.dish.review.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dish.review.dto.Result;
import com.dish.review.entity.Voucher;
import com.dish.review.mapper.VoucherMapper;
import com.dish.review.entity.SeckillVoucher;
import com.dish.review.service.ISeckillVoucherService;
import com.dish.review.service.IVoucherService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.dish.review.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author DishReview
 * @since 2021-12-22
 */

@Slf4j
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 查询优惠券信息
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 返回结果
        return Result.ok(vouchers);
    }

    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        // 保存优惠券
        save(voucher);
        // 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        seckillVoucherService.save(seckillVoucher);

        /*
         * 注册数据库事务提交回调。
         *
         * 此处不能立即初始化 Redis，因为当前 MySQL 事务尚未提交：
         * 如果先写入 Redis，随后数据库事务回滚，就会出现
         * “MySQL 中没有秒杀券，但 Redis 中存在可用库存”的不一致状态。
         *
         * 只有 MySQL 事务成功提交后才执行 afterCommit；
         * 如果事务发生回滚，afterCommit 不会执行，Redis 也不会写入错误库存。
         */
        Long voucherId = voucher.getId();
        Integer stock = voucher.getStock();

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        initializeRedisStock(voucherId, stock);
                    }
                }
        );
    }

    //新建秒杀优惠卷在Redis中的初始化
    private void initializeRedisStock(Long voucherId, Integer stock) {
        String hashTag = "{" + voucherId + "}";

        String stockKey =
                RedisConstants.SECKILL_STOCK_KEY + hashTag;

        String orderKey =
                RedisConstants.SECKILL_ORDER_KEY + hashTag;

        try {
            stringRedisTemplate.delete(orderKey);
            stringRedisTemplate.opsForValue().set(
                    stockKey,
                    stock.toString()
            );
        } catch (Exception e) {
            log.error(
                    "初始化 Redis 秒杀库存失败，voucherId={}",
                    voucherId,
                    e
            );
        }
    }
}
