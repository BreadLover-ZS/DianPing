package com.dish.review.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";

    public static final Long LOGIN_USER_TTL = 30L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";

    public static final String SECKILL_ORDER_KEY = "seckill:order:";

    /** 预留详情 Hash 前缀：eventId -> orderId|userId|createdAt|messageVersion。 */
    public static final String SECKILL_RESERVATION_KEY = "seckill:reservation:";

    /** 用户事件映射 Hash 前缀：userId -> eventId。 */
    public static final String SECKILL_RESERVATION_USER_KEY =
            "seckill:reservation:user:";

    /** 待对账 ZSet 前缀：eventId -> reservedAt。 */
    public static final String SECKILL_RESERVATION_PENDING_KEY =
            "seckill:reservation:pending:";

    /**
     * orderId 反向索引 Hash 前缀（拼接 {voucherId}）：
     * field = orderId，value = eventId。
     *
     * <p>订单状态查询用它直接定位未收敛预留，不依赖秒杀券的活动时间范围。
     * 与预留账本其余五个 Key 使用同一 {voucherId} Hash Tag，
     * Redis Cluster 下同槽，由预留/回滚/完成 Lua 同脚本原子维护。</p>
     */
    public static final String SECKILL_RESERVATION_ORDER_KEY =
            "seckill:reservation:order:";

    /**
     * 人工处理 ZSet 前缀（拼接 {voucherId}）：eventId -> 移交时间。
     *
     * <p>对账任务发现信息不完整的异常预留并成功写入人工失败单后，
     * 通过 {@code seckill_reservation_manual.lua} 原子移出待对账 ZSet
     * 并转入本集合：异常记录不再阻塞排在其后的正常预留，
     * 后续由人工依据失败单处置收敛。</p>
     */
    public static final String SECKILL_RESERVATION_MANUAL_KEY =
            "seckill:reservation:manual:";

    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";

    /**
     * 关注集合 Key 前缀，存储某个用户关注的人的 id 集合（Set 结构）
     * 用于共同关注（求交集）等场景
     */
    public static final String FOLLOWS_KEY = "follows:";
}
