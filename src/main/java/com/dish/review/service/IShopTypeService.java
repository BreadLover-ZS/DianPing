package com.dish.review.service;

import com.dish.review.dto.Result;
import com.dish.review.entity.ShopType;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author DishReview
 * @since 2021-12-22
 */
public interface IShopTypeService extends IService<ShopType> {

    Result queryTypeList();
}
