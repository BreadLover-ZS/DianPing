package com.dish.review;

import com.dish.review.service.IBlogCommentsService;
import com.dish.review.service.IBlogService;
import com.dish.review.service.IFollowService;
import com.dish.review.service.IShopService;
import com.dish.review.service.IUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 功能补全集成测试
 *
 * 启动完整 Spring 上下文，验证本次补全的各模块 Service Bean 能被正确装配，
 * 确保无循环依赖、无注入失败。依赖远程 MySQL/Redis 环境（见 application.yaml）。
 *
 * <p>使用 test profile：禁用全部秒杀定时任务（Outbox、回滚、对账、库存扫描、
 * 确认超时），防止测试进程访问或修改远程业务状态。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
public class FeatureIntegrationTests {

    @Autowired
    private IBlogService blogService;

    @Autowired
    private IFollowService followService;

    @Autowired
    private IUserService userService;

    @Autowired
    private IBlogCommentsService blogCommentsService;

    @Autowired
    private IShopService shopService;

    /**
     * 验证 Spring 上下文加载成功，且所有补全模块的 Service Bean 均已装配
     */
    @Test
    void contextLoadsAndBeansWired() {
        assertNotNull(blogService, "IBlogService 未装配");
        assertNotNull(followService, "IFollowService 未装配");
        assertNotNull(userService, "IUserService 未装配");
        assertNotNull(blogCommentsService, "IBlogCommentsService 未装配");
        assertNotNull(shopService, "IShopService 未装配");
    }
}
