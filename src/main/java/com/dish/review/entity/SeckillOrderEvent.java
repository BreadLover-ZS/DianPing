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
 * 秒杀消息事件持久化记录，用状态和重试时间连接数据库与 RabbitMQ。
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

    /** 等待发布确认或补偿重发。 */
    public static final int STATUS_PENDING = 0;
    /** RabbitMQ 已接收消息，但业务订单尚未确认完成。 */
    public static final int STATUS_CONFIRMED = 1;
    /** 消费事务已成功完成。 */
    public static final int STATUS_CONSUMED = 2;
    /** 发布或消费已确定失败，需要人工检查。 */
    public static final int STATUS_FAILED = 3;
    /** 快速发布补偿耗尽，但仍无法确定消息是否已到达 RabbitMQ。 */
    public static final int STATUS_PUBLISH_UNKNOWN = 4;

}
