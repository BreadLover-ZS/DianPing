package com.dish.review.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.dish.review.dto.Result;
import com.dish.review.entity.Shop;
import com.dish.review.mapper.ShopMapper;
import com.dish.review.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dish.review.utils.CacheClient;
import com.dish.review.utils.RedisConstants;
import com.dish.review.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author DishReview
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        //解决缓存穿透
//        Shop shop = queryWithPassThrough(id);
//        Shop shop = cacheClient
//                .queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);

        //逻辑过期解决缓存击穿
//        Shop shop = queryWithLogicalExpire(id);
        Shop shop = cacheClient
                .queryWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }

        //缓解缓存穿透
        return Result.ok(shop);
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public Shop queryWithLogicalExpire(Long id) {
        //1.从Redis中查商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);

        //2.判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            //3.不存在，返回
            return null;
        }

        //4.存在，把json序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        //5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //5.1未过期，直接返回店铺信息
            return shop;
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
                    saveShop2Redis(id, 1000L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //6.4释放锁
                    unlock(lockKey);
                }
            });

        }
        //6.5返回过期的商铺信息
        return shop;
    }

    public Shop queryWithMutex(Long id) {
        //1.从Redis中查商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);

        //2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //3.存在，返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        //判断命中的是否是空值
        if (shopJson != null) {
            //返回错误信息
            return null;
        }

        //4.实现缓存重建(缓解缓存击穿)
        //4.1获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLocked = tryLock(lockKey);
            //4.2判断是否获取锁
            if (!isLocked) {
                //4.3失败，休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            //4.4成功，根据id查数据库
            shop = getById(id);

            //5.判断数据库中是否存在
            if (shop == null) {
                //6.不存在
                //将控制接入redis（解决缓存穿透）
                stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);

                //返回错误信息
                return null;
            }

            //7.存在，写入Redis,释放互斥锁
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {

            unlock(lockKey);
        }

        //8.返回
        return shop;
    }


    public Shop queryWithPassThrough(Long id) {
        //1.从Redis中查商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);

        //2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //3.存在，返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        //判断命中的是否是控制
        if (shopJson != null) {
            //返回错误信息
            return null;
        }

        //4.不存在，根据id查数据库
        Shop shop = getById(id);

        //5.判断数据库中是否存在
        if (shop == null) {
            //6.不存在

            //将控制接入redis（解决缓存穿透）
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);

            //返回错误信息
            return null;
        }

        //7.存在，写入Redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //8.返回
        return shop;
    }

    //获取锁
    private boolean tryLock(String key) {
        return BooleanUtil.isTrue(stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS));
    }

    //释放锁
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    public void saveShop2Redis(Long id, Long expireSeconds) {
        //1.查询数据
        Shop shop = getById(id);
        //2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusMinutes(expireSeconds));
        //3.写入Redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }


    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();

        if (id == null) {
            return Result.fail("店铺id不能为空");
        }

        //1.更新数据库
        updateById(shop);

        //2.删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
