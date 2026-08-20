package com.dish.review.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 秒杀订单消息体；生产、补偿和消费始终传递同一组业务标识。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeckillOrderMessage {
    /**
     * 消息事件唯一标识，用于日志追踪。
     */
    private String eventId;

    /**
     * 订单 ID，也是订单落库时的主键。
     */
    private Long orderId;

    /**
     * 下单用户 ID。
     */
    private Long userId;

    /**
     * 秒杀券 ID。
     */
    private Long voucherId;

    /**
     * 消息创建时间，使用 Unix 毫秒时间戳。
     */
    private Long createdAt;

    /**
     * 消息结构版本，当前为 1。
     */
    private Integer version;
}
