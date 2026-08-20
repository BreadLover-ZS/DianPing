-- KEYS[1]：秒杀库存 Key
-- KEYS[2]：已下单用户集合 Key
-- ARGV[1]：当前用户 ID

local stock = tonumber(redis.call('GET', KEYS[1]))

-- Redis 库存尚未初始化
if stock == nil then
    return 3
end

-- 库存不足
if stock <= 0 then
    return 1
end

-- 用户已经预扣过该优惠券
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return 2
end

-- 扣减 Redis 库存
redis.call('DECR', KEYS[1])

-- 记录用户已经预扣
redis.call('SADD', KEYS[2], ARGV[1])

return 0
