package com.dish.review.mq;

import com.dish.review.entity.SeckillPublishAttempt;
import com.dish.review.service.SeckillOrderFailureDecisionService;
import com.dish.review.service.SeckillPublishAttemptService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 发布确认超时任务：扫描超过确认超时时间仍为 WAITING 的发送尝试。
 *
 * <p>结果未知的尝试标记为 UNKNOWN，并交由统一失败决策服务安排事件重试；
 * 进程在“创建尝试后、发送前”崩溃产生的孤儿尝试也由本任务收敛。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "dish-review.seckill.tasks-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class SeckillPublishConfirmTimeoutTask {

    private final SeckillPublishAttemptService attemptService;

    private final SeckillOrderFailureDecisionService decisionService;

    /**
     * 确认超时时间（秒）。
     */
    @Value("${dish-review.seckill.confirm-timeout-seconds:30}")
    private int timeoutSeconds;

    /**
     * 每轮最多处理的尝试数。
     */
    @Value("${dish-review.seckill.confirm-timeout-batch-size:100}")
    private int batchSize;

    /**
     * 注入发布尝试服务和统一失败决策服务。
     */
    public SeckillPublishConfirmTimeoutTask(
            SeckillPublishAttemptService attemptService,
            SeckillOrderFailureDecisionService decisionService) {

        this.attemptService = attemptService;
        this.decisionService = decisionService;
    }

    /**
     * 周期扫描 WAITING 超时尝试并标记结果未知。
     */
    @Scheduled(
            fixedDelayString =
                    "${dish-review.seckill.confirm-timeout-scan-delay:5000}"
    )
    public void markTimeoutAttempts() {
        List<SeckillPublishAttempt> attempts;

        try {
            attempts = attemptService.findWaitingTimeout(
                    timeoutSeconds,
                    batchSize
            );
        } catch (Exception exception) {
            log.error("确认超时扫描任务查询失败", exception);
            return;
        }

        for (SeckillPublishAttempt attempt : attempts) {
            try {
                markOneTimeout(attempt);
            } catch (Exception exception) {
                log.error(
                        "确认超时任务处理尝试失败，eventId={}，attemptId={}",
                        attempt.getEventId(),
                        attempt.getAttemptId(),
                        exception
                );
            }
        }
    }

    /**
     * 单个超时尝试：标记 UNKNOWN，再由决策服务安排事件补偿发布。
     */
    private void markOneTimeout(SeckillPublishAttempt attempt) {
        String errorMessage =
                "confirm not received within " + timeoutSeconds + "s";

        boolean recorded = attemptService.recordUnknown(
                attempt.getAttemptId(),
                "confirm_timeout",
                errorMessage
        );

        if (!recorded) {
            // 尝试已被 Confirm 回调更新，按幂等成功处理
            log.debug(
                    "确认超时标记未命中（尝试已收敛），eventId={}，attemptId={}",
                    attempt.getEventId(),
                    attempt.getAttemptId()
            );
            return;
        }

        log.warn(
                "发布确认超时，结果未知，eventId={}，attemptId={}，timeout={}s",
                attempt.getEventId(),
                attempt.getAttemptId(),
                timeoutSeconds
        );

        decisionService.evaluateForRetry(
                attempt.getEventId(),
                "confirm_timeout",
                errorMessage
        );
    }
}
