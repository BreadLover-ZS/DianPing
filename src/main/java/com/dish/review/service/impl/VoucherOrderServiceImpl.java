package com.dish.review.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dish.review.dto.Result;
import com.dish.review.dto.SeckillOrderMessage;
import com.dish.review.dto.UserDTO;
import com.dish.review.entity.SeckillVoucher;
import com.dish.review.entity.VoucherOrder;
import com.dish.review.mapper.VoucherOrderMapper;
import com.dish.review.mq.SeckillOrderPublisher;
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
 * <p>请求线程只负责快速校验、Redis 原子预扣和消息投递；
 * MySQL 库存扣减与订单写入由 RabbitMQ 消费者异步完成。</p>
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl
        extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private SeckillVoucherLuaExecutor luaExecutor;

    @Resource
    private SeckillOrderEventService eventService;

    @Resource
    private SeckillOrderPublisher orderPublisher;

    /**
     * 秒杀入口：校验活动、Lua 预扣、事件落库并发布 RabbitMQ 消息。
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        //初步判断该用户能否下单该优惠券
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

        //执行lua脚本：预扣除库存，并且将用户加入到已购用户集合
        //这里可以保证，用户只能下单一次
        Long reserveResult;
        try {
            reserveResult = luaExecutor.reserve(voucherId, user.getId());
        } catch (Exception exception) {
            log.error(
                    "Redis 秒杀预扣执行异常，voucherId={}，userId={}",
                    voucherId,
                    user.getId(),
                    exception
            );
            return Result.fail("系统繁忙，请稍后重试");
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

        //通过redis获取分布式ID
        Long orderId = redisIdWorker.nextId("order");
        SeckillOrderMessage message = new SeckillOrderMessage(
                UUID.randomUUID().toString(),
                orderId,
                user.getId(),
                voucherId,
                System.currentTimeMillis(),
                1
        );

        //创建对应的event数据，记录订单的状态，用于后续回调
        try {
            eventService.createPending(message);
        } catch (Exception exception) {
            rollbackReservation(message, "event_persist_failed");
            log.error(
                    "秒杀订单 PENDING 事件写入失败，eventId={}",
                    message.getEventId(),
                    exception
            );
            return Result.fail("下单失败，请稍后重试");
        }

        //生产消息：发送给消息队列，异步处理
        try {
            orderPublisher.publish(message);
        } catch (Exception exception) {
            /**
             * 发送消息抛异常
             *   → 不能确定消息是否已经到达 RabbitMQ
             *   → 不立即恢复 Redis 库存
             *   → 根据 eventId 安排事件表补偿重试
             *   → 记录执行结果
             *   → 方法继续执行
             */

            /**补偿任务：scheduleRetry
             * 处理：Redis 已经预扣、PENDING 事件已经落库，但生产者无法确认消息是否成功发送到 RabbitMQ。
             * 过程：扫描到期的 PENDING 事件
             *   → 抢占事件并设置 30 秒租约
             *   → 用事件表数据重建原消息
             *   → 使用原 eventId/orderId 重新发布
             *   → 等待 Confirm 回调更新状态
             */
            boolean scheduled = eventService.scheduleRetry(
                    message.getEventId(),
                    exception.getMessage()
            );

            if (!scheduled) {
                log.error(
                        "秒杀订单发布异常，且补偿任务未能安排，eventId={}",
                        message.getEventId(),
                        exception
                );
            } else {
                log.warn(
                        "秒杀订单发布结果未知，已安排补偿重试，eventId={}",
                        message.getEventId(),
                        exception
                );
            }
        }

        return Result.ok(orderId);
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
     * 事件落库失败时撤销 Redis 预扣；回滚脚本本身具备幂等性。
     */
    private void rollbackReservation(
            SeckillOrderMessage message,
            String reason) {
        try {
            Long result = luaExecutor.rollback(
                    message.getVoucherId(),
                    message.getUserId()
            );

            if (!Long.valueOf(1L).equals(result)
                    && !Long.valueOf(0L).equals(result)) {
                log.error(
                        "Redis 秒杀预扣回滚未成功，eventId={}，result={}，reason={}",
                        message.getEventId(),
                        result,
                        reason
                );
            }
        } catch (Exception exception) {
            log.error(
                    "Redis 秒杀预扣回滚异常，eventId={}，reason={}",
                    message.getEventId(),
                    reason,
                    exception
            );
        }
    }
}
