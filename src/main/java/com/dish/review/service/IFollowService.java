package com.dish.review.service;

import com.dish.review.dto.Result;
import com.dish.review.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author DishReview
 * @since 2021-12-22
 */
public interface IFollowService extends IService<Follow> {

    /**
     * 关注或取消关注
     *
     * @param followUserId 被关注的用户id
     * @param isFollow     true：关注，false：取消关注
     * @return 操作结果
     */
    Result follow(Long followUserId, Boolean isFollow);

    /**
     * 查询当前用户是否关注了某用户
     *
     * @param followUserId 被关注的用户id
     * @return 结果中 data 为 Boolean，true 表示已关注
     */
    Result isFollow(Long followUserId);

    /**
     * 查询当前用户与目标用户的共同关注
     *
     * @param targetUserId 目标用户id
     * @return 结果中 data 为共同关注的用户列表（UserDTO）
     */
    Result followCommons(Long targetUserId);
}
