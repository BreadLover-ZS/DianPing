package com.dish.review.service;

/**
 * 秒杀事件回滚重试策略（纯函数，规格第 11 节）。
 *
 * <p>回滚失败按 5 秒、30 秒、5 分钟、30 分钟退避；
 * 第 5 次失败后停止自动执行，由调用方转入 MANUAL_REVIEW 并持久化失败记录。
 * 语义与 {@link SeckillPublishRetryPolicy} 一致：
 * 第 N 次失败后退避 BACKOFF_SECONDS[N-1]。</p>
 */
public final class SeckillRollbackRetryPolicy {

    /** 回滚退避秒数。 */
    private static final long[] BACKOFF_SECONDS = {5L, 30L, 300L, 1800L};

    /** 停止自动重试的哨兵值。 */
    public static final long STOP_AUTOMATIC_RETRY = -1L;

    private SeckillRollbackRetryPolicy() {
    }

    /**
     * 根据已执行的回滚次数计算下一次退避秒数。
     *
     * <p>第 1 次失败（completedAttempts=1）后退避 5 秒，
     * 第 2 次后退避 30 秒，第 3 次后退避 5 分钟，第 4 次后退避 30 分钟；
     * 第 5 次失败（completedAttempts=5）返回 -1，表示转 MANUAL_REVIEW。</p>
     *
     * @param completedAttempts 已经执行过的回滚次数（含首次，至少为 1）
     * @return 下一次退避秒数；返回 -1 表示停止自动执行
     */
    public static long nextDelaySeconds(int completedAttempts) {
        if (completedAttempts < 1) {
            completedAttempts = 1;
        }

        int index = completedAttempts - 1;

        return index < BACKOFF_SECONDS.length
                ? BACKOFF_SECONDS[index]
                : STOP_AUTOMATIC_RETRY;
    }

    /**
     * 最大自动回滚尝试次数。
     *
     * <p>4 个退避档位 + 首次执行 = 5 次自动回滚，
     * 第 5 次失败后转 MANUAL_REVIEW。</p>
     */
    public static int maxAutomaticAttempts() {
        return BACKOFF_SECONDS.length + 1;
    }
}
