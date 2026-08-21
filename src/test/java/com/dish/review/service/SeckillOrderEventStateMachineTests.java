package com.dish.review.service;

import com.dish.review.entity.SeckillOrderEvent;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证事件主状态机的合法迁移约束（规格 6.2 节）。
 *
 * <p>核心回归点：消费可能早于生产者 Confirm，
 * PENDING/PUBLISH_UNKNOWN 必须允许迁入 DLQ，
 * 否则失败记录落库后事件仍会被 Outbox 重发。</p>
 */
class SeckillOrderEventStateMachineTests {

    @Test
    void consumptionBeforeConfirmCanDriveEventToDlq() {
        // 消费者收到消息本身就是可靠投递证据
        assertTrue(SeckillOrderEventStateMachine.canTransition(
                SeckillOrderEvent.STATUS_PENDING,
                SeckillOrderEvent.STATUS_DLQ));
        assertTrue(SeckillOrderEventStateMachine.canTransition(
                SeckillOrderEvent.STATUS_PUBLISH_UNKNOWN,
                SeckillOrderEvent.STATUS_DLQ));
    }

    @Test
    void dlqSourcesIncludeUnconfirmedStates() {
        Set<Integer> sources = SeckillOrderEventStateMachine.allowedSources(
                SeckillOrderEvent.STATUS_DLQ);

        assertTrue(sources.contains(SeckillOrderEvent.STATUS_PENDING));
        assertTrue(sources.contains(
                SeckillOrderEvent.STATUS_PUBLISH_UNKNOWN));
        assertTrue(sources.contains(SeckillOrderEvent.STATUS_CONFIRMED));
        // DLQ 自身迁入 DLQ 不允许（无意义自环）
        assertFalse(sources.contains(SeckillOrderEvent.STATUS_DLQ));
    }

    @Test
    void pendingCanReachAllAutomaticTargets() {
        Set<Integer> targets = SeckillOrderEventStateMachine.allowedTargets(
                SeckillOrderEvent.STATUS_PENDING);

        assertTrue(targets.contains(SeckillOrderEvent.STATUS_CONFIRMED));
        assertTrue(targets.contains(SeckillOrderEvent.STATUS_CONSUMED));
        assertTrue(targets.contains(
                SeckillOrderEvent.STATUS_PUBLISH_UNKNOWN));
        assertTrue(targets.contains(
                SeckillOrderEvent.STATUS_ROLLBACK_PENDING));
        assertTrue(targets.contains(SeckillOrderEvent.STATUS_DLQ));
        assertTrue(targets.contains(SeckillOrderEvent.STATUS_MANUAL_REVIEW));
    }

    @Test
    void terminalStatesHaveNoAutomaticExits() {
        assertTrue(SeckillOrderEventStateMachine.allowedTargets(
                SeckillOrderEvent.STATUS_CONSUMED).isEmpty());
        assertTrue(SeckillOrderEventStateMachine.allowedTargets(
                SeckillOrderEvent.STATUS_ROLLED_BACK).isEmpty());
    }

    @Test
    void manualReviewExitsOnlyViaManualDisposal() {
        // 自动任务禁止把 MANUAL_REVIEW 迁走
        assertFalse(SeckillOrderEventStateMachine.canTransition(
                SeckillOrderEvent.STATUS_MANUAL_REVIEW,
                SeckillOrderEvent.STATUS_PENDING));
        assertFalse(SeckillOrderEventStateMachine.canTransition(
                SeckillOrderEvent.STATUS_MANUAL_REVIEW,
                SeckillOrderEvent.STATUS_ROLLBACK_PENDING));

        // 人工处置允许重放或回滚
        assertTrue(SeckillOrderEventStateMachine.canTransitionManually(
                SeckillOrderEvent.STATUS_MANUAL_REVIEW,
                SeckillOrderEvent.STATUS_PENDING));
        assertTrue(SeckillOrderEventStateMachine.canTransitionManually(
                SeckillOrderEvent.STATUS_MANUAL_REVIEW,
                SeckillOrderEvent.STATUS_ROLLBACK_PENDING));
    }
}
