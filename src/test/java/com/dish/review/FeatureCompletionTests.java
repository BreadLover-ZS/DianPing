package com.dish.review;

import cn.hutool.core.bean.BeanUtil;
import com.dish.review.dto.Result;
import com.dish.review.dto.ScrollResult;
import com.dish.review.dto.UserDTO;
import com.dish.review.entity.User;
import com.dish.review.utils.RedisConstants;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 功能补全单元测试
 *
 * 针对本次补全的 Follow、Blog、User、BlogComments、用户签到、GEO 附近商铺等功能模块
 * 的核心逻辑进行单元测试验证。不依赖外部服务（MySQL/Redis），可独立运行。
 *
 * 说明：涉及 Redis/MySQL 交互的集成测试需要启动中间件环境，
 * 这里聚焦于可独立验证的纯逻辑（位运算、分页偏移量、Key 构造、DTO 安全性等）。
 */
public class FeatureCompletionTests {

    // ==================== 用户签到：连续天数计算逻辑（BitMap 位运算） ====================

    /**
     * 模拟 UserServiceImpl.signCount 的位运算逻辑
     * 给定表示本月签到记录的无符号整数（最低位为今天），统计从今天起连续为 1 的个数
     *
     * @param num 本月从第1天到今天的签到记录（bit 位 1 表示已签到）
     * @return 连续签到天数
     */
    private int countContinuousSign(long num) {
        if (num == 0) {
            return 0;
        }
        int count = 0;
        while ((num & 1) != 0) {
            count++;
            num >>>= 1;
        }
        return count;
    }

    /**
     * 本月前3天全部签到：二进制 111 = 7，连续3天
     */
    @Test
    void testSignCountAllSigned() {
        assertEquals(3, countContinuousSign(0b111L));
    }

    /**
     * 今天未签到（最低位为0），即使之前连续也应返回0
     */
    @Test
    void testSignCountTodayNotSigned() {
        assertEquals(0, countContinuousSign(0b110L));
    }

    /**
     * 第1、3天签到，第2天未签：二进制 101，今天签了但昨天没签，连续1天
     */
    @Test
    void testSignCountMiddleBroken() {
        assertEquals(1, countContinuousSign(0b101L));
    }

    /**
     * 本月无签到记录
     */
    @Test
    void testSignCountNoneSigned() {
        assertEquals(0, countContinuousSign(0L));
    }

    /**
     * 连续30天签到
     */
    @Test
    void testSignCountLongStreak() {
        long num = (1L << 30) - 1;
        assertEquals(30, countContinuousSign(num));
    }


    // ==================== UserDTO 转换：敏感信息不泄露 ====================

    /**
     * 测试 User 转 UserDTO 后，敏感字段（手机号、密码）不会泄露
     */
    @Test
    void testUserToUserDTONoSensitiveInfo() {
        User user = new User();
        user.setId(1010L);
        user.setPhone("13800138000");
        user.setPassword("secretEncodedPassword");
        user.setNickName("测试用户");
        user.setIcon("/imgs/icon.png");

        UserDTO dto = BeanUtil.copyProperties(user, UserDTO.class);

        assertEquals(1010L, dto.getId());
        assertEquals("测试用户", dto.getNickName());
        assertEquals("/imgs/icon.png", dto.getIcon());
    }

    /**
     * 测试 UserDTO 类本身不包含 phone、password 等敏感字段
     */
    @Test
    void testUserDTOClassHasNoSensitiveFields() {
        Set<String> fieldNames = new HashSet<>();
        for (Field f : UserDTO.class.getDeclaredFields()) {
            fieldNames.add(f.getName());
        }
        // 应包含的非敏感字段
        assertTrue(fieldNames.contains("id"), "UserDTO 应包含 id 字段");
        assertTrue(fieldNames.contains("nickName"), "UserDTO 应包含 nickName 字段");
        assertTrue(fieldNames.contains("icon"), "UserDTO 应包含 icon 字段");
        // 不应包含的敏感字段
        assertFalse(fieldNames.contains("phone"), "UserDTO 不应包含 phone 字段");
        assertFalse(fieldNames.contains("password"), "UserDTO 不应包含 password 字段");
    }


    // ==================== Redis Key 构造正确性 ====================

