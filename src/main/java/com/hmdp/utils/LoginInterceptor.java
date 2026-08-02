package com.hmdp.utils;

import org.springframework.lang.Nullable;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 登录拦截器
 * 校验用户是否已登录，未登录则返回 401 状态码
 *
 * 【安全修复 Fix 9】添加 afterCompletion 方法确保 ThreadLocal 清理
 * 虽然 RefreshTokenInterceptor 已有清理逻辑，但作为纵深防御，
 * 在此拦截器中也添加清理，确保任何异常情况下 ThreadLocal 都不会泄漏
 * 防止 Tomcat 线程池复用时出现用户信息串号问题
 */
public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 校验用户是否登录
        if (UserHolder.getUser() == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        return true;
    }

    /**
     * 【安全修复 Fix 9】纵深防御 -- 确保请求结束后清理 ThreadLocal
     * 防止 Tomcat 线程池复用时出现用户信息串号问题
     * UserHolder.removeUser() 是幂等操作，多次调用安全
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
