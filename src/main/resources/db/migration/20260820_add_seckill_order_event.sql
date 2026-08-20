-- 秒杀订单消息的持久化事件记录。
-- 用于处理发送结果未知、失败补偿和后续对账。
-- 项目未集成自动迁移框架，执行前需要检查目标环境。

CREATE TABLE IF NOT EXISTS tb_seckill_order_event
(
    event_id        varchar(64)         NOT NULL COMMENT '消息事件唯一 ID',
    order_id        bigint(20) UNSIGNED NOT NULL COMMENT '秒杀订单 ID',
    user_id         bigint(20) UNSIGNED NOT NULL COMMENT '下单用户 ID',
    voucher_id      bigint(20) UNSIGNED NOT NULL COMMENT '秒杀券 ID',
    created_at      bigint(20) UNSIGNED NOT NULL COMMENT '消息创建时间，Unix 毫秒',
    message_version tinyint(3) UNSIGNED NOT NULL DEFAULT 1 COMMENT '消息版本',
    status          tinyint(3) UNSIGNED NOT NULL DEFAULT 0
        COMMENT '0=PENDING，1=CONFIRMED，2=CONSUMED，3=FAILED',
    retry_count     int(10) UNSIGNED    NOT NULL DEFAULT 0 COMMENT '补偿重试次数',
    next_retry_time timestamp           NULL     DEFAULT NULL COMMENT '下次补偿时间',
    last_error      varchar(512)                 DEFAULT NULL COMMENT '最近失败原因',
    create_time     timestamp           NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     timestamp           NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (event_id),
    UNIQUE KEY uk_seckill_order_event_order (order_id),
    KEY idx_seckill_order_event_retry (status, next_retry_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '秒杀订单消息事件表';
