package com.dish.review.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dish.review.dto.Result;
import com.dish.review.entity.BlogComments;
import com.dish.review.mapper.BlogCommentsMapper;
import com.dish.review.service.IBlogCommentsService;
import com.dish.review.service.IBlogService;
import com.dish.review.utils.UserHolder;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author DishReview
 * @since 2021-12-22
 */
@Service
public class BlogCommentsServiceImpl extends ServiceImpl<BlogCommentsMapper, BlogComments> implements IBlogCommentsService {

    @Resource
    private IBlogService blogService;

    /**
     * 根据博客id查询评论列表
     *
     * @param blogId 博客id
     * @return 评论列表（按创建时间倒序）
     */
    @Override
    public Result queryComments(Long blogId) {
        List<BlogComments> comments = query()
                .eq("blog_id", blogId)
                .orderByDesc("create_time")
                .list();
        return Result.ok(comments);
    }

    /**
     * 新增评论
     * 设置当前登录用户为评论者，并同步博客评论数
     *
     * @param comment 评论数据
     * @return 新增评论的id
     */
    @Override
    public Result saveComment(BlogComments comment) {
        Long userId = UserHolder.getUser().getId();
        comment.setUserId(userId);
        comment.setCreateTime(LocalDateTime.now());
        comment.setLiked(0);
        // status：false 表示正常（0），true 表示被举报/禁止
        comment.setStatus(false);
        boolean success = save(comment);
        if (!success) {
            return Result.fail("评论失败");
        }
        // 博客评论数 +1
        blogService.update().setSql("comments = comments + 1").eq("id", comment.getBlogId()).update();
        return Result.ok(comment.getId());
    }
}
