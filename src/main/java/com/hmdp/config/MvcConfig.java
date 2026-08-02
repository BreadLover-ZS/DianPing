package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

/**
 * MVC 拦截器配置
 *
 * 【安全修复 Fix 3/5】调整拦截器排除路径：
 * 1. 移除 /upload/** 排除项，文件上传必须登录
 * 2. /shop/** 细化为仅排除查询接口（GET），POST/PUT 操作需认证
 * 3. /voucher/** 细化为仅排除查询接口（GET /voucher/list/**），新增需认证
 */
@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 登录拦截器：拦截除公开接口外的所有请求，校验用户登录状态
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/user/login",
                        "/user/code",
                        "/blog/hot",
                        // 商铺查询接口允许公开访问（浏览商铺无需登录）
                        "/shop/*",           // GET /shop/{id} 查询商铺详情
                        "/shop/of/**",       // GET /shop/of/type, /shop/of/name 分页查询
                        "/shop-type/**",     // 店铺类型列表
                        // 优惠券查询接口允许公开访问
                        "/voucher/list/**"   // GET /voucher/list/{shopId} 查询店铺优惠券
                        // 注意：POST /shop, PUT /shop, POST /voucher, POST /voucher/seckill
                        //       均不在排除列表中，需要登录认证
                ).order(1);
        // Token 刷新拦截器：拦截所有请求，从 Redis 中恢复用户信息并刷新 Token 有效期
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).addPathPatterns("/**").order(0);
    }
}
