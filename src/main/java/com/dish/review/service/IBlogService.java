package com.dish.review.service;

import com.dish.review.dto.Result;
import com.dish.review.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author DishReview
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    /**
     * 查询热门博客
     *
     * @param current 页码
     * @return 博客列表（含作者昵称、头像）
     */
    Result queryHotBlog(Integer current);

    /**
     * 根据id查询博客详情
     *
     * @param id 博客id
     * @return 博客详情（含作者信息、当前用户是否点赞）
     */
    Result queryBlogById(Long id);

    /**
     * 点赞或取消点赞
     * 基于 Redis ZSet 实现一人一赞，score 为点赞时间戳
     *
     * @param id 博客id
     * @return 操作结果
     */
    Result likeBlog(Long id);

    /**
     * 查询博客的点赞用户列表（top5，按点赞时间排序）
     *
     * @param id 博客id
     * @return 点赞用户列表（UserDTO）
     */
    Result queryBlogLikes(Long id);

    /**
     * 根据用户id查询其博客列表
     *
     * @param id      用户id
     * @param current 页码
     * @return 博客列表
     */
    Result queryBlogByUserId(Long id, Integer current);

    /**
     * 保存博客并推送到粉丝收件箱（Feed流）
     *
     * @param blog 博客数据（标题、内容已由上层做 XSS 转义）
     * @return 新增博客的id
     */
    Result saveBlog(Blog blog);

    /**
     * 查询当前用户收件箱中的关注推送（Feed流，滚动分页）
     *
     * @param lastId 上次查询的最小时间戳
     * @param offset 偏移量（与最小时间戳相同的条数）
     * @return 滚动分页结果（含列表、最小时间戳、偏移量）
     */
    Result queryBlogOfFollow(Long lastId, Integer offset);
}
