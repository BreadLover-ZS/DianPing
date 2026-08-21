-- 异常预留移交人工处理集合脚本
-- KEYS[1]：待对账 ZSet              seckill:reservation:pending:{voucherId}
-- KEYS[2]：人工处理 ZSet            seckill:reservation:manual:{voucherId}
-- ARGV[1]：eventId
-- ARGV[2]：移交时间（Unix 毫秒，作为人工集合 score）
--
-- 返回：1 移交成功；0 幂等成功（已不在待对账集合）
--
-- 两个 Key 使用相同 {voucherId} Hash Tag，Redis Cluster 同槽原子执行。
-- 对账任务写人工失败单成功后调用：信息不完整的预留离开自动对账队列，
-- 不再阻塞排在其后的正常预留（否则每轮固定读取最早 N 条，
-- 排头持续异常会让后面的正常记录永远无法被扫描）；
-- 该预留转入人工处理集合，由人工处置后收敛。
--
-- Redis Lua 的原子性保证执行期间不被其他命令穿插，但运行时错误不会
-- 回滚此前已经成功的写操作。因此必须先确认源成员存在，再写入人工集合，
-- 最后删除待对账成员：即使 ZADD 因 WRONGTYPE 等原因失败，源入口仍保留；
-- ZSCORE 已验证源 Key 类型，脚本执行期间其类型也不会被其他客户端改变。
if not redis.call('ZSCORE', KEYS[1], ARGV[1]) then
    return 0
end

redis.call('ZADD', KEYS[2], ARGV[2], ARGV[1])
redis.call('ZREM', KEYS[1], ARGV[1])
return 1
