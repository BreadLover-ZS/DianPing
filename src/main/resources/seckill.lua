-- 秒杀 Redis 原子预留脚本（预留账本版）
-- KEYS[1]：库存 Key                  seckill:stock:{voucherId}
-- KEYS[2]：已下单用户集合 Key         seckill:order:{voucherId}
-- KEYS[3]：预留详情 Hash              seckill:reservation:{voucherId}
-- KEYS[4]：用户事件映射 Hash           seckill:reservation:user:{voucherId}
-- KEYS[5]：待对账 ZSet                seckill:reservation:pending:{voucherId}
-- KEYS[6]：orderId 反向索引 Hash      seckill:reservation:order:{voucherId}
-- ARGV[1]：userId
-- ARGV[2]：eventId
-- ARGV[3]：orderId
-- ARGV[4]：createdAt（Unix 毫秒）
-- ARGV[5]：messageVersion
--
-- 返回：0 成功；1 库存不足；2 重复用户；3 库存未初始化
--
-- 六个 Key 全部使用 {voucherId} Hash Tag：
-- Redis Cluster 下同槽，单个 Lua 脚本原子写入预留账本与 orderId 反向索引。

local stock = tonumber(redis.call('GET', KEYS[1]))

-- Redis 库存尚未初始化
if stock == nil then
    return 3
end

-- 库存不足
if stock <= 0 then
    return 1
end

-- 用户已在一人一单集合
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return 2
end

-- 用户事件映射已存在（存在未收敛的预留）
if redis.call('HEXISTS', KEYS[4], ARGV[1]) == 1 then
    return 2
end

-- 扣减 Redis 库存
redis.call('DECR', KEYS[1])

-- 记录用户已经预扣（一人一单）
redis.call('SADD', KEYS[2], ARGV[1])

-- 写入预留详情：orderId|userId|createdAt|messageVersion
redis.call('HSET', KEYS[3], ARGV[2],
        ARGV[3] .. '|' .. ARGV[1] .. '|' .. ARGV[4] .. '|' .. ARGV[5])

-- 写入用户到事件的映射
redis.call('HSET', KEYS[4], ARGV[1], ARGV[2])

-- 写入待对账 ZSet，score 为预留时间
redis.call('ZADD', KEYS[5], ARGV[4], ARGV[2])

-- 写入 orderId 反向索引（Hash<orderId, eventId>）：
-- 订单状态查询不依赖活动券范围即可定位预留
redis.call('HSET', KEYS[6], ARGV[3], ARGV[2])

return 0
