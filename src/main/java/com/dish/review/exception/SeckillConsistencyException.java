package com.dish.review.exception;

/**
 * 秒杀链路的一致性冲突：Redis 与 MySQL 库存不一致、事件状态与订单事实矛盾等。
 *
 * <p>相同业务的重复重试无法解决冲突：不做相同业务重试，
 * 持久化失败记录并进入人工核对（规格第 9.2 节）。</p>
 */
public class SeckillConsistencyException extends RuntimeException {

    /**
     * 使用错误描述构造异常。
     */
    public SeckillConsistencyException(String message) {
        super(message);
    }

    /**
     * 使用错误描述和底层原因构造异常。
     */
    public SeckillConsistencyException(String message, Throwable cause) {
        super(message, cause);
    }
}
