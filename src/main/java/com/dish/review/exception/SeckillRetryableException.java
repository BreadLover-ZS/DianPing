package com.dish.review.exception;

/**
 * 秒杀链路的临时技术异常：数据库连接中断、超时、死锁等瞬时故障。
 *
 * <p>监听容器按可重试处理：有限退避重试，耗尽后进入 DLQ（规格第 9.2 节）。</p>
 */
public class SeckillRetryableException extends RuntimeException {

    /**
     * 使用错误描述构造异常。
     */
    public SeckillRetryableException(String message) {
        super(message);
    }

    /**
     * 使用错误描述和底层原因构造异常。
     */
    public SeckillRetryableException(String message, Throwable cause) {
        super(message, cause);
    }
}
