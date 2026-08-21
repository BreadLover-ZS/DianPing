package com.dish.review;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 上下文启动冒烟测试。
 *
 * <p>使用 test profile：禁用全部秒杀定时任务，
 * 防止测试进程访问或修改远程 MySQL/Redis 业务状态。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class DishReviewApplicationTests {


}
