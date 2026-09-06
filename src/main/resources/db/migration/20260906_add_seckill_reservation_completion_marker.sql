-- 对账修复：持久化 Redis 预留完成标记，避免 CONSUMED 事件批次饥饿。
-- 部署前请确认 20260821_seckill_reliability_upgrade.sql 已执行。

ALTER TABLE tb_seckill_order_event
    ADD COLUMN reservation_completed_at timestamp NULL
        DEFAULT NULL COMMENT 'Redis 预留完成脚本执行时间',
    ADD KEY idx_seckill_order_event_reservation
        (status, reservation_completed_at, consumed_at, event_id);
