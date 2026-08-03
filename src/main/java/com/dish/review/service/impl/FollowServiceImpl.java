package com.dish.review.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dish.review.dto.Result;
import com.dish.review.dto.UserDTO;
import com.dish.review.entity.Follow;
import com.dish.review.entity.User;
import com.dish.review.mapper.FollowMapper;
import com.dish.review.service.IFollowService;
import com.dish.review.service.IUserService;
import com.dish.review.utils.RedisConstants;
import com.dish.review.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author DishReview
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    /**
     * 关注或取消关注
     * 关注时写入数据库并同步到 Redis Set，取消关注时从数据库删除并移除 Redis Set 成员
     *
     * @param followUserId 被关注的用户id
     * @param isFollow     true：关注，false：取消关注
     * @return 操作结果
     */
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.FOLLOWS_KEY + userId;
        if (isFollow) {
            // 关注：先判断是否已关注，避免重复关注
            Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
            if (count > 0) {
                return Result.ok();
            }
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            follow.setCreateTime(LocalDateTime.now());
            boolean success = save(follow);
            if (success) {
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        } else {
            // 取消关注：从数据库删除并移除 Redis Set 中的成员
            boolean success = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId).eq("follow_user_id", followUserId));
            if (success) {
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 查询当前用户是否关注了某用户
     *
     * @param followUserId 被关注的用户id
     * @return 结果中 data 为 Boolean
     */
    @Override
    public Result isFollow(Long followUserId) {
        Long userId = UserHolder.getUser().getId();
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        return Result.ok(count > 0);
    }

    /**
     * 查询当前用户与目标用户的共同关注
     * 利用 Redis Set 求交集，再回查用户基本信息
     *
     * @param targetUserId 目标用户id
     * @return 结果中 data 为共同关注的用户列表（UserDTO）
     */
    @Override
    public Result followCommons(Long targetUserId) {
        Long userId = UserHolder.getUser().getId();
        String key1 = RedisConstants.FOLLOWS_KEY + userId;
        String key2 = RedisConstants.FOLLOWS_KEY + targetUserId;
        // 求两个关注集合的交集
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if (intersect == null || intersect.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 解析出用户id
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        // 查询用户信息并转为 UserDTO，避免泄露敏感信息
        List<UserDTO> users = userService.listByIds(ids).stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }
}
