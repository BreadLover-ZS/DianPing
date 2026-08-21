package com.dish.review.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dish.review.dto.Result;
import com.dish.review.entity.SeckillFailureCase;
import com.dish.review.entity.Voucher;
import com.dish.review.mapper.VoucherMapper;
import com.dish.review.entity.SeckillVoucher;
import com.dish.review.service.ISeckillVoucherService;
import com.dish.review.service.IVoucherService;
import com.dish.review.service.SeckillFailureCaseService;
import com.dish.review.service.SeckillVoucherLuaExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author DishReview
 * @since 2021-12-22
 */

@Slf4j
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private SeckillVoucherLuaExecutor luaExecutor;

    @Resource
    private SeckillFailureCaseService failureCaseService;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 查询优惠券信息
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 返回结果
        return Result.ok(vouchers);
    }

    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        // 保存优惠券
        save(voucher);
        // 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        seckillVoucherService.save(seckillVoucher);

        /*
         * 注册数据库事务提交回调。
         *
         * 此处不能立即初始化 Redis，因为当前 MySQL 事务尚未提交：
         * 如果先写入 Redis，随后数据库事务回滚，就会出现
         * “MySQL 中没有秒杀券，但 Redis 中存在可用库存”的不一致状态。
         *
         * 只有 MySQL 事务成功提交后才执行 afterCommit；
         * 如果事务发生回滚，afterCommit 不会执行，Redis 也不会写入错误库存。
         */
        Long voucherId = voucher.getId();
        Integer stock = voucher.getStock();

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        initializeRedisStock(voucherId, stock);
                    }
                }
        );
    }

    /**
     * Redis 库存安全原子初始化（规格第 14 节）。
     *
     * <p>通过 Lua 原子脚本初始化：库存 Key 已存在按幂等成功处理，不覆盖；
     * 库存缺失但存在历史用户或预留数据时返回冲突，写失败记录转人工，
     * 禁止清空已有数据；只有相关 Key 全部不存在时才写入初始库存。
     * 禁止无条件 delete(orderKey)。初始化失败时由缺失库存扫描任务补偿。</p>
     */
    private void initializeRedisStock(Long voucherId, Integer stock) {
        try {
            Long result = luaExecutor.initStock(voucherId, stock);

            if (Long.valueOf(0L).equals(result)) {
                log.info(
                        "Redis 秒杀库存已存在，按幂等成功处理，voucherId={}",
                        voucherId
                );
                return;
            }

            if (Long.valueOf(1L).equals(result)) {
                log.info(
                        "Redis 秒杀库存初始化成功，voucherId={}，stock={}",
                        voucherId,
                        stock
                );
                return;
            }

            // -1：库存缺失但存在历史用户/预留数据，禁止清空，转人工
            recordStockInitConflict(voucherId, stock, result);
        } catch (Exception exception) {
            log.error(
                    "[SECKILL_STOCK_INIT_UNAVAILABLE] "
                            + "Redis 秒杀库存初始化执行失败，"
                            + "等待缺失库存扫描任务补偿，voucherId={}，stock={}",
                    voucherId,
                    stock,
                    exception
            );
        }
    }

    /**
     * 库存初始化冲突：写失败记录转人工处理，禁止仅靠日志兜底。
     */
    private void recordStockInitConflict(
            Long voucherId, Integer stock, Long luaResult) {

        SeckillFailureCase failureCase = new SeckillFailureCase();

        failureCase.setIdempotencyKey("STOCK_INIT:" + voucherId);
        failureCase.setVoucherId(voucherId);
        failureCase.setSource(SeckillFailureCase.SOURCE_RECONCILE);
        failureCase.setStatus(SeckillFailureCase.STATUS_OPEN);
        failureCase.setErrorCode("stock_init_conflict");
        failureCase.setErrorMessage(
                "库存 Key 缺失但存在历史用户或预留数据，禁止自动初始化，"
                        + "luaResult=" + luaResult + "，stock=" + stock
        );
        failureCase.setReplayCount(0);

        try {
            failureCaseService.recordFailure(failureCase);
        } catch (Exception persistenceException) {
            log.error(
                    "库存初始化冲突的失败记录写入失败，voucherId={}",
                    voucherId,
                    persistenceException
            );
        }

        log.error(
                "[SECKILL_STOCK_INIT_CONFLICT] "
                        + "Redis 秒杀库存初始化冲突，存在历史用户或预留数据，"
                        + "禁止清空，已转人工处理，voucherId={}，luaResult={}",
                voucherId,
                luaResult
        );
    }
}
