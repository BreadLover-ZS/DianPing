package com.dish.review.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.dish.review.dto.LoginFormDTO;
import com.dish.review.dto.Result;
import com.dish.review.entity.User;

import javax.servlet.http.HttpSession;

/**
 * 用户服务接口
 *
 * 【安全修复 Fix 12/14】新增登出和密码登录接口声明
 */
public interface IUserService extends IService<User> {

    /**
     * 发送手机验证码
     *
     * @param phone   手机号
     * @param session HttpSession
     * @return 发送结果
     */
    Result sendCode(String phone, HttpSession session);

    /**
     * 登录功能
     * 支持两种登录方式：
     * 1. 验证码登录：手机号 + 验证码
     * 2. 密码登录：手机号 + 密码
     *
     * @param loginForm 登录表单
     * @param session   HttpSession
     * @return 登录结果，成功返回 token
     */
    Result login(LoginFormDTO loginForm, HttpSession session);

    /**
     * 登出功能
     * 从 Redis 中删除用户 Token，使其立即失效
     *
     * @param token 用户登录令牌
     * @return 登出结果
     */
    Result logout(String token);

    /**
     * 用户签到（基于 Redis BitMap，按月存储）
     *
     * @return 签到结果
     */
    Result sign();

    /**
     * 统计当前用户本月连续签到天数
     *
     * @return 连续签到天数
     */
    Result signCount();
}
