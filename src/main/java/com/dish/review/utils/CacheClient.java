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

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;

        //1.从Redis中查商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);

        //2.判断是否存在
        if (StrUtil.isNotBlank(json)) {
            //3.存在，返回
            return JSONUtil.toBean(json, type);
        }

        //判断命中的是否是控制
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

    private boolean tryLock(String key) {
        return BooleanUtil.isTrue(stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS));
    }

    //释放锁
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
