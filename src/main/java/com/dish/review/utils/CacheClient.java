package com.dish.review.utils;


import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.dish.review.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 构造注入 StringRedisTemplate
     *
     * @param stringRedisTemplate Redis 操作模板
     */
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 将对象序列化为JSON并存入Redis，同时设置物理过期时间
     *
     * @param key   Redis缓存键
     * @param value 待缓存的对象，会被序列化为JSON字符串
     * @param time  过期时间数值
     * @param unit  过期时间单位
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * 将对象存入Redis，并附带逻辑过期时间（Redis本身不设置TTL）
     *
     * @param key   Redis缓存键
     * @param value 待缓存的对象
     * @param time  逻辑过期时间数值（从当前时间起）
     * @param unit  逻辑过期时间单位
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 封装数据和逻辑过期时间到RedisData对象
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 缓存查询，采用"缓存穿透"解决方案：数据库不存在时缓存空字符串
     *
     * @param keyPrefix  缓存键前缀
     * @param id         数据ID
     * @param type       返回值类型，用于反序列化
     * @param dbFallback 数据库查询回退逻辑（当缓存未命中时调用）
     * @param time       缓存过期时间数值
     * @param unit       缓存过期时间单位
     * @param <R>        返回值泛型
     * @param <ID>       ID泛型
     * @return 查询到的对象，若数据库中不存在则返回null
     */
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;

        //1.从Redis中查商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);

        //2.判断是否存在
        if (StrUtil.isNotBlank(json)) {
            //3.存在，返回
            return JSONUtil.toBean(json, type);
        }

        //判断命中的是否是空值（缓解缓存穿透）
        if (json != null) {
            //返回错误信息
            return null;
        }

        //4.不存在，根据id查数据库
        R r = dbFallback.apply(id);

        //5.判断数据库中是否存在
        if (r == null) {
            //6.不存在
            //将控制信息接入redis（解决缓存穿透）
            stringRedisTemplate.opsForValue().set(key, "", time, unit);

            //返回错误信息
            return null;
        }

        //7.存在，写入Redis
        this.set(key, r, time, unit);

        //8.返回
        return r;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 缓存查询，采用"逻辑过期"方案：未过期直接返回，过期后异步重建缓存并返回旧数据
     *
     * @param keyPrefix  缓存键前缀
     * @param id         数据ID
     * @param type       返回值类型，用于反序列化
     * @param dbFallback 数据库查询回退逻辑（缓存重建时调用）
     * @param time       缓存逻辑过期时间数值
     * @param unit       缓存逻辑过期时间单位
     * @param <R>        返回值泛型
     * @param <ID>       ID泛型
     * @return 查询到的对象，若缓存不存在则返回null
     */
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;

        //1.从Redis中查商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);

        //2.判断是否存在
        if (StrUtil.isBlank(json)) {
            //3.不存在，返回
            return null;
        }

        //4.存在，把json序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();

        //5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //5.1未过期，直接返回店铺信息
            return r;
        }

        //6.过期，缓存重建,然后返回旧对象
        //6.1获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean isLocked = tryLock(lockKey);

        //6.2判断是否获取锁
        if (isLocked) {
            //6.3成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //查数据库
                    R r1 = dbFallback.apply(id);
                    //写入redis
                    this.setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //6.4释放锁
                    unlock(lockKey);
                }
            });

        }
        //6.5返回过期的商铺信息
        return r;
    }

    /**
     * 获取分布式互斥锁（基于Redis SETNX实现，带10秒过期防止死锁）
     *
     * @param key 锁的Redis键
     * @return true表示成功获取锁，false表示锁已被其他线程持有
     */
    private boolean tryLock(String key) {
        return BooleanUtil.isTrue(stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS));
    }

    //释放锁
    /**
     * 释放分布式互斥锁（删除Redis中的锁键）
     *
     * @param key 锁的Redis键
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
