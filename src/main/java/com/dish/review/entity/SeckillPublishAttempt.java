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
 * 一次实际 RabbitMQ 发送的独立证据；Confirm、Return 和同步异常分别落列，不互相覆盖。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_seckill_publish_attempt")
public class SeckillPublishAttempt implements Serializable {

    @TableId(value = "attempt_id", type = IdType.INPUT)
    private String attemptId;

    private String eventId;

    /** 事件内发送序号，与事件 retry_count 对应。 */
    private Integer attemptNo;

    private Integer confirmStatus;

    private Boolean returned;

    private Boolean sendException;

    private String errorCode;

    private String errorMessage;

    private LocalDateTime sentAt;

    private LocalDateTime confirmAt;

    private LocalDateTime returnAt;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** Broker 尚未返回 Confirm。 */
    public static final int CONFIRM_WAITING = 0;
    /** Broker 确认接收。 */
    public static final int CONFIRM_ACK = 1;
    /** Broker 明确拒绝。 */
    public static final int CONFIRM_NACK = 2;
    /** 同步异常、连接关闭或确认超时，结果未知。 */
    public static final int CONFIRM_UNKNOWN = 3;
}
