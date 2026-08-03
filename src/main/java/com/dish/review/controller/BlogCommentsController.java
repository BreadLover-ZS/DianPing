package com.dish.review.controller;


import com.dish.review.dto.Result;
import com.dish.review.entity.BlogComments;
import com.dish.review.service.IBlogCommentsService;
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
@RequestMapping("/blog-comments")
public class BlogCommentsController {

    @Resource
    private IBlogCommentsService blogCommentsService;

    /**
     * 根据博客id查询评论列表
     *
     * @param blogId 博客id
     * @return 评论列表（按创建时间倒序）
     */
    @GetMapping("/{id}")
    public Result queryComments(@PathVariable("id") Long blogId) {
        return blogCommentsService.queryComments(blogId);
    }

    /**
     * 新增评论
     *
     * @param comment 评论数据
     * @return 新增评论的id
     */
    @PostMapping
    public Result saveComment(@RequestBody BlogComments comment) {
        return blogCommentsService.saveComment(comment);
    }
}
