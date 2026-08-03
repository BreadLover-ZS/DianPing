package com.dish.review.service;

import com.dish.review.dto.Result;
import com.dish.review.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author DishReview
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {

    Result queryById(Long id);

    Result update(Shop shop);

    /**
     * 根据商铺类型查询附近商铺（基于 Redis GEO）
     * 若未传坐标，则退化为普通分页查询
     *
     * @param typeId  商铺类型
     * @param current 页码
     * @param x       经度
     * @param y       纬度
     * @return 商铺列表（含距离）
     */
    Result queryShopByType(Integer typeId, Integer current, Double x, Double y);
}
