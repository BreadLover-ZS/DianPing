-- RabbitMQ 秒杀可靠性闭环改造：失败处置审计表。
-- 依据 docs/development/10-rabbitmq-seckill-reliability-development-spec.md 第 13 节：
-- 人工重放、回滚或关闭失败记录必须持久化操作者、时间和原因。
-- 项目暂无 RBAC，审计 operator 由内部调用方传入，Controller 在 RBAC 完成前禁止开放。

CREATE TABLE IF NOT EXISTS tb_seckill_failure_audit
(
    audit_id    bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '审计记录 ID',
    failure_id  bigint(20) UNSIGNED NOT NULL COMMENT '关联失败记录 ID',
    event_id    varchar(64)          DEFAULT NULL COMMENT '关联事件 ID',
    action      varchar(32)  NOT NULL COMMENT 'REPLAY、ROLLBACK、CLOSE',
    operator    varchar(64)  NOT NULL COMMENT '操作者标识',
    reason      varchar(512)          DEFAULT NULL COMMENT '操作原因',
    create_time timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    PRIMARY KEY (audit_id),
    KEY idx_failure_audit_case (failure_id, create_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '秒杀失败处置审计表';
