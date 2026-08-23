-- 用户角色与管理写接口授权。
-- 执行前检查目标库是否已经存在 role 列；本项目当前迁移由人工按顺序执行。
ALTER TABLE tb_user
    ADD COLUMN role varchar(16) NOT NULL DEFAULT 'USER' COMMENT '角色：USER、ADMIN';

UPDATE tb_user
SET role = 'USER'
WHERE role IS NULL OR role = '';

-- 仅在明确确认操作者身份后执行示例：
-- UPDATE tb_user SET role = 'ADMIN' WHERE phone = '替换为管理员手机号';
