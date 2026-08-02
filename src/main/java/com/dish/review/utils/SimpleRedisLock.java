package com.dish.review.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private String name;
    private StringRedisTemplate stringRedisTemplate;
    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        //获取线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();

        //在Redis中存储 Key：lock:name, Value：threadId, 设置过期时间
        Boolean isLocked = stringRedisTemplate
                .opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);

        return isLocked != null && isLocked;
    }

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
