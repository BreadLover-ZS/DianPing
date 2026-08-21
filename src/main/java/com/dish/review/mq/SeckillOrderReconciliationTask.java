package com.dish.review.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dish.review.dto.SeckillOrderMessage;
import com.dish.review.entity.SeckillFailureCase;
import com.dish.review.entity.SeckillOrderEvent;
import com.dish.review.entity.SeckillVoucher;
import com.dish.review.entity.VoucherOrder;
import com.dish.review.mapper.VoucherOrderMapper;
import com.dish.review.service.ISeckillVoucherService;
import com.dish.review.service.SeckillFailureCaseService;
import com.dish.review.service.SeckillOrderEventService;
import com.dish.review.service.SeckillVoucherLuaExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Redis/MySQL 对账任务（规格第 12 节），分批处理，禁止全量 Scan 阻塞 Redis。
 *
 * <p>方向一（预留→MySQL）采用“快速扫描 + 永久兜底扫描”两层结构：
 * 快速任务每分钟处理结束时间回看窗口（默认 7 天）内的券，负责效率；
 * 安全任务每小时游标分页遍历全部 {@code tb_seckill_voucher}，
 * 保证故障持续超过回看窗口的孤儿预留仍有发现入口，负责最终一致性。</p>
 *
 * <p>方向二（MySQL→Redis）：CONSUMED 事件执行预留完成脚本；
 * ROLLED_BACK 事件确认预留已清理；ROLLBACK_EXECUTING 卡死事件
 * 依据 Redis 预留是否仍存在安全收敛；长期 PUBLISH_UNKNOWN 转人工并告警。</p>
 *
 * <p>同时输出规格第 19 节要求的关键指标日志（各状态事件数量、OPEN 失败记录数）。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "dish-review.seckill.tasks-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class SeckillOrderReconciliationTask {

    private final ISeckillVoucherService seckillVoucherService;

    private final SeckillOrderEventService eventService;

    private final SeckillVoucherLuaExecutor luaExecutor;

    private final VoucherOrderMapper voucherOrderMapper;

    private final SeckillFailureCaseService failureCaseService;

    /**
     * 预留超过该分钟数仍未收敛即视为孤儿，进入对账。
     */
    @Value("${dish-review.seckill.reconcile.reservation-threshold-minutes:30}")
    private int reservationThresholdMinutes;

    /**
     * 每个券单轮最多处理的预留数。
     */
    @Value("${dish-review.seckill.reconcile.reservation-batch-size:50}")
    private int reservationBatchSize;

    /**
     * 事件→Redis 对账回看的时间窗口（分钟）。
     */
    @Value("${dish-review.seckill.reconcile.event-window-minutes:60}")
    private int eventWindowMinutes;

    /**
     * 每轮最多处理的事件数。
     */
    @Value("${dish-review.seckill.reconcile.event-batch-size:100}")
    private int eventBatchSize;

    /**
     * ROLLBACK_EXECUTING 卡死判定的分钟数。
     */
    @Value("${dish-review.seckill.reconcile.rollback-stuck-minutes:10}")
    private int rollbackStuckMinutes;

    /**
     * PUBLISH_UNKNOWN 最大存活小时数，超过转 MANUAL_REVIEW。
     */
    @Value("${dish-review.seckill.reconcile.publish-unknown-max-hours:24}")
    private int publishUnknownMaxHours;

    /**
     * 孤儿预留对账的券回看天数：覆盖“预留成功后活动已结束”的券。
     *
     * <p>禁止只查活动中的券：Lua 预留成功但事件写库失败后活动结束的预留
     * 也必须被对账，否则永远无人处理。回看窗口必须远大于
     * reservation-threshold-minutes，保证每个孤儿预留有多次对账机会。
     * 超过回看窗口的存量孤儿由每小时的安全兜底扫描负责。</p>
     */
    @Value("${dish-review.seckill.reconcile.reservation-voucher-lookback-days:7}")
    private int reservationVoucherLookbackDays;

    /**
     * 安全兜底扫描的 Cron 表达式（默认每小时整点）。
     *
     * <p>兜底扫描游标分页遍历全部 {@code tb_seckill_voucher}：
     * 故障持续超过回看窗口后，历史券上的孤儿预留仍有发现入口。
     * 7 天回看窗口负责效率，全量分页兜底负责最终一致性。</p>
     */
    @Value("${dish-review.seckill.reconcile.safety-scan-cron:0 0 * * * *}")
    private String safetyScanCron;

    /**
     * 安全兜底扫描的单页券数量（游标分页，禁止一次加载全表）。
     */
    @Value("${dish-review.seckill.reconcile.safety-scan-page-size:100}")
    private int safetyScanPageSize;

    /**
     * 注入秒杀券服务、事件服务、Lua 执行器、订单 Mapper 和失败记录服务。
     */
    public SeckillOrderReconciliationTask(
            ISeckillVoucherService seckillVoucherService,
            SeckillOrderEventService eventService,
            SeckillVoucherLuaExecutor luaExecutor,
            VoucherOrderMapper voucherOrderMapper,
            SeckillFailureCaseService failureCaseService) {
        this.seckillVoucherService = seckillVoucherService;
        this.eventService = eventService;
        this.luaExecutor = luaExecutor;
        this.voucherOrderMapper = voucherOrderMapper;
        this.failureCaseService = failureCaseService;
    }

    /**
     * 周期执行双向对账并输出指标日志。
     */
    @Scheduled(
            fixedDelayString =
                    "${dish-review.seckill.reconcile.scan-delay:60000}"
    )
    public void reconcile() {
        reconcilePendingReservations();
        reconcileEventsToRedis();
        emitMetrics();
    }

    /**
     * 方向一快速任务：Redis 孤儿预留 → MySQL（规格 12.1 节）。
     *
     * <p>券范围使用“结束时间回看窗口”（默认 7 天）而不是活动中的券：
     * 已结束活动的孤儿预留同样必须被对账收敛。
     * 超过回看窗口的历史券由 {@link #reconcileAllVouchersSafely()} 兜底。</p>
     */
    private void reconcilePendingReservations() {
        List<SeckillVoucher> candidateVouchers;

        try {
            LambdaQueryWrapper<SeckillVoucher> query =
                    new LambdaQueryWrapper<>();
            // 覆盖未结束 + 回看窗口内已结束的券；不再限制 begin_time
            query.ge(SeckillVoucher::getEndTime,
                    java.time.LocalDateTime.now().minusDays(
                            Math.max(1, reservationVoucherLookbackDays)));

            candidateVouchers = seckillVoucherService.list(query);
        } catch (Exception exception) {
            log.error("对账任务查询待对账秒杀券失败", exception);
            return;
        }

        if (candidateVouchers.isEmpty()) {
            return;
        }

        long reservedBeforeMillis = System.currentTimeMillis()
                - reservationThresholdMinutes * 60_000L;

        for (SeckillVoucher voucher : candidateVouchers) {
            try {
                reconcileOneVoucher(
                        voucher.getVoucherId(), reservedBeforeMillis);
            } catch (Exception exception) {
                log.error(
                        "对账任务处理秒杀券失败，voucherId={}",
                        voucher.getVoucherId(),
                        exception
                );
            }
        }
    }

    /**
     * 方向一安全兜底任务：游标分页遍历全部 {@code tb_seckill_voucher}。
     *
     * <p>快速任务的回看窗口负责效率；本任务负责最终一致性：
     * 故障持续超过回看窗口（如 MySQL 长时间不可用超过 7 天）后，
     * 历史券上的孤儿预留仍有发现入口，不会永久丢失。
     * 每个券只读取待对账 ZSet，继续使用 reservation-threshold-minutes
     * 过滤未到期预留。禁止使用 Redis KEYS 或全量 SCAN 作为兜底。</p>
     */
    @Scheduled(cron = "${dish-review.seckill.reconcile.safety-scan-cron:0 0 * * * *}")
    public void reconcileAllVouchersSafely() {
        long reservedBeforeMillis = System.currentTimeMillis()
                - reservationThresholdMinutes * 60_000L;

        int pageSize = Math.max(1, safetyScanPageSize);
        Long cursor = 0L;
        long scannedVouchers = 0;

        while (true) {
            List<SeckillVoucher> page;

            try {
                LambdaQueryWrapper<SeckillVoucher> query =
                        new LambdaQueryWrapper<>();
                query.gt(SeckillVoucher::getVoucherId, cursor)
                        .orderByAsc(SeckillVoucher::getVoucherId)
                        .last("LIMIT " + pageSize);

                page = seckillVoucherService.list(query);
            } catch (Exception exception) {
                log.error(
                        "安全兜底扫描查询秒杀券失败，cursor={}",
                        cursor,
                        exception
                );
                return;
            }

            if (page.isEmpty()) {
                break;
            }

            for (SeckillVoucher voucher : page) {
                cursor = voucher.getVoucherId();
                scannedVouchers++;

                try {
                    reconcileOneVoucher(
                            voucher.getVoucherId(), reservedBeforeMillis);
                } catch (Exception exception) {
                    log.error(
                            "安全兜底扫描处理秒杀券失败，voucherId={}",
                            voucher.getVoucherId(),
                            exception
                    );
                }
            }

            // 不足一页说明已到最后一页；恰好整页时下一轮空页终止，不遗漏
            if (page.size() < pageSize) {
                break;
            }
        }

        log.info(
                "[SECKILL_SAFETY_SCAN] 安全兜底扫描完成，扫描券数={}，"
                        + "回看窗口外的历史券已覆盖",
                scannedVouchers
        );
    }

    /**
     * 处理单个券的孤儿预留。
     */
    private void reconcileOneVoucher(
            Long voucherId, long reservedBeforeMillis) {

        java.util.Set<String> pendingEventIds =
                luaExecutor.findPendingReservationEventIds(
                        voucherId,
                        reservedBeforeMillis,
                        reservationBatchSize);

        if (pendingEventIds.isEmpty()) {
            return;
        }

        for (String eventId : pendingEventIds) {
            try {
                reconcileOneReservation(voucherId, eventId);
            } catch (Exception exception) {
                log.error(
                        "对账任务处理孤儿预留失败，voucherId={}，eventId={}",
                        voucherId,
                        eventId,
                        exception
                );
            }
        }
    }

    /**
     * 收敛单个孤儿预留。
     */
    private void reconcileOneReservation(Long voucherId, String eventId) {
        String detail = luaExecutor.getReservationDetail(voucherId, eventId);

        if (detail == null || detail.trim().isEmpty()) {
            // 预留详情缺失但 ZSet 成员仍在：信息不完整，写失败记录转人工
            recordIncompleteReservation(voucherId, eventId, detail);
            return;
        }

        String[] parts = detail.split("\\|");

        if (parts.length < 4) {
            recordIncompleteReservation(voucherId, eventId, detail);
            return;
        }

        Long orderId;
        Long userId;
        Long createdAt;
        Integer messageVersion;

        try {
            orderId = Long.valueOf(parts[0]);
            userId = Long.valueOf(parts[1]);
            createdAt = Long.valueOf(parts[2]);
            messageVersion = Integer.valueOf(parts[3]);
        } catch (NumberFormatException exception) {
            recordIncompleteReservation(voucherId, eventId, detail);
            return;
        }

        // 订单已存在：收敛事件为 CONSUMED，清理预留详情但保留用户集合
        VoucherOrder order = voucherOrderMapper.selectById(orderId);

        if (order != null) {
            eventService.createPendingIfAbsent(buildMessage(
                    eventId, orderId, userId, voucherId,
                    createdAt, messageVersion));
            eventService.markConsumed(eventId);

            Long completeResult = luaExecutor.completeReservation(
                    voucherId, userId, eventId, orderId);

            log.warn(
                    "对账发现孤儿预留但订单已存在，已收敛 CONSUMED 并清理预留，"
                            + "voucherId={}，eventId={}，orderId={}，completeResult={}",
                    voucherId,
                    eventId,
                    orderId,
                    completeResult
            );
            return;
        }

        // 订单不存在且事件不存在：依据预留详情幂等补建 PENDING 事件
        SeckillOrderEvent existingEvent = eventService.findByEventId(eventId);

        if (existingEvent == null) {
            boolean created = eventService.createPendingIfAbsent(
                    buildMessage(eventId, orderId, userId, voucherId,
                            createdAt, messageVersion));

            log.warn(
                    "对账依据 Redis 预留账本补建事件，voucherId={}，eventId={}，"
                            + "orderId={}，created={}",
                    voucherId,
                    eventId,
                    orderId,
                    created
            );
            return;
        }

        // 事件存在且订单不存在：事件状态机仍在自动处理（发布/回滚/人工），不动
        log.debug(
                "孤儿预留对应事件仍在处理中，等待自动流程收敛，"
                        + "voucherId={}，eventId={}，status={}",
                voucherId,
                eventId,
                existingEvent.getStatus()
        );
    }

    /**
     * 方向二：MySQL 事件 → Redis（规格 12.2 节）。
     */
    private void reconcileEventsToRedis() {
        reconcileConsumedEvents();
        reconcileRolledBackEvents();
        reconcileStuckRollbackExecuting();
        escalateLongUnknownPublishes();
    }

    /**
     * CONSUMED 事件执行预留完成脚本（幂等）。
     */
    private void reconcileConsumedEvents() {
        List<SeckillOrderEvent> events;

        try {
            events = eventService.findConsumedRecent(
                    eventWindowMinutes, eventBatchSize);
        } catch (Exception exception) {
            log.error("对账任务查询 CONSUMED 事件失败", exception);
            return;
        }

        for (SeckillOrderEvent event : events) {
            try {
                Long result = luaExecutor.completeReservation(
                        event.getVoucherId(),
                        event.getUserId(),
                        event.getEventId(),
                        event.getOrderId()
                );

                if (Long.valueOf(1L).equals(result)) {
                    log.warn(
                            "对账补执行预留完成（消费侧清理缺失），"
                                    + "eventId={}，voucherId={}",
                            event.getEventId(),
                            event.getVoucherId()
                    );
                }
                // 0 幂等成功；-2 事件冲突需要人工关注
                if (Long.valueOf(-2L).equals(result)) {
                    recordReconcileConflict(
                            event,
                            "complete_reservation_conflict",
                            "预留完成脚本报告事件冲突（映射指向其他事件）"
                    );
                }
            } catch (Exception exception) {
                log.error(
                        "对账执行预留完成脚本失败，eventId={}",
                        event.getEventId(),
                        exception
                );
            }
        }
    }

    /**
     * ROLLED_BACK 事件确认预留详情和用户事件映射已经移除。
     */
    private void reconcileRolledBackEvents() {
        List<SeckillOrderEvent> events;

        try {
            events = eventService.findRolledBackRecent(
                    eventWindowMinutes, eventBatchSize);
        } catch (Exception exception) {
            log.error("对账任务查询 ROLLED_BACK 事件失败", exception);
            return;
        }

        for (SeckillOrderEvent event : events) {
            try {
                boolean detailExists = luaExecutor.reservationExists(
                        event.getVoucherId(), event.getEventId());

                if (!detailExists) {
                    continue;
                }

                // 详情仍在但状态已是 ROLLED_BACK：回滚 Lua 执行证据矛盾
                recordReconcileConflict(
                        event,
                        "rolled_back_reservation_remains",
                        "事件已 ROLLED_BACK 但 Redis 预留详情仍存在"
                );
            } catch (Exception exception) {
                log.error(
                        "对账核对 ROLLED_BACK 预留清理失败，eventId={}",
                        event.getEventId(),
                        exception
                );
            }
        }
    }

    /**
     * ROLLBACK_EXECUTING 卡死事件按 Redis 预留是否仍存在收敛。
     */
    private void reconcileStuckRollbackExecuting() {
        List<SeckillOrderEvent> events;

        try {
            events = eventService.findRollbackExecutingStuck(
                    rollbackStuckMinutes, eventBatchSize);
        } catch (Exception exception) {
            log.error("对账任务查询卡死回滚事件失败", exception);
            return;
        }

        for (SeckillOrderEvent event : events) {
            try {
                boolean reservationRemains = luaExecutor.reservationExists(
                        event.getVoucherId(), event.getEventId());

                // 预留已移除说明 Lua 已执行：收敛 ROLLED_BACK；
                // 预留仍在说明 Lua 未执行：恢复 ROLLBACK_PENDING 重试
                boolean converged = eventService
                        .convergeStuckRollbackExecuting(
                                event.getEventId(),
                                !reservationRemains
                        );

                log.warn(
                        "对账收敛卡死的 ROLLBACK_EXECUTING 事件，"
                                + "eventId={}，reservationRemains={}，converged={}",
                        event.getEventId(),
                        reservationRemains,
                        converged
                );
            } catch (Exception exception) {
                log.error(
                        "对账收敛卡死回滚事件失败，eventId={}",
                        event.getEventId(),
                        exception
                );
            }
        }
    }

    /**
     * 长期 PUBLISH_UNKNOWN 转 MANUAL_REVIEW 并告警（规格 12.2 节）。
     *
     * <p>转人工与失败记录通过 {@link SeckillFailureCaseService}
     * 的同一事务写入，保证人工处置入口必然存在。</p>
     */
    private void escalateLongUnknownPublishes() {
        List<SeckillOrderEvent> events;

        try {
            events = eventService.findPublishUnknownOlderThan(
                    publishUnknownMaxHours, eventBatchSize);
        } catch (Exception exception) {
            log.error("对账任务查询长期 PUBLISH_UNKNOWN 事件失败", exception);
            return;
        }

        for (SeckillOrderEvent event : events) {
            try {
                boolean escalated =
                        failureCaseService.recordManualReviewEscalation(
                                event,
                                SeckillFailureCase.SOURCE_RECONCILE,
                                "publish_unknown_expired",
                                "PUBLISH_UNKNOWN 超过最大存活时间 "
                                        + publishUnknownMaxHours + " 小时"
                        );

                log.error(
                        "[SECKILL_PUBLISH_UNKNOWN_EXPIRED] "
                                + "长期发布结果未知事件转人工并写失败记录，eventId={}，"
                                + "escalated={}",
                        event.getEventId(),
                        escalated
                );
            } catch (Exception exception) {
                log.error(
                        "对账升级长期 PUBLISH_UNKNOWN 事件失败，eventId={}",
                        event.getEventId(),
                        exception
                );
            }
        }
    }

    /**
     * 输出规格第 19 节要求的关键指标日志（可聚合）。
     */
    private void emitMetrics() {
        try {
            Integer pending = eventService.countByStatus(
                    SeckillOrderEvent.STATUS_PENDING);
            Integer publishUnknown = eventService.countByStatus(
                    SeckillOrderEvent.STATUS_PUBLISH_UNKNOWN);
            Integer rollbackPending = eventService.countByStatus(
                    SeckillOrderEvent.STATUS_ROLLBACK_PENDING);
            Integer dlq = eventService.countByStatus(
                    SeckillOrderEvent.STATUS_DLQ);
            Integer manualReview = eventService.countByStatus(
                    SeckillOrderEvent.STATUS_MANUAL_REVIEW);
            Integer openFailures = failureCaseService.countOpenCases();

            log.info(
                    "[SECKILL_METRICS] pending={} publishUnknown={} "
                            + "rollbackPending={} dlq={} manualReview={} "
                            + "openFailureCases={}",
                    pending,
                    publishUnknown,
                    rollbackPending,
                    dlq,
                    manualReview,
                    openFailures
            );
        } catch (Exception exception) {
            log.warn("对账任务输出监控指标失败", exception);
        }
    }

    /**
     * 信息不完整的孤儿预留：写失败记录转人工，禁止猜测回滚。
     *
     * <p>失败单落库成功后必须原子移出待对账 ZSet 并转入人工处理集合：
     * 每轮固定读取最早的 reservationBatchSize 条记录，异常记录若留在原处，
     * 排头持续异常会永久阻塞排在后面的正常预留。
     * 先写失败单再移交——移交失败时记录仍在待对账 ZSet，
     * 下一轮失败单幂等重写并重试移交，不会失去发现入口；
     * 移交 Lua 成功后该预留由人工依据失败单处置收敛。</p>
     */
    private void recordIncompleteReservation(
            Long voucherId, String eventId, String detail) {

        SeckillFailureCase failureCase = new SeckillFailureCase();

        failureCase.setIdempotencyKey("RECONCILE:" + eventId);
        failureCase.setEventId(eventId);
        failureCase.setVoucherId(voucherId);
        failureCase.setSource(SeckillFailureCase.SOURCE_RECONCILE);
        failureCase.setStatus(SeckillFailureCase.STATUS_OPEN);
        failureCase.setErrorCode("incomplete_reservation_detail");
        failureCase.setErrorMessage("Redis 预留详情缺失或格式非法，禁止自动回滚");
        failureCase.setMessagePayload(detail == null
                ? "absent"
                : detail.substring(0, Math.min(detail.length(), 200)));
        failureCase.setReplayCount(0);

        failureCaseService.recordFailure(failureCase);

        Long movedToManual = luaExecutor.moveReservationToManual(
                voucherId, eventId);

        log.error(
                "[SECKILL_INCOMPLETE_RESERVATION] "
                        + "孤儿预留信息不完整，已写失败记录并移交人工处理集合，"
                        + "voucherId={}，eventId={}，movedToManual={}",
                voucherId,
                eventId,
                movedToManual
        );
    }

    /**
     * 对账冲突：写失败记录转人工核对。
     */
    private void recordReconcileConflict(
            SeckillOrderEvent event,
            String errorCode,
            String errorMessage) {

        SeckillFailureCase failureCase = new SeckillFailureCase();

        failureCase.setIdempotencyKey("RECONCILE:" + errorCode + ":"
                + event.getEventId());
        failureCase.setEventId(event.getEventId());
        failureCase.setOrderId(event.getOrderId());
        failureCase.setUserId(event.getUserId());
        failureCase.setVoucherId(event.getVoucherId());
        failureCase.setSource(SeckillFailureCase.SOURCE_RECONCILE);
        failureCase.setStatus(SeckillFailureCase.STATUS_OPEN);
        failureCase.setErrorCode(errorCode);
        failureCase.setErrorMessage(errorMessage);
        failureCase.setReplayCount(0);

        failureCaseService.recordFailure(failureCase);
    }

    /**
     * 依据预留详情重建消息。
     */
    private SeckillOrderMessage buildMessage(
            String eventId,
            Long orderId,
            Long userId,
            Long voucherId,
            Long createdAt,
            Integer messageVersion) {

        return new SeckillOrderMessage(
                eventId,
                orderId,
                userId,
                voucherId,
                createdAt,
                messageVersion
        );
    }
}
