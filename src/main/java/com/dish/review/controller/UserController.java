package com.dish.review.controller;


import cn.hutool.core.bean.BeanUtil;
import com.dish.review.dto.LoginFormDTO;
import com.dish.review.dto.Result;
import com.dish.review.dto.UserDTO;
import com.dish.review.entity.User;
import com.dish.review.entity.UserInfo;
import com.dish.review.service.IUserInfoService;
import com.dish.review.service.IUserService;
import com.dish.review.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author DishReview
 * @since 2021-12-22
 */
@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private IUserService userService;

    @Resource
    private IUserInfoService userInfoService;

    /**
     * 发送手机验证码
     */
    @PostMapping("code")
    public Result sendCode(@RequestParam("phone") String phone, HttpSession session) {
        return userService.sendCode(phone, session);
    }

    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     */
    @PostMapping("/login")
    public Result login(@RequestBody LoginFormDTO loginForm, HttpSession session){
        // 实现登录功能
        return userService.login(loginForm, session);
    }

    /**
     * 登出功能
     * 【安全修复 Fix 12】从请求头获取 token，调用 service 删除 Redis 中的 token 使其失效
     *
     * @param token 用户登录令牌（从请求头 authorization 获取）
     * @return 登出结果
     */
    @PostMapping("/logout")
    public Result logout(@RequestHeader(value = "authorization", required = false) String token) {
        return userService.logout(token);
    }

    @GetMapping("/me")
    public Result me(){
        // 获取当前登录的用户并返回
        UserDTO user = UserHolder.getUser();
        return Result.ok(user);
    }

    @GetMapping("/info/{id}")
    public Result info(@PathVariable("id") Long userId){
        // 查询详情
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            // 没有详情，应该是第一次查看详情
            return Result.ok();
        }
        info.setCreateTime(null);
        info.setUpdateTime(null);
        // 返回
        return Result.ok(info);
    }

    /**
     * 根据id查询用户基本信息
     * 用于他人主页展示昵称、头像，仅返回非敏感信息
     *
     * @param userId 用户id
     * @return 用户基本信息（UserDTO，仅含 id、nickName、icon）
     */
    @GetMapping("/{id:\\d+}")
    public Result queryUserById(@PathVariable("id") Long userId) {
        User user = userService.getById(userId);
        if (user == null) {
            return Result.ok();
        }
        // 转为 UserDTO，避免泄露手机号、密码等敏感信息
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        return Result.ok(userDTO);
    }

    /**
     * 用户签到
     *
     * @return 签到结果
     */
    @PostMapping("/sign")
    public Result sign() {
        return userService.sign();
    }

    /**
     * 统计本月连续签到天数
     *
     * @return 连续签到天数
     */
    @GetMapping("/sign/count")
    public Result signCount() {
        return userService.signCount();
    }
}
