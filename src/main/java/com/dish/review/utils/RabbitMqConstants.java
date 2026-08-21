package com.dish.review.utils;


/**
 * 集中维护秒杀订单消息 Header、交换机、队列和路由键名称。
 */
public final class RabbitMqConstants {

    /**
     * 常量类不允许实例化。
     */
    private RabbitMqConstants() {
    }

    public static final String SECKILL_ORDER_EVENT_ID_HEADER = "eventId";
    public static final String SECKILL_ORDER_ID_HEADER = "orderId";
    public static final String SECKILL_ORDER_USER_ID_HEADER = "userId";
    public static final String SECKILL_ORDER_VOUCHER_ID_HEADER = "voucherId";
    public static final String SECKILL_ORDER_ATTEMPT_ID_HEADER = "attemptId";

    public static final String SECKILL_ORDER_EXCHANGE = "dianping.seckill.direct";
    public static final String SECKILL_ORDER_QUEUE = "dianping.seckill.order.queue";
    public static final String SECKILL_ORDER_ROUTING_KEY = "seckill.order.create";

    public static final String SECKILL_ORDER_DEAD_LETTER_EXCHANGE = "dianping.seckill.dlx";
    public static final String SECKILL_ORDER_DEAD_LETTER_QUEUE = "dianping.seckill.order.dlq";
    public static final String SECKILL_ORDER_DEAD_LETTER_ROUTING_KEY = "seckill.order.dead";
}
