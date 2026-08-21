package com.dish.review.service;

import com.dish.review.utils.RedisConstants;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 执行秒杀 Redis Lua，提供原子预留、事件级回滚、预留完成和安全初始化能力。
 *
 * <p>同一秒杀券的六个 Key（含 orderId 反向索引 Hash）使用相同 Hash Tag，
 * 保证 Redis Cluster 单槽原子性。</p>
 */
@Component
public class SeckillVoucherLuaExecutor {

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    private static final DefaultRedisScript<Long> SECKILL_ROLLBACK_SCRIPT;

    private static final DefaultRedisScript<Long> SECKILL_RESERVATION_COMPLETE_SCRIPT;

    private static final DefaultRedisScript<Long> SECKILL_STOCK_INIT_SCRIPT;

    private static final DefaultRedisScript<Long> SECKILL_RESERVATION_MANUAL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);

        SECKILL_ROLLBACK_SCRIPT = new DefaultRedisScript<>();
        SECKILL_ROLLBACK_SCRIPT.setLocation(
                new ClassPathResource("seckill_rollback.lua")
        );
        SECKILL_ROLLBACK_SCRIPT.setResultType(Long.class);

        SECKILL_RESERVATION_COMPLETE_SCRIPT = new DefaultRedisScript<>();
        SECKILL_RESERVATION_COMPLETE_SCRIPT.setLocation(
                new ClassPathResource("seckill_reservation_complete.lua")
        );
        SECKILL_RESERVATION_COMPLETE_SCRIPT.setResultType(Long.class);

        SECKILL_STOCK_INIT_SCRIPT = new DefaultRedisScript<>();
        SECKILL_STOCK_INIT_SCRIPT.setLocation(
                new ClassPathResource("seckill_stock_init.lua")
        );
        SECKILL_STOCK_INIT_SCRIPT.setResultType(Long.class);

        SECKILL_RESERVATION_MANUAL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_RESERVATION_MANUAL_SCRIPT.setLocation(
                new ClassPathResource("seckill_reservation_manual.lua")
        );
        SECKILL_RESERVATION_MANUAL_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 注入 Redis 模板；Lua 脚本在类初始化时从 classpath 加载一次。
     */
    public SeckillVoucherLuaExecutor(
            StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 构造同一 Hash Tag 下的六个同槽 Key，兼容 Redis Cluster。
     *
     * <p>orderId 反向索引是券维度 Hash（field=orderId，value=eventId），
     * 与预留账本其余五个 Key 同槽，Lua 脚本原子维护。</p>
     */
    private List<String> buildKeys(Long voucherId) {
        String hashTag = "{" + voucherId + "}";

        return Arrays.asList(
                RedisConstants.SECKILL_STOCK_KEY + hashTag,
                RedisConstants.SECKILL_ORDER_KEY + hashTag,
                RedisConstants.SECKILL_RESERVATION_KEY + hashTag,
                RedisConstants.SECKILL_RESERVATION_USER_KEY + hashTag,
                RedisConstants.SECKILL_RESERVATION_PENDING_KEY + hashTag,
                RedisConstants.SECKILL_RESERVATION_ORDER_KEY + hashTag
        );
    }

    private String stockKey(Long voucherId) {
        return RedisConstants.SECKILL_STOCK_KEY
                + "{" + voucherId + "}";
    }

    private String reservationKey(Long voucherId) {
        return RedisConstants.SECKILL_RESERVATION_KEY
                + "{" + voucherId + "}";
    }

    private String userEventKey(Long voucherId) {
        return RedisConstants.SECKILL_RESERVATION_USER_KEY
                + "{" + voucherId + "}";
    }

    private String pendingKey(Long voucherId) {
        return RedisConstants.SECKILL_RESERVATION_PENDING_KEY
                + "{" + voucherId + "}";
    }

    private String orderKey(Long voucherId) {
        return RedisConstants.SECKILL_RESERVATION_ORDER_KEY
                + "{" + voucherId + "}";
    }

    private String manualKey(Long voucherId) {
        return RedisConstants.SECKILL_RESERVATION_MANUAL_KEY
                + "{" + voucherId + "}";
    }

    /**
     * 原子预扣并写预留账本。
     *
     * @return 0 成功、1 无库存、2 重复用户、3 未初始化
     */
    public Long reserve(
            Long voucherId,
            Long userId,
            String eventId,
            Long orderId,
            Long createdAt,
            Integer messageVersion) {

        if (voucherId == null || userId == null
                || eventId == null || eventId.trim().isEmpty()
                || orderId == null || createdAt == null
                || messageVersion == null) {
            throw new IllegalArgumentException("秒杀预留参数不能为空");
        }

        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                buildKeys(voucherId),
                userId.toString(),
                eventId,
                orderId.toString(),
                createdAt.toString(),
                messageVersion.toString()
        );

        if (result == null) {
            throw new IllegalStateException("秒杀 Lua 脚本执行结果为空");
        }

        return result;
    }

    /**
     * 按 eventId 撤销预留；返回 1 已恢复、0 幂等成功、-1 库存 Key 不存在、-2 事件冲突。
     *
     * <p>回滚成功时同步删除 orderId 反向索引。</p>
     */
    public Long rollbackByEvent(
            Long voucherId,
            Long userId,
            String eventId,
            Long orderId) {

        if (voucherId == null || userId == null
                || eventId == null || eventId.trim().isEmpty()
                || orderId == null) {
            throw new IllegalArgumentException("秒杀回滚参数不能为空");
        }

        Long result = stringRedisTemplate.execute(
                SECKILL_ROLLBACK_SCRIPT,
                buildKeys(voucherId),
                userId.toString(),
                eventId,
                orderId.toString()
        );

        if (result == null) {
            throw new IllegalStateException("秒杀回滚 Lua 脚本执行结果为空");
        }

        return result;
    }

    /**
     * 订单成功后清理预留账本并保留一人一单用户集合。
     * 返回 1 清理成功、0 幂等成功、-2 事件冲突。
     *
     * <p>清理成功时同步删除 orderId 反向索引。</p>
     */
    public Long completeReservation(
            Long voucherId,
            Long userId,
            String eventId,
            Long orderId) {

        if (voucherId == null || userId == null
                || eventId == null || eventId.trim().isEmpty()
                || orderId == null) {
            throw new IllegalArgumentException("预留完成参数不能为空");
        }

        Long result = stringRedisTemplate.execute(
                SECKILL_RESERVATION_COMPLETE_SCRIPT,
                buildKeys(voucherId),
                userId.toString(),
                eventId,
                orderId.toString()
        );

        if (result == null) {
            throw new IllegalStateException("预留完成 Lua 脚本执行结果为空");
        }

        return result;
    }

    /**
     * 安全初始化库存；返回 0 幂等成功、1 初始化成功、-1 历史数据冲突。
     */
    public Long initStock(Long voucherId, Integer stock) {
        if (voucherId == null || stock == null || stock < 0) {
            throw new IllegalArgumentException("库存初始化参数非法");
        }

        Long result = stringRedisTemplate.execute(
                SECKILL_STOCK_INIT_SCRIPT,
                Collections.singletonList(stockKey(voucherId)),
                stock.toString()
        );

        if (result == null) {
            throw new IllegalStateException("库存初始化 Lua 脚本执行结果为空");
        }

        return result;
    }

    /**
     * 读取预留详情（orderId|userId|createdAt|messageVersion）。
     */
    public String getReservationDetail(Long voucherId, String eventId) {
        Object value = stringRedisTemplate.opsForHash()
                .get(reservationKey(voucherId), eventId);

        return value == null ? null : value.toString();
    }

    /**
     * 读取用户当前预留对应的 eventId；无预留返回 null。
     */
    public String getUserEventMapping(Long voucherId, Long userId) {
        Object value = stringRedisTemplate.opsForHash()
                .get(userEventKey(voucherId), userId.toString());

        return value == null ? null : value.toString();
    }

    /**
     * 按 orderId 读取预留对应的 eventId；无预留返回 null。
     *
     * <p>订单状态查询用它直接定位未收敛预留，
     * 不再遍历活动中的秒杀券（活动结束后预留仍可查）。
     * 反向索引是券维度 Hash（field=orderId，value=eventId），
     * 与预留账本同槽。</p>
     */
    public String findReservationEventId(Long voucherId, Long orderId) {
        if (voucherId == null || orderId == null) {
            return null;
        }

        Object value = stringRedisTemplate.opsForHash()
                .get(orderKey(voucherId), orderId.toString());

        return value == null ? null : value.toString();
    }

    /**
     * 判断预留详情是否仍存在（对账任务判断回滚 Lua 是否已执行）。
     */
    public boolean reservationExists(Long voucherId, String eventId) {
        Boolean exists = stringRedisTemplate.opsForHash()
                .hasKey(reservationKey(voucherId), eventId);

        return Boolean.TRUE.equals(exists);
    }

    /**
     * 读取超过阈值的待对账 eventId（分批，禁止全量 Scan）。
     */
    public Set<String> findPendingReservationEventIds(
            Long voucherId,
            long reservedBeforeMillis,
            int limit) {

        int safeLimit = Math.max(1, Math.min(limit, 100));

        Set<String> members = stringRedisTemplate.opsForZSet()
                .rangeByScore(
                        pendingKey(voucherId),
                        0,
                        reservedBeforeMillis,
                        0,
                        safeLimit
                );

        return members == null
                ? Collections.emptySet()
                : new LinkedHashSet<>(members);
    }

    /**
     * 异常预留原子移出自动对账集合并转入人工处理集合。
     *
     * <p>对账任务写人工失败单成功后调用：信息不完整的预留离开
     * 待对账 ZSet，不再阻塞排在其后的正常预留
     * （每轮固定读取最早 N 条，排头持续异常会让后面的记录永远扫描不到）；
     * eventId 转入人工处理 ZSet，score 为移交时间。</p>
     *
     * @return 1 移交成功、0 幂等成功（已不在待对账集合）
     */
    public Long moveReservationToManual(Long voucherId, String eventId) {
        if (voucherId == null
                || eventId == null || eventId.trim().isEmpty()) {
            throw new IllegalArgumentException("预留移交人工参数不能为空");
        }

        Long result = stringRedisTemplate.execute(
                SECKILL_RESERVATION_MANUAL_SCRIPT,
                Arrays.asList(
                        pendingKey(voucherId),
                        manualKey(voucherId)
                ),
                eventId,
                String.valueOf(System.currentTimeMillis())
        );

        if (result == null) {
            throw new IllegalStateException(
                    "预留移交人工 Lua 脚本执行结果为空"
            );
        }

        return result;
    }

    /**
     * 库存 Key 是否存在（缺失库存扫描任务使用）。
     */
    public boolean hasStockKey(Long voucherId) {
        Boolean exists = stringRedisTemplate.hasKey(stockKey(voucherId));
        return Boolean.TRUE.equals(exists);
    }

    /**
     * 读取当前 Redis 可售库存；Key 不存在返回 null。
     */
    public String getStock(Long voucherId) {
        return stringRedisTemplate.opsForValue().get(stockKey(voucherId));
    }

    /**
     * 统计待对账 ZSet 中的预留数量（库存一致性指标使用）。
     */
    public Long pendingReservationCount(Long voucherId) {
        Long count = stringRedisTemplate.opsForZSet()
                .zCard(pendingKey(voucherId));

        return count == null ? 0L : count;
    }
}
