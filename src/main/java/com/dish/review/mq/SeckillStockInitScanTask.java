package com.dish.review.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dish.review.entity.SeckillFailureCase;
import com.dish.review.entity.SeckillVoucher;
import com.dish.review.service.ISeckillVoucherService;
import com.dish.review.service.SeckillFailureCaseService;
import com.dish.review.service.SeckillVoucherLuaExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 缺失库存扫描任务（规格第 14 节）。
 *
 * <p>发现 MySQL 有未结束的秒杀券但 Redis 库存 Key 缺失时，
 * 只能调用安全原子初始化脚本；存在历史用户或预留数据时返回冲突，
 * 写失败记录进入人工处理，禁止清空重建。</p>
 *
 * <p>同时输出库存一致性指标（规格第 19 节）：
 * divergence = MySQL 库存 - Redis 库存 - 待对账预留数。
 * divergence &gt; 0 表示 Redis 比账本多扣（真实不一致，告警）；
 * divergence &lt; 0 通常是 CONSUMED 事件等待对账清理预留的滞后窗口，
 * 仅计入指标不告警。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "dish-review.seckill.tasks-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class SeckillStockInitScanTask {

    private final ISeckillVoucherService seckillVoucherService;

    private final SeckillVoucherLuaExecutor luaExecutor;

    private final SeckillFailureCaseService failureCaseService;

    /**
     * 注入秒杀券服务、Lua 执行器和失败记录服务。
     */
    public SeckillStockInitScanTask(
            ISeckillVoucherService seckillVoucherService,
            SeckillVoucherLuaExecutor luaExecutor,
            SeckillFailureCaseService failureCaseService) {
        this.seckillVoucherService = seckillVoucherService;
        this.luaExecutor = luaExecutor;
        this.failureCaseService = failureCaseService;
    }

    /**
     * 周期扫描缺失库存并输出库存一致性指标。
     */
    @Scheduled(
            fixedDelayString =
                    "${dish-review.seckill.stock-scan.scan-delay:300000}"
    )
    public void scanMissingStock() {
        List<SeckillVoucher> vouchers;

        try {
            LambdaQueryWrapper<SeckillVoucher> query =
                    new LambdaQueryWrapper<>();

            // 未结束的秒杀券（含未开始）：结束的券不再需要 Redis 库存
            query.ge(SeckillVoucher::getEndTime, LocalDateTime.now());

            vouchers = seckillVoucherService.list(query);
        } catch (Exception exception) {
            log.error("缺失库存扫描任务查询秒杀券失败", exception);
            return;
        }

        if (vouchers.isEmpty()) {
            return;
        }

        int missingCount = 0;
        int initializedCount = 0;
        int conflictCount = 0;
        int inconsistentCount = 0;

        for (SeckillVoucher voucher : vouchers) {
            try {
                int outcome = scanOneVoucher(voucher);

                switch (outcome) {
                    case 1:
                        missingCount++;
                        break;
                    case 2:
                        missingCount++;
                        initializedCount++;
                        break;
                    case 3:
                        missingCount++;
                        conflictCount++;
                        break;
                    case 4:
                        inconsistentCount++;
                        break;
                    default:
                        // 0：库存存在且一致
                        break;
                }
            } catch (Exception exception) {
                log.error(
                        "缺失库存扫描任务处理秒杀券失败，voucherId={}",
                        voucher.getVoucherId(),
                        exception
                );
            }
        }

        log.info(
                "[SECKILL_STOCK_METRICS] scanned={} missing={} "
                        + "initialized={} conflict={} inconsistent={}",
                vouchers.size(),
                missingCount,
                initializedCount,
                conflictCount,
                inconsistentCount
        );
    }

    /**
     * 处理单张秒杀券。
     *
     * @return 0 正常；1 库存缺失；2 库存缺失且已初始化；
     *         3 库存缺失但冲突转人工；4 库存数值真实不一致
     */
    private int scanOneVoucher(SeckillVoucher voucher) {
        Long voucherId = voucher.getVoucherId();

        // 1. 库存 Key 缺失：只能调用安全初始化，禁止清空重建
        if (!luaExecutor.hasStockKey(voucherId)) {
            Long result = luaExecutor.initStock(
                    voucherId, voucher.getStock());

            if (Long.valueOf(1L).equals(result)) {
                log.warn(
                        "[SECKILL_STOCK_MISSING_INITIALIZED] "
                                + "缺失库存扫描任务安全初始化库存，"
                                + "voucherId={}，stock={}",
                        voucherId,
                        voucher.getStock()
                );
                return 2;
            }

            if (Long.valueOf(0L).equals(result)) {
                // 并发下已被其他实例初始化，按幂等成功处理
                return 1;
            }

            // -1：存在历史用户或预留数据，转人工
            recordStockScanConflict(voucher, result);
            return 3;
        }

        // 2. 库存 Key 存在：核对库存一致性指标
        return checkStockConsistency(voucher);
    }

    /**
     * 核对 MySQL 库存、Redis 库存与待对账预留的账面一致性。
     *
     * <p>真实不一致（divergence &gt; 0 或库存值非法）写失败记录转人工，
     * 禁止仅靠日志兜底；滞后窗口（divergence &lt; 0）仅计入指标。</p>
     */
    private int checkStockConsistency(SeckillVoucher voucher) {
        Long voucherId = voucher.getVoucherId();

        String redisStockValue = luaExecutor.getStock(voucherId);

        if (redisStockValue == null) {
            // Key 在两次调用间被删除：留待下一轮扫描处理
            return 0;
        }

        long redisStock;
        try {
            redisStock = Long.parseLong(redisStockValue.trim());
        } catch (NumberFormatException exception) {
            recordStockConsistencyFailure(
                    voucher,
                    "stock_value_invalid",
                    "Redis 库存值非法，value=" + redisStockValue
            );
            log.error(
                    "[SECKILL_STOCK_INCONSISTENT] "
                            + "Redis 库存值非法，voucherId={}，value={}",
                    voucherId,
                    redisStockValue
            );
            return 4;
        }

        long mysqlStock = voucher.getStock() == null
                ? 0L
                : voucher.getStock();
        long pendingCount = luaExecutor.pendingReservationCount(voucherId);

        long divergence = mysqlStock - redisStock - pendingCount;

        if (divergence > 0) {
            // Redis 比账面多扣：存在无法用预留解释的库存差异，真实不一致
            recordStockConsistencyFailure(
                    voucher,
                    "stock_consistency_divergence",
                    "Redis 库存与 MySQL 账面不一致（无法用待对账预留解释），"
                            + "mysqlStock=" + mysqlStock
                            + "，redisStock=" + redisStock
                            + "，pendingReservations=" + pendingCount
                            + "，divergence=" + divergence
            );
            log.error(
                    "[SECKILL_STOCK_INCONSISTENT] "
                            + "Redis 库存与 MySQL 账面不一致（无法用待对账预留解释），"
                            + "voucherId={}，mysqlStock={}，redisStock={}，"
                            + "pendingReservations={}，divergence={}",
                    voucherId,
                    mysqlStock,
                    redisStock,
                    pendingCount,
                    divergence
            );
            return 4;
        }

        // divergence < 0：CONSUMED 事件等待对账清理预留的正常滞后窗口
        return 0;
    }

    /**
     * 库存真实不一致：写失败记录转人工处理。
     */
    private void recordStockConsistencyFailure(
            SeckillVoucher voucher,
            String errorCode,
            String errorMessage) {

        SeckillFailureCase failureCase = new SeckillFailureCase();

        failureCase.setIdempotencyKey(
                "STOCK_CONSISTENT:" + voucher.getVoucherId());
        failureCase.setVoucherId(voucher.getVoucherId());
        failureCase.setSource(SeckillFailureCase.SOURCE_RECONCILE);
        failureCase.setStatus(SeckillFailureCase.STATUS_OPEN);
        failureCase.setErrorCode(errorCode);
        failureCase.setErrorMessage(errorMessage);
        failureCase.setReplayCount(0);

        try {
            failureCaseService.recordFailure(failureCase);
        } catch (Exception persistenceException) {
            log.error(
                    "库存不一致失败记录写入失败，voucherId={}",
                    voucher.getVoucherId(),
                    persistenceException
            );
        }
    }

    /**
     * 扫描冲突：库存缺失但存在历史用户或预留数据，写失败记录转人工。
     */
    private void recordStockScanConflict(
            SeckillVoucher voucher, Long luaResult) {

        SeckillFailureCase failureCase = new SeckillFailureCase();

        failureCase.setIdempotencyKey(
                "STOCK_INIT:" + voucher.getVoucherId());
        failureCase.setVoucherId(voucher.getVoucherId());
        failureCase.setSource(SeckillFailureCase.SOURCE_RECONCILE);
        failureCase.setStatus(SeckillFailureCase.STATUS_OPEN);
        failureCase.setErrorCode("stock_init_conflict");
        failureCase.setErrorMessage(
                "缺失库存扫描发现历史用户或预留数据，禁止自动初始化，"
                        + "luaResult=" + luaResult
                        + "，mysqlStock=" + voucher.getStock()
        );
        failureCase.setReplayCount(0);

        failureCaseService.recordFailure(failureCase);

        log.error(
                "[SECKILL_STOCK_INIT_CONFLICT] "
                        + "缺失库存扫描发现历史用户或预留数据，"
                        + "禁止自动初始化，已转人工处理，voucherId={}，"
                        + "luaResult={}",
                voucher.getVoucherId(),
                luaResult
        );
    }
}
