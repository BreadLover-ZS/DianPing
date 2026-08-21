-- 秒杀库存安全初始化脚本
-- KEYS[1]：库存 Key
-- KEYS[2]：已下单用户集合 Key
-- KEYS[3]：预留详情 Hash
-- KEYS[4]：用户事件映射 Hash
-- KEYS[5]：待对账 ZSet
-- ARGV[1]：初始库存
--
-- 返回：0 幂等成功（库存已存在）；1 初始化成功；-1 存在历史数据冲突

-- 库存 Key 已存在，按幂等成功处理，不覆盖
if redis.call('EXISTS', KEYS[1]) == 1 then
    return 0
end

-- 库存缺失但存在历史用户或预留数据：禁止清空，进入人工处理
if redis.call('EXISTS', KEYS[2]) == 1
        or redis.call('EXISTS', KEYS[3]) == 1
        or redis.call('EXISTS', KEYS[4]) == 1
        or redis.call('ZCARD', KEYS[5]) > 0 then
    return -1
end

redis.call('SET', KEYS[1], ARGV[1])

return 1
