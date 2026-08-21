package com.dish.review.service;

import com.dish.review.entity.SeckillOrderEvent;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 秒杀订单事件主状态机（纯函数）。
 *
 * <p>定义规格 6.2 节允许的状态迁移；所有自动状态更新必须先经过本类校验，
 * 再配合带当前状态条件的 UPDATE（CAS）落库，禁止无条件覆盖。
 * MANUAL_REVIEW 的出边仅允许人工处置 Service 调用，自动任务禁止使用。</p>
 */
public final class SeckillOrderEventStateMachine {

    /** 自动流程允许的迁移表。 */
    private static final Map<Integer, Set<Integer>> AUTOMATIC_TRANSITIONS =
            new HashMap<>();

    /** 仅人工处置允许的迁移表（规格 6.1：人工审核后执行重放或回滚）。 */
    private static final Map<Integer, Set<Integer>> MANUAL_TRANSITIONS =
            new HashMap<>();

    static {
        /*
         * PENDING/PUBLISH_UNKNOWN 允许迁入 DLQ：消费可能早于生产者 Confirm
         * （消息已到队列并被消费），消费重试耗尽时事件可能仍处于这两个状态。
         * “消费者已经收到消息”本身就是可靠投递证据，
         * 必须允许它驱动事件进入 DLQ，否则失败记录落库后事件仍会被
         * Outbox 重发，形成重复消费、重复死信。
         */
        addAutomatic(SeckillOrderEvent.STATUS_PENDING,
                SeckillOrderEvent.STATUS_CONFIRMED,
                SeckillOrderEvent.STATUS_CONSUMED,
                SeckillOrderEvent.STATUS_PUBLISH_UNKNOWN,
                SeckillOrderEvent.STATUS_ROLLBACK_PENDING,
                SeckillOrderEvent.STATUS_DLQ,
                SeckillOrderEvent.STATUS_MANUAL_REVIEW);

        addAutomatic(SeckillOrderEvent.STATUS_PUBLISH_UNKNOWN,
                SeckillOrderEvent.STATUS_CONFIRMED,
                SeckillOrderEvent.STATUS_CONSUMED,
                SeckillOrderEvent.STATUS_ROLLBACK_PENDING,
                SeckillOrderEvent.STATUS_DLQ,
                SeckillOrderEvent.STATUS_MANUAL_REVIEW);

        addAutomatic(SeckillOrderEvent.STATUS_CONFIRMED,
                SeckillOrderEvent.STATUS_CONSUMED,
                SeckillOrderEvent.STATUS_DLQ,
                SeckillOrderEvent.STATUS_MANUAL_REVIEW);

        addAutomatic(SeckillOrderEvent.STATUS_DLQ,
                SeckillOrderEvent.STATUS_PENDING,
                SeckillOrderEvent.STATUS_CONSUMED,
                SeckillOrderEvent.STATUS_ROLLBACK_PENDING,
                SeckillOrderEvent.STATUS_MANUAL_REVIEW);

        // PENDING 出边是消费事务内“CAS 取消回滚”的中间步骤（规格第 10 节），
        // 与后续 CONSUMED 同事务提交；事务回滚时状态自动恢复。
        addAutomatic(SeckillOrderEvent.STATUS_ROLLBACK_PENDING,
                SeckillOrderEvent.STATUS_ROLLBACK_EXECUTING,
                SeckillOrderEvent.STATUS_PENDING,
                SeckillOrderEvent.STATUS_CONFIRMED,
                SeckillOrderEvent.STATUS_CONSUMED,
                SeckillOrderEvent.STATUS_MANUAL_REVIEW);

        addAutomatic(SeckillOrderEvent.STATUS_ROLLBACK_EXECUTING,
                SeckillOrderEvent.STATUS_ROLLED_BACK,
                SeckillOrderEvent.STATUS_ROLLBACK_PENDING,
                SeckillOrderEvent.STATUS_MANUAL_REVIEW);

        // 人工审核后的重放和回滚（规格第 13 节管理接口语义）
        addManual(SeckillOrderEvent.STATUS_MANUAL_REVIEW,
                SeckillOrderEvent.STATUS_PENDING,
                SeckillOrderEvent.STATUS_ROLLBACK_PENDING);
    }

    private SeckillOrderEventStateMachine() {
    }

    /** 判断自动任务是否可以把事件从 from 状态迁移到 to 状态。 */
    public static boolean canTransition(int from, int to) {
        Set<Integer> targets = AUTOMATIC_TRANSITIONS.get(from);
        return targets != null && targets.contains(to);
    }

    /** 判断人工处置是否可以把事件从 from 状态迁移到 to 状态。 */
    public static boolean canTransitionManually(int from, int to) {
        return canTransition(from, to)
                || (MANUAL_TRANSITIONS.getOrDefault(
                from, Collections.emptySet()).contains(to));
    }

    /** 返回某状态在自动流程中的全部合法去向（不可变）。 */
    public static Set<Integer> allowedTargets(int from) {
        Set<Integer> targets = AUTOMATIC_TRANSITIONS.get(from);
        return targets == null
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new HashSet<>(targets));
    }

    /** 返回自动流程中允许迁入目标状态的全部来源。 */
    public static Set<Integer> allowedSources(int to) {
        Set<Integer> sources = new HashSet<>();
        for (Map.Entry<Integer, Set<Integer>> entry
                : AUTOMATIC_TRANSITIONS.entrySet()) {
            if (entry.getValue().contains(to)) {
                sources.add(entry.getKey());
            }
        }
        return sources;
    }

    private static void addAutomatic(int from, int... tos) {
        Set<Integer> targets = new HashSet<>();
        for (int to : tos) {
            targets.add(to);
        }
        AUTOMATIC_TRANSITIONS.put(from, targets);
    }

    private static void addManual(int from, int... tos) {
        Set<Integer> targets = new HashSet<>();
        for (int to : tos) {
            targets.add(to);
        }
        MANUAL_TRANSITIONS.put(from, targets);
    }
}
