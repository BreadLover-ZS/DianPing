package com.dish.review.service;

import com.dish.review.dto.Result;
import com.dish.review.entity.BlogComments;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author DishReview
 * @since 2021-12-22
 */
public interface IBlogCommentsService extends IService<BlogComments> {

    /**
     * 根据博客id查询评论列表
     *
     * @param blogId 博客id
     * @return 评论列表（按创建时间倒序）
     */
    Result queryComments(Long blogId);

    /**
     * 新增评论
     *
     * @param comment 评论数据
     * @return 新增评论的id
     */
    Result saveComment(BlogComments comment);
}
