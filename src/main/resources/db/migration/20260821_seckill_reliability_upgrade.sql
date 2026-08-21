-- RabbitMQ 秒杀可靠性闭环改造：数据模型升级。
-- 依据 docs/development/10-rabbitmq-seckill-reliability-development-spec.md 第 6 节。
-- 只新增列和新表，不修改历史迁移；执行前需备份事件表（见规格 17.1 上线顺序）。

-- 1. 事件表扩展：回滚计数、任务租约、行版本、稳定错误码和终态时间
ALTER TABLE tb_seckill_order_event
    ADD COLUMN rollback_retry_count int(10) UNSIGNED NOT NULL DEFAULT 0 COMMENT '回滚执行次数',
    ADD COLUMN lease_owner          varchar(64)          DEFAULT NULL COMMENT '当前任务实例',
    ADD COLUMN lease_until          timestamp NULL       DEFAULT NULL COMMENT '任务租约到期时间',
    ADD COLUMN lease_token          bigint(20) UNSIGNED NOT NULL DEFAULT 0 COMMENT '每次抢占生成的 fencing token',
    ADD COLUMN row_version          bigint(20) UNSIGNED NOT NULL DEFAULT 0 COMMENT '行版本',
    ADD COLUMN last_error_code      varchar(64)          DEFAULT NULL COMMENT '稳定错误码',
    ADD COLUMN confirmed_at         timestamp NULL       DEFAULT NULL COMMENT 'Broker 确认时间',
    ADD COLUMN consumed_at          timestamp NULL       DEFAULT NULL COMMENT '订单完成时间',
    ADD COLUMN terminal_at          timestamp NULL       DEFAULT NULL COMMENT '终态时间';

-- 覆盖定时任务扫描条件的组合索引（上线前用 EXPLAIN 验证）
ALTER TABLE tb_seckill_order_event
    ADD KEY idx_seckill_order_event_task (status, next_retry_time, lease_until);

-- 旧 FAILED(3) 只能迁移为 MANUAL_REVIEW(9)，禁止直接迁移为 ROLLED_BACK
UPDATE tb_seckill_order_event
SET status      = 9,
    last_error  = CONCAT('migrated_from_failed: ', IFNULL(last_error, '')),
    terminal_at = CURRENT_TIMESTAMP
WHERE status = 3;

-- 2. 发布尝试表：记录每一次实际 convertAndSend 的独立证据
CREATE TABLE IF NOT EXISTS tb_seckill_publish_attempt
(
    attempt_id     varchar(64)         NOT NULL COMMENT '每次实际发送的唯一 ID，同时写入 CorrelationData 和消息 Header',
    event_id       varchar(64)         NOT NULL COMMENT '关联事件 ID',
    attempt_no     int(10) UNSIGNED    NOT NULL COMMENT '事件内发送序号',
    confirm_status tinyint(3) UNSIGNED NOT NULL DEFAULT 0 COMMENT '0=WAITING，1=ACK，2=NACK，3=UNKNOWN',
    returned       tinyint(1)          NOT NULL DEFAULT 0 COMMENT '是否触发 Return',
    send_exception tinyint(1)          NOT NULL DEFAULT 0 COMMENT '同步调用是否抛异常',
    error_code     varchar(64)                  DEFAULT NULL COMMENT '稳定错误码',
    error_message  varchar(512)                 DEFAULT NULL COMMENT '错误信息（截断）',
    sent_at        timestamp           NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发送时间',
    confirm_at     timestamp NULL      DEFAULT NULL COMMENT 'Confirm 到达时间',
    return_at      timestamp NULL      DEFAULT NULL COMMENT 'Return 到达时间',
    create_time    timestamp           NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time    timestamp           NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (attempt_id),
    UNIQUE KEY uk_publish_attempt_event_no (event_id, attempt_no),
    KEY idx_publish_attempt_evidence (event_id, confirm_status, returned),
    KEY idx_publish_attempt_confirm_scan (confirm_status, sent_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '秒杀订单发布尝试证据表';

-- 3. 失败记录表：承接 DLQ、回滚异常和对账冲突的持久化事实
CREATE TABLE IF NOT EXISTS tb_seckill_failure_case
(
    failure_id      bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '失败记录 ID',
    idempotency_key varchar(128)        NOT NULL COMMENT '防止同一失败被重复落库',
    event_id        varchar(64)                  DEFAULT NULL COMMENT '关联事件 ID',
    order_id        bigint(20) UNSIGNED           DEFAULT NULL COMMENT '关联订单 ID',
    user_id         bigint(20) UNSIGNED           DEFAULT NULL COMMENT '关联用户 ID',
    voucher_id      bigint(20) UNSIGNED           DEFAULT NULL COMMENT '关联秒杀券 ID',
    source          varchar(32)         NOT NULL COMMENT 'PUBLISH、CONSUMER_DLQ、ROLLBACK、RECONCILE',
    status          varchar(32)         NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN、REPLAYED、ROLLED_BACK、CLOSED、MANUAL',
    error_code      varchar(64)                  DEFAULT NULL COMMENT '稳定错误码',
    error_message   varchar(512)                 DEFAULT NULL COMMENT '错误信息（截断）',
    message_payload varchar(2048)                DEFAULT NULL COMMENT '受限长度的消息摘要',
    x_death_info    varchar(1024)                DEFAULT NULL COMMENT 'DLQ x-death 信息摘要',
    replay_count    int(10) UNSIGNED    NOT NULL DEFAULT 0 COMMENT '重放次数',
    next_action_time timestamp NULL     DEFAULT NULL COMMENT '下次可执行动作时间',
    create_time     timestamp           NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     timestamp           NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (failure_id),
    UNIQUE KEY uk_failure_case_idempotency (idempotency_key),
    KEY idx_failure_case_scan (status, next_action_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '秒杀订单失败处置记录表';
