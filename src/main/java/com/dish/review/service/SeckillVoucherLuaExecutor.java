package com.dish.review.service;

import com.dish.review.utils.RedisConstants;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 执行秒杀 Redis Lua，提供原子预扣和幂等回滚能力。
 */
@Component
public class SeckillVoucherLuaExecutor {

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    private static final DefaultRedisScript<Long> SECKILL_ROLLBACK_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);

        SECKILL_ROLLBACK_SCRIPT = new DefaultRedisScript<>();
        SECKILL_ROLLBACK_SCRIPT.setLocation(
                new ClassPathResource("seckill_rollback.lua")
        );
        SECKILL_ROLLBACK_SCRIPT.setResultType(Long.class);
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
     * 构造带相同 Hash Tag 的库存 Key 和用户集合 Key，兼容 Redis Cluster。
     */
    private List<String> buildKeys(Long voucherId) {
        String hashTag = "{" + voucherId + "}";

        return Arrays.asList(
                RedisConstants.SECKILL_STOCK_KEY + hashTag,
                RedisConstants.SECKILL_ORDER_KEY + hashTag
        );
    }

    /**
     * 原子检查库存和一人一单后预扣；返回 0 成功、1 无库存、2 重复、3 未初始化。
     */
    public Long reserve(Long voucherId, Long userId) {
        if (voucherId == null || userId == null) {
            throw new IllegalArgumentException("优惠券 ID 和用户 ID 不能为空");
        }

        List<String> keys = buildKeys(voucherId);

        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                keys,
                userId.toString()
        );

        if (result == null) {
            throw new IllegalStateException("秒杀 Lua 脚本执行结果为空");
        }

        return result;
    }

    /**
     * 幂等撤销预扣；返回 1 已恢复、0 无需重复恢复、-1 库存 Key 不存在。
     */
    public Long rollback(Long voucherId, Long userId) {
        if (voucherId == null || userId == null) {
            throw new IllegalArgumentException("优惠券 ID 和用户 ID 不能为空");
        }

        Long result = stringRedisTemplate.execute(
                SECKILL_ROLLBACK_SCRIPT,
                buildKeys(voucherId),
                userId.toString()
        );

        if (result == null) {
            throw new IllegalStateException("秒杀回滚 Lua 脚本执行结果为空");
        }

        return result;
    }
}
