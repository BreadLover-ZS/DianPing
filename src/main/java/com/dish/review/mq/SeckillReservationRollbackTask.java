package com.dish.review.mq;

import com.dish.review.entity.SeckillOrderEvent;
import com.dish.review.entity.VoucherOrder;
import com.dish.review.mapper.VoucherOrderMapper;
import com.dish.review.service.SeckillFailureCaseService;
import com.dish.review.service.SeckillOrderEventService;
import com.dish.review.service.SeckillVoucherLuaExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.List;
import java.util.UUID;

/**
 * 持久化回滚任务：扫描 ROLLBACK_PENDING 到期事件并执行 Redis 预留回滚（规格第 11 节）。
 *
 * <p>执行顺序：再次核对 MySQL 订单（存在则禁止回滚、收敛为 CONSUMED）→
 * CAS 抢占为 ROLLBACK_EXECUTING 并记录 fencing token → 调用按 eventId 校验的回滚 Lua →
 * 按返回值标记 ROLLED_BACK 或退避重试 → 耗尽上限转 MANUAL_REVIEW 并持久化失败记录。
 * 设置 ROLLBACK_EXECUTING 是为了避免“Lua 已恢复库存、数据库状态还未更新”时
 * 消费者并发创建订单；任务崩溃后由对账任务依据 Redis 预留是否仍存在安全收敛。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "dish-review.seckill.tasks-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class SeckillReservationRollbackTask {

    private final SeckillOrderEventService eventService;

    private final SeckillVoucherLuaExecutor luaExecutor;

    private final VoucherOrderMapper voucherOrderMapper;

    private final SeckillFailureCaseService failureCaseService;

    /**
     * 每轮扫描的最多事件数。
     */
    @Value("${dish-review.seckill.rollback.batch-size:20}")
    private int batchSize;

    /**
     * 当前实例的执行持有者标识。
     */
    private final String owner;

    /**
     * 注入事件服务、Lua 执行器、订单 Mapper 和失败记录服务。
     */
    public SeckillReservationRollbackTask(
            SeckillOrderEventService eventService,
            SeckillVoucherLuaExecutor luaExecutor,
            VoucherOrderMapper voucherOrderMapper,
            SeckillFailureCaseService failureCaseService) {
        this.eventService = eventService;
        this.luaExecutor = luaExecutor;
        this.voucherOrderMapper = voucherOrderMapper;
        this.failureCaseService = failureCaseService;
        this.owner = buildOwner();
    }

    /**
     * 周期扫描到期回滚事件；5 秒、30 秒、5 分钟、30 分钟退避，耗尽后转人工。
     */
    @Scheduled(
            fixedDelayString =
                    "${dish-review.seckill.rollback.scan-delay:5000}"
    )
    public void rollbackDueEvents() {
        List<SeckillOrderEvent> events;

        try {
            events = eventService.findDueForRollback(batchSize);
        } catch (Exception exception) {
            log.error("回滚任务扫描到期事件失败", exception);
            return;
        }

        for (SeckillOrderEvent event : events) {
            try {
                rollbackOneEvent(event);
            } catch (Exception exception) {
                log.error(
                        "回滚任务处理秒杀订单事件异常，eventId={}",
                        event.getEventId(),
                        exception
                );
            }
        }
    }

    /**
     * 单个事件的回滚流程：订单核对 → CAS 抢占 → Lua 回滚 → 收敛状态。
     */
    private void rollbackOneEvent(SeckillOrderEvent event) {
        String eventId = event.getEventId();

        // 1. 再次查询 MySQL 订单；存在则禁止回滚，收敛为 CONSUMED
        VoucherOrder existingOrder =
                voucherOrderMapper.selectById(event.getOrderId());

        if (existingOrder != null) {
            boolean marked = eventService.markConsumed(eventId);

            log.error(
                    "回滚前发现订单已创建，禁止回滚并收敛为 CONSUMED，"
                            + "eventId={}，orderId={}，marked={}",
                    eventId,
                    event.getOrderId(),
                    marked
            );

            return;
        }

        // 2. CAS 改为 ROLLBACK_EXECUTING 并记录执行令牌（防止多实例并发回滚）
        Long leaseToken = eventService.claimForRollback(eventId, owner);

        if (leaseToken == null) {
            // 其他实例已抢占，或订单核对后状态已变化
            return;
        }

        try {
            // 3. 调用按 eventId 校验的回滚 Lua
            Long result = luaExecutor.rollbackByEvent(
                    event.getVoucherId(),
                    event.getUserId(),
                    eventId,
                    event.getOrderId()
            );

            if (result == 1L || result == 0L) {
                // 4. Lua 返回 1（已恢复）或 0（幂等成功）：标记 ROLLED_BACK
                boolean marked = eventService.markRolledBack(
                        eventId,
                        leaseToken
                );

                if (marked) {
                    log.info(
                            "秒杀预留回滚完成，eventId={}，voucherId={}，"
                                    + "luaResult={}",
                            eventId,
                            event.getVoucherId(),
                            result
                    );
                } else {
                    // 执行令牌过期：状态已被对账任务收敛，按幂等处理
                    log.warn(
                            "秒杀预留回滚标记 ROLLED_BACK 未命中"
                                    + "（状态已被并发收敛），eventId={}",
                            eventId
                    );
                }

                return;
            }

            // 5. Lua 返回 -1（库存 Key 不存在）或 -2（事件冲突）：退避重试
            revertWithBackoff(
                    event,
                    leaseToken,
                    "rollback_lua_result_" + result,
                    "回滚 Lua 返回异常结果 " + result
                            + "（-1 库存 Key 不存在，-2 事件冲突）"
            );
        } catch (Exception luaException) {
            // 6. Lua 抛异常（Redis 不可用等）：恢复 ROLLBACK_PENDING 并退避重试
            revertWithBackoff(
                    event,
                    leaseToken,
                    "rollback_exception",
                    "回滚 Lua 执行异常: " + luaException.getMessage()
            );
        }
    }

    /**
     * 恢复 ROLLBACK_PENDING 并按退避重试；耗尽上限转 MANUAL_REVIEW
     * 并持久化失败记录（同一事务）。
     */
    private void revertWithBackoff(
            SeckillOrderEvent event,
            long leaseToken,
            String errorCode,
            String errorMessage) {

        int status = failureCaseService.recordRollbackRevert(
                event,
                leaseToken,
                errorCode,
                errorMessage
        );

        if (status == SeckillOrderEvent.STATUS_MANUAL_REVIEW) {
            log.error(
                    "秒杀预留回滚重试耗尽，转 MANUAL_REVIEW 并持久化失败记录，"
                            + "eventId={}，errorCode={}",
                    event.getEventId(),
                    errorCode
            );
        } else {
            log.warn(
                    "秒杀预留回滚失败，恢复 ROLLBACK_PENDING 等待退避重试，"
                            + "eventId={}，errorCode={}，errorMessage={}",
                    event.getEventId(),
                    errorCode,
                    errorMessage
            );
        }
    }

    /**
     * 生成实例唯一的执行持有者标识：主机名 + 随机后缀。
     */
    private String buildOwner() {
        String hostName;

        try {
            hostName = InetAddress.getLocalHost().getHostName();
        } catch (Exception exception) {
            hostName = "unknown-host";
        }

        return hostName + ":rollback:" + UUID.randomUUID();
    }
}
