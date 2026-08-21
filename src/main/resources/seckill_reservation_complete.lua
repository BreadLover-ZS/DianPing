-- 秒杀 Redis 预留完成脚本
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
-- 订单成功后清理预留账本，但保留一人一单用户集合。
-- 返回：1 清理成功；0 幂等成功（已处理）；-2 事件冲突

local mappedEvent = redis.call('HGET', KEYS[4], ARGV[1])

-- 映射不存在表示预留已清理，幂等成功；
-- 顺手清理可能残留的 orderId 反向索引（自愈）
if mappedEvent == false then
    redis.call('HDEL', KEYS[6], ARGV[3])
    return 0
end

-- 映射指向其他事件：不能删除他人预留，也不得删他人索引
if mappedEvent ~= ARGV[2] then
    return -2
end

-- 删除预留详情、用户事件映射、待对账成员和 orderId 反向索引
redis.call('HDEL', KEYS[3], ARGV[2])
redis.call('HDEL', KEYS[4], ARGV[1])
redis.call('ZREM', KEYS[5], ARGV[2])
redis.call('HDEL', KEYS[6], ARGV[3])

-- 保留 KEYS[2] 中的用户，继续执行一人一单限制

return 1
