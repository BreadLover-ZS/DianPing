-- 秒杀 Redis 事件级回滚脚本
-- KEYS[1]：库存 Key
-- KEYS[2]：已下单用户集合 Key
-- KEYS[3]：预留详情 Hash
-- KEYS[4]：用户事件映射 Hash
-- KEYS[5]：待对账 ZSet
-- KEYS[6]：orderId 反向索引 Hash   seckill:reservation:order:{voucherId}
-- ARGV[1]：userId
-- ARGV[2]：eventId
-- ARGV[3]：orderId（反向索引 field）
--
-- 六个 Key 全部使用 {voucherId} Hash Tag，Redis Cluster 下同槽原子执行。
--
-- 返回：1 已恢复；0 无需重复恢复（已处理）；-1 库存 Key 不存在；-2 事件冲突

-- 库存 Key 不存在时不能回滚，避免 INCR 创建错误库存
if redis.call('EXISTS', KEYS[1]) == 0 then
    return -1
end

local mappedEvent = redis.call('HGET', KEYS[4], ARGV[1])

-- 映射不存在表示已处理（回滚或完成），幂等成功；
-- 顺手清理可能残留的 orderId 反向索引（自愈）
if mappedEvent == false then
    redis.call('HDEL', KEYS[6], ARGV[3])
    return 0
end

-- 映射指向其他事件：禁止删除用户和增加库存，也不得删他人索引
if mappedEvent ~= ARGV[2] then
    return -2
end

-- 删除预留详情、用户事件映射、待对账成员和 orderId 反向索引
redis.call('HDEL', KEYS[3], ARGV[2])
redis.call('HDEL', KEYS[4], ARGV[1])
redis.call('ZREM', KEYS[5], ARGV[2])
redis.call('HDEL', KEYS[6], ARGV[3])

-- 只有确实移除一个用户时才恢复库存
local removed = redis.call('SREM', KEYS[2], ARGV[1])
if removed == 1 then
    redis.call('INCR', KEYS[1])
end

return 1
