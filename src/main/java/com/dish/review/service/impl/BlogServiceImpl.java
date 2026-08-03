package com.dish.review.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dish.review.dto.Result;
import com.dish.review.dto.ScrollResult;
import com.dish.review.dto.UserDTO;
import com.dish.review.entity.Blog;
import com.dish.review.entity.Follow;
import com.dish.review.entity.User;
import com.dish.review.mapper.BlogMapper;
import com.dish.review.service.IBlogService;
import com.dish.review.service.IFollowService;
import com.dish.review.service.IUserService;
import com.dish.review.utils.RedisConstants;
import com.dish.review.utils.SimpleRedisLock;
import com.dish.review.utils.SystemConstants;
import com.dish.review.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Resource
    private IFollowService followService;

    /**
     * 查询热门博客
     *
     * @param current 页码
     * @return 博客列表（含作者昵称、头像）
     */
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据点赞数降序分页查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 填充作者昵称、头像
        records.forEach(blog -> {
            Long userId = blog.getUserId();
            User user = userService.getById(userId);
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
        });
        return Result.ok(records);
    }

    /**
     * 根据id查询博客详情
     *
     * @param id 博客id
     * @return 博客详情（含作者信息、当前用户是否点赞）
     */
    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在！");
        }
        // 设置作者昵称、头像
        queryBlogUser(blog);
        // 设置当前用户是否已点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    /**
     * 点赞或取消点赞
     * 基于 Redis ZSet 实现一人一赞，score 为点赞时间戳，便于按点赞时间排序
     *
     * @param id 博客id
     * @return 操作结果
     */
    @Override
    public Result likeBlog(Long id) {
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        // 同一用户对同一博客的点赞/取消点赞必须串行化，避免两个请求同时看到相同状态。
        SimpleRedisLock lock = new SimpleRedisLock(
                "blog:like:" + id + ":" + userId,
                stringRedisTemplate);
        if (!lock.tryLock(10L)) {
            return Result.fail("操作频繁，请稍后重试");
        }
        try {
            // 加锁后重新读取状态，不能使用加锁前的判断结果。
            Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
            if (score == null) {
                return increaseLike(id, key, userId);
            }
            return decreaseLike(id, key, userId);
        } finally {
            lock.unlock();
        }
    }

    private Result increaseLike(Long id, String key, Long userId) {
        boolean success = update()
                .setSql("liked = COALESCE(liked, 0) + 1")
                .eq("id", id)
                .update();
        if (!success) {
            return Result.fail("笔记不存在");
        }
        try {
            stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            return Result.ok();
        } catch (RuntimeException e) {
            // Redis 写入失败时尽力补偿数据库计数，避免下次重试再次累加。
            update().setSql("liked = CASE WHEN COALESCE(liked, 0) > 0 THEN liked - 1 ELSE 0 END")
                    .eq("id", id).update();
            throw e;
        }
    }

    private Result decreaseLike(Long id, String key, Long userId) {
        boolean success = update()
                .setSql("liked = CASE WHEN COALESCE(liked, 0) > 0 THEN liked - 1 ELSE 0 END")
                .eq("id", id)
                .update();
        if (!success) {
            return Result.fail("笔记不存在");
        }
        try {
            stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            return Result.ok();
        } catch (RuntimeException e) {
            // Redis 删除失败时尽力恢复数据库点赞数。
            update().setSql("liked = COALESCE(liked, 0) + 1")
                    .eq("id", id).update();
            throw e;
        }
    }

    /**
     * 查询博客的点赞用户列表（top5，按点赞时间排序）
     *
     * @param id 博客id
     * @return 点赞用户列表（UserDTO）
     */
    @Override
    public Result queryBlogLikes(Long id) {
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        // 取点赞 top5 的用户（按点赞时间升序）
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 解析出用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        // 查询用户信息并转为 UserDTO，避免泄露敏感信息
        List<UserDTO> userDTOS = userService.listByIds(ids).stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }

    /**
     * 根据用户id查询其博客列表
     *
     * @param id      用户id
     * @param current 页码
     * @return 博客列表
     */
    @Override
    public Result queryBlogByUserId(Long id, Integer current) {
        Page<Blog> page = query()
                .eq("user_id", id)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        return Result.ok(page.getRecords());
    }

    /**
     * 保存博客并推送到粉丝收件箱（Feed流）
     *
     * @param blog 博客数据（标题、内容已由上层做 XSS 转义）
     * @return 新增博客的id
     */
    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户并设置作者
        Long userId = UserHolder.getUser().getId();
        blog.setUserId(userId);
        // 保存探店博文
        boolean success = save(blog);
        if (!success) {
            return Result.fail("新增笔记失败！");
        }
        // 推送到所有粉丝的收件箱（Feed流）
        pushBlogToFans(blog);
        // 返回id
        return Result.ok(blog.getId());
    }

    /**
     * 查询当前用户收件箱中的关注推送（Feed流，滚动分页）
     *
     * @param lastId 上次查询的最小时间戳
     * @param offset 偏移量（与最小时间戳相同的条数）
     * @return 滚动分页结果（含列表、最小时间戳、偏移量）
     */
    @Override
    public Result queryBlogOfFollow(Long lastId, Integer offset) {
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.FEED_KEY + userId;
        // 从收件箱按 score 倒序滚动分页，取2条
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, lastId, offset, 2);
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        // 解析出笔记id、最小时间戳、偏移量
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0L;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            ids.add(Long.valueOf(tuple.getValue()));
            long time = tuple.getScore().longValue();
            if (time == minTime) {
                os++;
            } else {
                minTime = time;
                os = 1;
            }
        }
        // 查询笔记并按 ids 顺序排序（listByIds 不保证顺序，需手动排序）
        List<Blog> blogs = listByIds(ids);
        Map<Long, Blog> blogMap = blogs.stream().collect(Collectors.toMap(Blog::getId, b -> b));
        List<Blog> sorted = new ArrayList<>();
        for (Long id : ids) {
            Blog blog = blogMap.get(id);
            if (blog == null) {
                continue;
            }
            queryBlogUser(blog);
            isBlogLiked(blog);
            sorted.add(blog);
        }
        // 封装滚动分页结果
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(sorted);
        scrollResult.setMinTime(minTime);
        scrollResult.setOffset(os);
        return Result.ok(scrollResult);
    }

    /**
     * 推送博客到所有粉丝的收件箱（Feed流）
     * 查询作者的所有粉丝，将博客id写入每个粉丝的收件箱 ZSet，score 为当前时间戳
     *
     * @param blog 博客
     */
    private void pushBlogToFans(Blog blog) {
        Long userId = blog.getUserId();
        // 查询作者的所有粉丝（关注了作者的用户）
        List<Follow> follows = followService.query().eq("follow_user_id", userId).list();
        long now = System.currentTimeMillis();
        for (Follow follow : follows) {
            String key = RedisConstants.FEED_KEY + follow.getUserId();
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), now);
        }
    }

    /**
     * 设置博客的作者信息（昵称、头像）
     *
     * @param blog 博客
     */
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        if (user != null) {
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
        }
    }

    /**
     * 设置当前用户是否已点赞该博客
     *
     * @param blog 博客
     */
    private void isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return;
        }
        Long userId = user.getId();
        String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }
}
