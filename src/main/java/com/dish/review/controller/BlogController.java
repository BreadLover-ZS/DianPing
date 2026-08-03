package com.dish.review.controller;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dish.review.dto.Result;
import com.dish.review.dto.UserDTO;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HtmlUtil;
import com.dish.review.entity.Blog;
import com.dish.review.entity.User;
import com.dish.review.service.IBlogService;
import com.dish.review.service.IUserService;
import com.dish.review.utils.SystemConstants;
import com.dish.review.utils.UserHolder;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author DishReview
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/blog")
public class BlogController {

    @Resource
    private IBlogService blogService;
    @Resource
    private IUserService userService;

    @PostMapping
    public Result saveBlog(@RequestBody Blog blog) {
        // 【安全修复 Fix 13】XSS 防护 —— 对用户输入的标题和内容进行 HTML 转义
        // 将 <script> 等危险标签转换为 &lt;script&gt;，防止存储型 XSS 攻击
        if (StrUtil.isNotBlank(blog.getTitle())) {
            blog.setTitle(HtmlUtil.escape(blog.getTitle()));
        }
        if (StrUtil.isNotBlank(blog.getContent())) {
            blog.setContent(HtmlUtil.escape(blog.getContent()));
        }

        // 保存探店博文并推送到粉丝收件箱（Feed流），逻辑下沉至 Service
        return blogService.saveBlog(blog);
    }

    @PutMapping("/like/{id}")
    public Result likeBlog(@PathVariable("id") Long id) {
        // 点赞/取消点赞，逻辑下沉至 Service（基于 Redis ZSet 实现一人一赞）
        return blogService.likeBlog(id);
    }

    @GetMapping("/of/me")
    public Result queryMyBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .eq("user_id", user.getId()).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    @GetMapping("/hot")
    public Result queryHotBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
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
    @GetMapping("/{id}")
    public Result queryBlogById(@PathVariable("id") Long id) {
        return blogService.queryBlogById(id);
    }

    /**
     * 查询博客的点赞用户列表（top5）
     *
     * @param id 博客id
     * @return 点赞用户列表（UserDTO）
     */
    @GetMapping("/likes/{id}")
    public Result queryBlogLikes(@PathVariable("id") Long id) {
        return blogService.queryBlogLikes(id);
    }

    /**
     * 根据用户id查询其博客列表
     *
     * @param id      用户id
     * @param current 页码
     * @return 博客列表
     */
    @GetMapping("/of/user")
    public Result queryBlogByUserId(
            @RequestParam("id") Long id,
            @RequestParam(value = "current", defaultValue = "1") Integer current
    ) {
        return blogService.queryBlogByUserId(id, current);
    }

    /**
     * 查询当前用户收件箱中的关注推送（Feed流，滚动分页）
     *
     * @param lastId 上次查询的最小时间戳
     * @param offset 偏移量
     * @return 滚动分页结果
     */
    @GetMapping("/of/follow")
    public Result queryBlogOfFollow(
            @RequestParam("lastId") Long lastId,
            @RequestParam(value = "offset", defaultValue = "0") Integer offset
    ) {
        return blogService.queryBlogOfFollow(lastId, offset);
    }
}
