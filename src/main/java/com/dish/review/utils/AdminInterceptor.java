package com.dish.review.utils;

import com.dish.review.dto.UserDTO;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 管理写接口鉴权。登录拦截器只解决身份认证，本拦截器补充角色授权。
 */
public class AdminInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) {

        String method = request.getMethod();
        if (HttpMethod.GET.matches(method)
                || HttpMethod.HEAD.matches(method)
                || HttpMethod.OPTIONS.matches(method)) {
            return true;
        }

        UserDTO user = UserHolder.getUser();
        if (user != null
                && SystemConstants.ROLE_ADMIN.equalsIgnoreCase(user.getRole())) {
            return true;
        }

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        return false;
    }
}
