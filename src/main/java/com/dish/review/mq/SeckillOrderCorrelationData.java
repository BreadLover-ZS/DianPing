package com.dish.review.mq;

import com.dish.review.dto.SeckillOrderMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;

/**
 * 发布确认关联数据；用 eventId 匹配回调，并保留 Redis 回滚所需业务 ID。
 */
public class SeckillOrderCorrelationData extends CorrelationData {

    private final Long orderId;
    private final Long userId;
    private final Long voucherId;

    /**
     * 从待发布消息复制事件 ID、订单 ID、用户 ID 和优惠券 ID。
     */
    public SeckillOrderCorrelationData(SeckillOrderMessage message) {
        super(message.getEventId());

        this.orderId = message.getOrderId();
        this.userId = message.getUserId();
        this.voucherId = message.getVoucherId();
    }

    /** 返回 Redis 回滚所需的用户 ID。 */
    public Long getUserId() {
        return userId;
    }

    /** 返回日志和订单定位所需的订单 ID。 */
    public Long getOrderId() {
        return orderId;
    }

    /** 返回 Redis 回滚所需的优惠券 ID。 */
    public Long getVoucherId() {
        return voucherId;
    }
}
