package com.dish.review.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.dish.review.dto.SeckillOrderMessage;
import com.dish.review.entity.SeckillOrderEvent;
import com.dish.review.entity.SeckillVoucher;
import com.dish.review.entity.VoucherOrder;
import com.dish.review.exception.SeckillConsistencyException;
import com.dish.review.exception.SeckillRetryableException;
import com.dish.review.mapper.SeckillVoucherMapper;
import com.dish.review.mapper.VoucherOrderMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 秒杀订单消费事务处理器（规格第 10 节）。
 *
 * <p>事务内先锁定事件行并检查状态机，再条件扣减 MySQL 库存、写订单、
 * 标记事件 CONSUMED，四步同成同败。事务提交后由监听容器发送 ACK；
 * 提交后 ACK 丢失触发的重投依靠事件状态和唯一索引转为幂等成功。</p>
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
     * 幂等创建订单：锁定事件行 → 状态分支 → 查已有订单 → 条件扣库存 → 写订单 → 标记 CONSUMED。
     */
    @Transactional
    public void createOrder(SeckillOrderMessage message) {
        // 1. 事务内锁定事件行，串行化同一事件的并发消费
        SeckillOrderEvent event = eventService.lockEvent(message.getEventId());

        if (event == null) {
            throw new SeckillConsistencyException(
                    "秒杀订单事件不存在，禁止创建订单，eventId="
                            + message.getEventId()
            );
        }

        int status = event.getStatus();

        // 2. CONSUMED：幂等返回（事务提交后 ACK 丢失触发的重投）
        if (status == SeckillOrderEvent.STATUS_CONSUMED) {
            return;
        }

        // 3. ROLLED_BACK：预留已恢复，迟到消息禁止创建订单，转人工核对
        if (status == SeckillOrderEvent.STATUS_ROLLED_BACK) {
            throw new SeckillConsistencyException(
                    "迟到消息：事件已回滚，禁止创建订单，eventId="
                            + message.getEventId()
            );
        }

        // 4. ROLLBACK_EXECUTING：回滚 Lua 可能已恢复库存，等待任务结束后按结果处理
        if (status == SeckillOrderEvent.STATUS_ROLLBACK_EXECUTING) {
            throw new SeckillRetryableException(
                    "回滚执行中，等待回滚任务收敛，eventId="
                            + message.getEventId()
            );
        }

        // 5. ROLLBACK_PENDING：先 CAS 取消回滚，成功后才允许创建订单
        if (status == SeckillOrderEvent.STATUS_ROLLBACK_PENDING) {
            boolean cancelled = eventService.cancelRollback(
                    message.getEventId()
            );

            if (!cancelled) {
                throw new SeckillRetryableException(
                        "取消回滚失败（回滚任务并发抢占），稍后重试，eventId="
                                + message.getEventId()
                );
            }
        } else if (!isConsumableStatus(status)) {
            // MANUAL_REVIEW、历史 FAILED 等状态：等待人工决策，禁止自动创建订单
            throw new SeckillConsistencyException(
                    "事件状态不允许自动创建订单，eventId=" + message.getEventId()
                            + "，status=" + status
            );
        }

        // 6. 查询已有订单；存在则标记 CONSUMED（重投幂等）
        Integer count = voucherOrderMapper.selectCount(
                orderByUserAndVoucher(message)
        );

        if (count != null && count > 0) {
            markEventConsumed(message);
            return;
        }

        // 7. 条件扣减 MySQL 库存
        UpdateWrapper<SeckillVoucher> stockUpdate = new UpdateWrapper<>();

        stockUpdate
                .setSql("stock = stock - 1")
                .eq("voucher_id", message.getVoucherId())
                .gt("stock", 0);

        int updatedRows = seckillVoucherMapper.update(null, stockUpdate);

        if (updatedRows != 1) {
            // Redis 预留成功但 MySQL 库存不足：数据不一致，不是瞬时故障
            throw new SeckillConsistencyException(
                    "数据库秒杀库存不足（Redis/MySQL 不一致），voucherId="
                            + message.getVoucherId()
            );
        }

        // 8. 写订单（orderId 前置生成，主键冲突由唯一索引防御）
        VoucherOrder voucherOrder = new VoucherOrder();

        voucherOrder.setId(message.getOrderId());
        voucherOrder.setUserId(message.getUserId());
        voucherOrder.setVoucherId(message.getVoucherId());

        int insertedRows = voucherOrderMapper.insert(voucherOrder);

        if (insertedRows != 1) {
            throw new SeckillRetryableException(
                    "秒杀订单写入失败，eventId=" + message.getEventId()
            );
        }

        // 9. 标记事件 CONSUMED，与订单同事务提交
        markEventConsumed(message);
    }

    /**
     * 判断事件状态是否允许消费事务自动创建订单。
     * DLQ 状态允许：死信被重放到主队列时按状态机 DLQ → CONSUMED 收敛。
     */
    private boolean isConsumableStatus(int status) {
        return status == SeckillOrderEvent.STATUS_PENDING
                || status == SeckillOrderEvent.STATUS_CONFIRMED
                || status == SeckillOrderEvent.STATUS_PUBLISH_UNKNOWN
                || status == SeckillOrderEvent.STATUS_DLQ;
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

        return count != null && count > 0;
    }

    /**
     * 在消费事务内标记事件完成；失败时抛一致性异常使订单和库存一起回滚。
     */
    private void markEventConsumed(
            SeckillOrderMessage message) {

        boolean marked = eventService.markConsumed(
                message.getEventId()
        );

        if (!marked) {
            throw new SeckillConsistencyException(
                    "秒杀订单事件无法标记为 CONSUMED（状态来源不合法），eventId="
                            + message.getEventId()
            );
        }
    }
}
