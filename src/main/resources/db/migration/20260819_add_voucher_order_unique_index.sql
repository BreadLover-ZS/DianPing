-- 秒杀订单一人一单数据库兜底约束。
-- 本项目没有自动数据库迁移框架，需要人工执行。
-- 第一步只运行重复数据检查语句。
-- 只有检查结果为空，并确认同名索引不存在时，才能执行 ALTER TABLE。
-- 如果存在重复记录，不得自动删除，应先人工确认保留策略。

SELECT
    user_id,
    voucher_id,
    COUNT(*) AS order_count,
    GROUP_CONCAT(id ORDER BY id) AS order_ids
FROM tb_voucher_order
GROUP BY user_id, voucher_id
HAVING COUNT(*) > 1;


SELECT
    index_name,
    column_name,
    seq_in_index
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'tb_voucher_order'
  AND index_name = 'uk_voucher_order_user_voucher'
ORDER BY seq_in_index;

ALTER TABLE tb_voucher_order
    ADD UNIQUE INDEX uk_voucher_order_user_voucher (user_id, voucher_id);
