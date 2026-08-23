-- 发布尝试超时/异常后仍可能收到 Confirm；追加旁证列，不覆盖原 UNKNOWN/NACK 结论。
ALTER TABLE tb_seckill_publish_attempt
    ADD COLUMN late_confirm_at datetime NULL COMMENT '迟到Confirm时间' AFTER update_time,
    ADD COLUMN late_confirm_result tinyint NULL COMMENT '迟到Confirm结果：1 ACK，2 NACK' AFTER late_confirm_at,
    ADD COLUMN late_confirm_reason varchar(512) NULL COMMENT '迟到Confirm原因' AFTER late_confirm_result;
