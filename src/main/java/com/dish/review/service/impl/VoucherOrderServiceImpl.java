package com.dish.review.service.impl;

import com.dish.review.dto.Result;
import com.dish.review.entity.SeckillVoucher;
import com.dish.review.entity.VoucherOrder;
import com.dish.review.mapper.VoucherOrderMapper;
import com.dish.review.service.ISeckillVoucherService;
import com.dish.review.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dish.review.utils.RedisIdWorker;
import com.dish.review.utils.SimpleRedisLock;
import com.dish.review.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author DishReview
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Override
    public Result seckillVoucher(Long voucherId) {
        //1.查询优惠卷
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

        //2.判断，秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            //3.未开始，返回异常
            return Result.fail("秒杀尚未开始！");
        }

        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已结束！");
        }

        //4.已开始，判断库存是否充足
        if (voucher.getStock() <= 0) {
            //5.不足，返回异常
            return Result.fail("库存不足！");
        }

        Long userId = UserHolder.getUser().getId();

        //获取锁（同一用户同时发送多个购买请求，只有一个请求可以获得锁）
        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);

        boolean isLocked = lock.tryLock(1200L);
        if (!isLocked) {
            return Result.fail("请勿重复下单！");
        }
        try{
            //获取事务代理对象，使事务生效:如果直接调用该方法，是当前的实例对象去调用，不是事务代理对象，导致事务不生效
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } catch (Exception e) {
            return Result.fail("下单失败！");
        } finally {
            lock.unlock();
        }
    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {

        Long userId = UserHolder.getUser().getId();

        //一人一单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("用户已下过订单！");
        }

        //6.充足，扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();

        if (!success) {
            //6.1失败，返回异常
            return Result.fail("库存不足！");
        }

        //7.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(UserHolder.getUser().getId());
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        //8.返回订单id
        return Result.ok(orderId);

    }
}
