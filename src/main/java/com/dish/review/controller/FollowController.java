package com.dish.review.controller;


import com.dish.review.dto.Result;
import com.dish.review.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author DishReview
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {

    @Resource
    private IFollowService followService;

    /**
     * 关注或取消关注
     *
     * @param followUserId 被关注的用户id
     * @param isFollow     true：关注，false：取消关注
     * @return 操作结果
     */
    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable("id") Long followUserId, @PathVariable("isFollow") Boolean isFollow) {
        return followService.follow(followUserId, isFollow);
    }

    /**
     * 查询当前用户是否关注了某用户
     *
     * @param followUserId 被关注的用户id
     * @return data 为 Boolean，true 表示已关注
     */
    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id") Long followUserId) {
        return followService.isFollow(followUserId);
    }

    /**
     * 查询当前用户与目标用户的共同关注
     *
     * @param targetUserId 目标用户id
     * @return data 为共同关注的用户列表（UserDTO）
     */
    @GetMapping("/common/{id}")
    public Result followCommons(@PathVariable("id") Long targetUserId) {
        return followService.followCommons(targetUserId);
    }
}
