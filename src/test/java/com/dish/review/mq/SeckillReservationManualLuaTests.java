package com.dish.review.mq;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 异常预留移交 Lua 的静态安全约束测试。
 *
 * <p>当前单元测试不连接真实 Redis，因此直接锁定最关键的命令顺序：
 * 先确认待对账成员存在，再写人工集合，最后移除待对账入口。</p>
 */
class SeckillReservationManualLuaTests {

    @Test
    void manualTargetMustBeWrittenBeforePendingEntryIsRemoved()
            throws Exception {

        String script = StreamUtils.copyToString(
                new ClassPathResource("seckill_reservation_manual.lua")
                        .getInputStream(),
                StandardCharsets.UTF_8
        );

        int scoreIndex = script.indexOf("redis.call('ZSCORE'");
        int addIndex = script.indexOf("redis.call('ZADD'");
        int removeIndex = script.indexOf("redis.call('ZREM'");

        assertTrue(scoreIndex >= 0, "脚本必须先确认待对账成员存在");
        assertTrue(addIndex > scoreIndex, "人工集合写入必须发生在成员确认之后");
        assertTrue(removeIndex > addIndex, "只有人工集合写入成功后才能移除待对账入口");
    }
}
