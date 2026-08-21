package com.dish.review.exception;

/**
 * 秒杀链路的永久消息异常：字段缺失、版本不受支持、反序列化失败等消息本身错误。
 *
 * <p>重试无法修复消息内容：不做重试，直接进入 DLQ 并持久化失败记录
 * （规格第 9.2 节）。</p>
 */
public class SeckillPermanentMessageException extends RuntimeException {

    /**
     * 使用错误描述构造异常。
     */
    public SeckillPermanentMessageException(String message) {
        super(message);
    }

    /**
     * 使用错误描述和底层原因构造异常。
     */
    public SeckillPermanentMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
