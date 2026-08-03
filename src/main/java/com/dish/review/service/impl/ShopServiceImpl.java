package com.dish.review.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dish.review.dto.Result;
import com.dish.review.entity.Shop;
import com.dish.review.mapper.ShopMapper;
import com.dish.review.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dish.review.utils.CacheClient;
import com.dish.review.utils.RedisConstants;
import com.dish.review.utils.RedisData;
import com.dish.review.utils.SystemConstants;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoRadiusCommandArgs;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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
        // 缓存穿透方案：缓存未命中时回源数据库，并在数据库不存在时缓存空值
        // 说明：逻辑过期方案（queryWithLogicalExpire）需要预先预热缓存（saveShop2Redis），
        //       冷启动时缓存为空会直接返回 null，导致店铺详情不可用。
        //       为保证冷启动可用，默认采用缓存穿透方案；逻辑过期方案作为击穿优化的参考实现保留在下方。
        Shop shop = cacheClient
                .queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
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

    /**
     * 根据商铺类型查询附近商铺（基于 Redis GEO）
     * 若未传坐标，则退化为普通分页查询
     *
     * @param typeId  商铺类型
     * @param current 页码
     * @param x       经度
     * @param y       纬度
     * @return 商铺列表（含距离）
     */
    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 无坐标，退化为按类型分页查询
        if (x == null || y == null) {
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }
        // 基于 Redis GEO 查询附近商铺
        String key = RedisConstants.SHOP_GEO_KEY + typeId;
        // 若 GEO 数据未加载，从数据库加载该类型所有店铺坐标
        if (Boolean.FALSE.equals(stringRedisTemplate.hasKey(key))) {
            loadShopGeo(typeId, key);
        }
        // 以坐标为中心，查询 5km 内的商铺（按距离升序）
        Circle circle = new Circle(new Point(x, y), new Distance(5000, Metrics.KILOMETERS));
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .radius(key, circle, GeoRadiusCommandArgs.newGeoRadiusArgs()
                        .includeDistance()
                        .sortAscending());
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        // radius 不支持 offset，这里手动分页
        int size = SystemConstants.DEFAULT_PAGE_SIZE;
        int from = (current - 1) * size;
        if (from >= list.size()) {
            return Result.ok(Collections.emptyList());
        }
        int end = Math.min(from + size, list.size());
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> pageList = list.subList(from, end);
        // 取出店铺id
        List<Long> ids = pageList.stream()
                .map(r -> Long.valueOf(r.getContent().getName()))
                .collect(Collectors.toList());
        // 查询店铺信息并保持按距离升序
        Map<Long, Shop> shopMap = listByIds(ids).stream()
                .collect(Collectors.toMap(Shop::getId, s -> s));
        List<Shop> shops = new ArrayList<>();
        for (GeoResult<RedisGeoCommands.GeoLocation<String>> r : pageList) {
            Long id = Long.valueOf(r.getContent().getName());
            Shop shop = shopMap.get(id);
            if (shop == null) {
                continue;
            }
            shop.setDistance(r.getDistance().getValue());
            shops.add(shop);
        }
        return Result.ok(shops);
    }

    /**
     * 从数据库加载指定类型商铺的坐标到 Redis GEO
     *
     * @param typeId 商铺类型
     * @param key    GEO 缓存键
     */
    private void loadShopGeo(Integer typeId, String key) {
        List<Shop> shops = query().eq("type_id", typeId).list();
        for (Shop shop : shops) {
            if (shop.getX() != null && shop.getY() != null) {
                stringRedisTemplate.opsForGeo().add(key, new Point(shop.getX(), shop.getY()), shop.getId().toString());
            }
        }
    }
}
