package com.dish.review.mq;

import org.springframework.amqp.rabbit.connection.CorrelationData;

/**
 * 发布确认关联数据。
 *
 * <p>id 使用 attemptId：每次实际 convertAndSend 都有独立关联，
 * Confirm Future 完成时可同时读取 Confirm 结果和 ReturnedMessage，
 * 不会把多次发送的确认混在同一个 eventId 上。</p>
 */
public class SeckillOrderCorrelationData extends CorrelationData {

    private final String eventId;

    private final Long orderId;

    /**
     * 以 attemptId 作为关联 ID，同时保留 eventId 供回调定位事件。
     */
    public SeckillOrderCorrelationData(
            String attemptId,
            String eventId,
            Long orderId) {

        super(attemptId);

        this.eventId = eventId;
        this.orderId = orderId;
    }

    /**
     * 返回本次发送所属的事件 ID。
     */
    public String getEventId() {
        return eventId;
    }

    /**
     * 返回日志和订单定位所需的订单 ID。
     */
    public Long getOrderId() {
        return orderId;
    }
}
