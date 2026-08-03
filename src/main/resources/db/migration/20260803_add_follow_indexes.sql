-- 关注关系约束和 Feed 流查询索引
-- 执行前请先检查现有数据是否存在重复的 (user_id, follow_user_id)。
-- 本项目当前没有自动迁移框架，该脚本需要在目标数据库上人工执行一次。

ALTER TABLE tb_follow
    ADD UNIQUE INDEX uk_user_follow (user_id, follow_user_id),
    ADD INDEX idx_follow_user_id (follow_user_id);
