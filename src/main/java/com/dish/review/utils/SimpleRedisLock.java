package com.dish.review.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 基于Redis实现的简单分布式锁
 * <p>
 * 通过Redis SETNX命令实现互斥，锁值为"应用级UUID+线程ID"以区分持有者。
 * 注意：unlock的判断与删除非原子操作，高并发下建议后续升级为Lua脚本实现。
 */
public class SimpleRedisLock implements ILock{

    private final String name;
    private final StringRedisTemplate stringRedisTemplate;
    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";

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
     * 释放分布式锁
     * <p>
     * 释放前校验锁是否属于当前线程，避免误删其他线程持有的锁。
     * 注意：当前校验与删除为非原子操作，极端场景下仍可能误删，建议后续改用Lua脚本保证原子性。
     */
    @Override
    public void unlock() {
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        //释放锁之前需要判断是否是自己的锁：如果业务时间太久，锁被自动释放，别的线程获取锁后，被本线程释放，将出现问题
        //判断锁标识和释放锁应该是原子操作：如果在判断锁标识后，线程被阻塞，可能会释放不属于自己的锁

        if (!threadId.equals(stringRedisTemplate.opsForValue().get(KEY_PREFIX + name))) {
            return;
        }

        stringRedisTemplate.delete(KEY_PREFIX + name);
    }
}
