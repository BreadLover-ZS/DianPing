-- KEYS[1]：秒杀库存 Key
-- KEYS[2]：已下单用户集合 Key
-- ARGV[1]：需要回滚的用户 ID

-- 库存 Key 不存在时不能回滚，避免 INCR 创建错误库存
if redis.call('EXISTS', KEYS[1]) == 0 then
    return -1
end

-- 先尝试移除用户预扣标记
local removed = redis.call('SREM', KEYS[2], ARGV[1])

-- 用户不在集合中，说明没有预扣或已经回滚过
if removed == 0 then
    return 0
end

-- 只有成功移除用户标记时，才恢复一份库存
redis.call('INCR', KEYS[1])

return 1
