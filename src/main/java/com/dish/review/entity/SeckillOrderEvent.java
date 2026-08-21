package com.dish.review.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 秒杀消息事件持久化记录，用一个受控的主状态机驱动发布、消费和回滚收敛。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_seckill_order_event")
public class SeckillOrderEvent implements Serializable {
    @TableId(value = "event_id", type = IdType.INPUT)
    private String eventId;

    private Long orderId;
    private Long userId;
    private Long voucherId;
    private Long createdAt;
    private Integer messageVersion;
    private Integer status;
    private Integer retryCount;
    private LocalDateTime nextRetryTime;
    private String lastError;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 回滚执行次数。 */
    private Integer rollbackRetryCount;

    /** 当前持有任务租约的实例标识。 */
    private String leaseOwner;

    /** 任务租约到期时间，以 MySQL CURRENT_TIMESTAMP 为准。 */
    private LocalDateTime leaseUntil;

    /** 每次租约抢占生成的 fencing token。 */
    private Long leaseToken;

    /** 行版本，配合条件 UPDATE 使用。 */
    private Long rowVersion;

    /** 稳定错误码。 */
    private String lastErrorCode;

    /** Broker 确认时间。 */
    private LocalDateTime confirmedAt;

    /** 订单完成时间。 */
    private LocalDateTime consumedAt;

    /** 终态时间（CONSUMED/ROLLED_BACK/MANUAL_REVIEW）。 */
    private LocalDateTime terminalAt;

    /** 等待发布或补偿发布。 */
    public static final int STATUS_PENDING = 0;
    /** Broker 已确认接收。 */
    public static final int STATUS_CONFIRMED = 1;
    /** 订单事务已完成，业务成功终态。 */
    public static final int STATUS_CONSUMED = 2;
    /** 旧状态，仅兼容迁移，新代码禁止写入。 */
    public static final int STATUS_FAILED = 3;
    /** 存在结果未知的发送尝试。 */
    public static final int STATUS_PUBLISH_UNKNOWN = 4;
    /** 已决定回滚，等待执行。 */
    public static final int STATUS_ROLLBACK_PENDING = 5;
    /** 回滚脚本正在执行。 */
    public static final int STATUS_ROLLBACK_EXECUTING = 6;
    /** Redis 已恢复，业务失败终态。 */
    public static final int STATUS_ROLLED_BACK = 7;
    /** 消息已经隔离并持久化失败记录。 */
    public static final int STATUS_DLQ = 8;
    /** 自动处理停止，等待人工核对。 */
    public static final int STATUS_MANUAL_REVIEW = 9;

    /** 自动流程的成功终态。 */
    public static boolean isTerminalSuccess(Integer status) {
        return status != null && status == STATUS_CONSUMED;
    }

    /** 自动流程的失败终态。 */
    public static boolean isTerminalFailure(Integer status) {
        return status != null && status == STATUS_ROLLED_BACK;
    }

    /** CONSUMED 和 ROLLED_BACK 是自动终态，禁止任何迟到回调覆盖。 */
    public static boolean isTerminal(Integer status) {
        return isTerminalSuccess(status) || isTerminalFailure(status);
    }
}
