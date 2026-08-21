package com.dish.review.service;

/**
 * 秒杀事件发布重试策略（纯函数，规格 9.1 节）。
 *
 * <p>第 1 次发送后按 1、2、4 秒快速重试；之后按 30 秒、2 分钟、10 分钟、
 * 30 分钟慢速补偿；全部退避轮次用尽（已完成发送达到
 * {@link #maxAutomaticAttempts()}）返回 {@link #STOP_AUTOMATIC_RETRY}。</p>
 *
 * <p>到达 STOP 不代表可以立即停止自动流程：最后一次发送的 Confirm/Return
 * 可能尚未返回，必须先等待 {@link #FINAL_DECISION_WAIT_SECONDS} 的终局窗口
 * （必须大于确认超时时间），窗口内事件仍未收敛才由 Outbox 扫描统一转人工。</p>
 */
public final class SeckillPublishRetryPolicy {

    /** 快速重试退避秒数。 */
    private static final long[] FAST_BACKOFF_SECONDS = {1L, 2L, 4L};

    /** 慢速补偿退避秒数。 */
    private static final long[] SLOW_BACKOFF_SECONDS = {30L, 120L, 600L, 1800L};

    /** 停止自动重试的哨兵值。 */
    public static final long STOP_AUTOMATIC_RETRY = -1L;

    /**
     * 最后一次发送后的终局等待秒数。
     *
     * <p>语义：等待该次发送的 Confirm/Return 回调或确认超时任务先收敛结果，
     * 到期后事件仍无进展（PENDING/PUBLISH_UNKNOWN）才允许转 MANUAL_REVIEW。
     * 必须大于 {@code dish-review.seckill.confirm-timeout-seconds}（默认 30 秒），
     * 调大确认超时时需同步评估本值。</p>
     */
    public static final long FINAL_DECISION_WAIT_SECONDS = 90L;

    private SeckillPublishRetryPolicy() {
    }

    /**
     * 计算已完成 {@code completedAttempts} 次发送后的下一次退避秒数。
     *
     * <p>第 1 次发送（completedAttempts=1）后退避 1 秒，
     * 第 2 次后退避 2 秒，依此类推；
     * 完成次数达到 {@link #maxAutomaticAttempts()} 时返回 -1，
     * 表示不应再发起下一次发送。</p>
     *
     * @param completedAttempts 已经执行过的发送次数（含首次发送，至少为 1）
     * @return 下一次重试的退避秒数；返回 -1 表示停止自动重试
     */
    public static long nextDelaySeconds(int completedAttempts) {
        if (completedAttempts < 1) {
            completedAttempts = 1;
        }

        int index = completedAttempts - 1;
        int fastCount = FAST_BACKOFF_SECONDS.length;
        int slowCount = SLOW_BACKOFF_SECONDS.length;

        if (index < fastCount) {
            return FAST_BACKOFF_SECONDS[index];
        }

        int slowIndex = index - fastCount;
        if (slowIndex < slowCount) {
            return SLOW_BACKOFF_SECONDS[slowIndex];
        }

        return STOP_AUTOMATIC_RETRY;
    }

    /**
     * 最大自动发送次数（首次发送 + 全部退避轮次的重发）。
     * 已完成发送次数达到该值后返回 STOP。
     */
    public static int maxAutomaticAttempts() {
        return FAST_BACKOFF_SECONDS.length + SLOW_BACKOFF_SECONDS.length + 1;
    }
}