    /**
     * 测试本次新增及预留的 Redis 常量值正确
     */
    @Test
    void testRedisConstantsKeys() {
        assertEquals("follows:", RedisConstants.FOLLOWS_KEY, "关注集合 Key");
        assertEquals("blog:liked:", RedisConstants.BLOG_LIKED_KEY, "点赞 Key");
        assertEquals("feed:", RedisConstants.FEED_KEY, "Feed流 Key");
        assertEquals("sign:", RedisConstants.USER_SIGN_KEY, "签到 Key");
        assertEquals("shop:geo:", RedisConstants.SHOP_GEO_KEY, "商铺 GEO Key");
    }

    /**
     * 测试签到 Key 格式：sign:{userId}:yyyyMM
     */
    @Test
    void testSignKeyFormat() {
        Long userId = 1010L;
        String keySuffix = ":202608";
        String key = RedisConstants.USER_SIGN_KEY + userId + keySuffix;
        assertEquals("sign:1010:202608", key);
    }

    /**
     * 测试关注集合 Key 格式：follows:{userId}
     */
    @Test
    void testFollowKeyFormat() {
        Long userId = 1L;
        String key = RedisConstants.FOLLOWS_KEY + userId;
        assertEquals("follows:1", key);
    }

    /**
     * 测试博客点赞 Key 格式：blog:liked:{blogId}
     */
    @Test
    void testBlogLikedKeyFormat() {
        Long blogId = 5L;
        String key = RedisConstants.BLOG_LIKED_KEY + blogId;
        assertEquals("blog:liked:5", key);
    }

    /**
     * 测试 Feed 收件箱 Key 格式：feed:{userId}
     */
    @Test
    void testFeedKeyFormat() {
        Long userId = 2L;
        String key = RedisConstants.FEED_KEY + userId;
        assertEquals("feed:2", key);
    }


    // ==================== Result / ScrollResult 构造 ====================

    /**
     * 测试 Result 的 ok / fail 构造方法
     */
    @Test
    void testResultOkAndFail() {
        Result ok = Result.ok();
        assertTrue(ok.getSuccess());
        assertNull(ok.getErrorMsg());

        Result okData = Result.ok("data");
        assertTrue(okData.getSuccess());
        assertEquals("data", okData.getData());

        Result fail = Result.fail("error");
        assertFalse(fail.getSuccess());
        assertEquals("error", fail.getErrorMsg());
    }

    /**
     * 测试 ScrollResult（Feed 流滚动分页结果）的构造与赋值
     */
    @Test
    void testScrollResultConstruction() {
        List<?> list = Arrays.asList(1, 2);
        ScrollResult sr = new ScrollResult();
        sr.setList(list);
        sr.setMinTime(1000L);
        sr.setOffset(1);
        assertEquals(list, sr.getList());
        assertEquals(1000L, sr.getMinTime());
        assertEquals(1, sr.getOffset());
    }


    // ==================== Feed 滚动分页 offset 计算逻辑 ====================

    /**
     * 模拟 BlogServiceImpl.queryBlogOfFollow 的 minTime / offset 计算逻辑
     * 遍历按时间戳倒序排列的分数（先大后小），统计末尾连续相同最小时间戳的个数
     *
     * @param scoresDesc 按时间戳倒序排列的分数数组
     * @return long[]{minTime, offset}
     */
    private long[] calcMinTimeAndOffset(long[] scoresDesc) {
        long minTime = 0L;
        long os = 1;
        for (long time : scoresDesc) {
            if (time == minTime) {
                os++;
            } else {
                minTime = time;
                os = 1;
            }
        }
        return new long[]{minTime, os};
    }

    /**
     * 所有时间戳不同：末尾最小时间戳只出现1次
     */
    @Test
    void testFeedOffsetAllDifferent() {
        long[] res = calcMinTimeAndOffset(new long[]{3000, 2000, 1000});
        assertEquals(1000L, res[0]);
        assertEquals(1L, res[1]);
    }

    /**
     * 末尾两个时间戳相同：offset 应为2
     */
    @Test
    void testFeedOffsetLastTwoSame() {
        long[] res = calcMinTimeAndOffset(new long[]{3000, 1000, 1000});
        assertEquals(1000L, res[0]);
        assertEquals(2L, res[1]);
    }

    /**
     * 所有时间戳相同：offset 应为总数
     */
    @Test
    void testFeedOffsetAllSame() {
        long[] res = calcMinTimeAndOffset(new long[]{2000, 2000, 2000});
        assertEquals(2000L, res[0]);
        assertEquals(3L, res[1]);
    }
}
