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
 * DLQ、回滚异常和对账冲突的持久化失败事实，是人工处置的唯一入口。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_seckill_failure_case")
public class SeckillFailureCase implements Serializable {

    @TableId(value = "failure_id", type = IdType.ASSIGN_ID)
    private Long failureId;

    /** 防止同一失败被重复落库的唯一键。 */
    private String idempotencyKey;

    private String eventId;

    private Long orderId;

    private Long userId;

    private Long voucherId;

    /** PUBLISH、CONSUMER_DLQ、ROLLBACK、RECONCILE。 */
    private String source;

    /** OPEN、REPLAYED、ROLLED_BACK、CLOSED、MANUAL。 */
    private String status;

    private String errorCode;

    private String errorMessage;

    /** 受限长度的消息摘要。 */
    private String messagePayload;

    private String xDeathInfo;

    private Integer replayCount;

    private LocalDateTime nextActionTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    public static final String SOURCE_PUBLISH = "PUBLISH";
    public static final String SOURCE_CONSUMER_DLQ = "CONSUMER_DLQ";
    public static final String SOURCE_ROLLBACK = "ROLLBACK";
    public static final String SOURCE_RECONCILE = "RECONCILE";

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_REPLAYED = "REPLAYED";
    public static final String STATUS_ROLLED_BACK = "ROLLED_BACK";
    public static final String STATUS_CLOSED = "CLOSED";
    public static final String STATUS_MANUAL = "MANUAL";
}
