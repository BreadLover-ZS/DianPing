package com.dish.review.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * 基于Redis实现的简单分布式锁
 * <p>
 * 通过Redis SETNX命令实现互斥，锁值为"应用级UUID+线程ID"以区分持有者。
 * 释放锁时使用Lua脚本原子校验持有者并删除，避免误删其他线程的锁。
 */
public class SimpleRedisLock implements ILock{

    private final String name;
    private final StringRedisTemplate stringRedisTemplate;
    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                    "return redis.call('del', KEYS[1]) " +
                    "else return 0 end", Long.class);

    /**
     * 构造SimpleRedisLock实例
     *
     * @param name                锁的业务名称（如"order"），最终Redis键为 "lock:{name}"
     * @param stringRedisTemplate Redis操作模板
     */
    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 尝试获取分布式锁
     *
     * @param timeoutSec 锁的超时时间（秒），到期后锁自动释放，防止持有者宕机导致死锁
     * @return true表示成功获取锁，false表示锁已被其他线程持有
     */
    @Override
    public boolean tryLock(long timeoutSec) {
        //获取线程标识，作为key的值，在解锁的时候用于判断这个锁是哪个线程的，ID_PREFIX是UUID防止多服务器线程同名
        String threadId = ID_PREFIX + Thread.currentThread().getId();

        //在Redis中存储 Key：lock:name, Value：threadId, 设置过期时间
        Boolean isLocked = stringRedisTemplate
                .opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);

        return isLocked != null && isLocked;
    }

    /**
     * 释放分布式锁。
     * <p>
     * 通过Lua脚本保证“校验锁持有者”和“删除锁”是一个原子操作。
     */
    @Override
    public void unlock() {
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                threadId);
    }
}
