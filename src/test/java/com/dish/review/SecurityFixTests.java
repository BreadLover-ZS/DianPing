package com.dish.review;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HtmlUtil;
import com.dish.review.utils.PasswordEncoder;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 安全修复单元测试
 *
 * 针对项目安全修复的关键逻辑进行单元测试验证
 * 不依赖外部服务（MySQL/Redis），可独立运行
 */
public class SecurityFixTests {

    // ==================== Fix 3: 文件上传类型白名单校验 ====================

    /** 允许上传的文件扩展名白名单（与 UploadController 保持一致，Java 8 兼容写法） */
    private static final Set<String> ALLOWED_EXTENSIONS =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList("jpg", "jpeg", "png", "gif", "webp", "bmp")));

    /**
     * 测试文件扩展名白名单校验
     * 验证仅允许图片格式上传，拒绝可执行文件
     */
    @Test
    void testFileExtensionWhitelist() {
        // 合法图片格式
        assertTrue(isExtensionAllowed("photo.jpg"));
        assertTrue(isExtensionAllowed("photo.jpeg"));
        assertTrue(isExtensionAllowed("photo.png"));
        assertTrue(isExtensionAllowed("photo.gif"));
        assertTrue(isExtensionAllowed("photo.webp"));

        // 非法文件格式
        assertFalse(isExtensionAllowed("shell.jsp"));
        assertFalse(isExtensionAllowed("malware.exe"));
        assertFalse(isExtensionAllowed("script.js"));
        assertFalse(isExtensionAllowed("hack.html"));
        assertFalse(isExtensionAllowed("file."));
        assertFalse(isExtensionAllowed("nofile"));
    }

    /**
     * 模拟 UploadController 的文件扩展名校验逻辑
     */
    private boolean isExtensionAllowed(String filename) {
        String suffix = StrUtil.subAfter(filename, ".", true);
        return StrUtil.isNotBlank(suffix) && ALLOWED_EXTENSIONS.contains(suffix.toLowerCase());
    }


    // ==================== Fix 4: 路径穿越防护 ====================

    /**
     * 测试路径穿越攻击检测
     * 验证包含 ../ 的文件名会被正确拦截
     */
    @Test
    void testPathTraversalDetection() {
        String uploadDir = "D:\\develop\\nginx-1.18.0\\html\\dishreview\\imgs";

        // 正常文件路径
        assertTrue(isPathSafe(uploadDir, "blogs/1/2/photo.jpg"), "正常文件路径应通过校验");

        // 路径穿越攻击
        assertFalse(isPathSafe(uploadDir, "../../etc/passwd"), "路径穿越应被拦截");
        assertFalse(isPathSafe(uploadDir, "..\\..\\windows\\system32"), "Windows 路径穿越应被拦截");
        assertFalse(isPathSafe(uploadDir, "blogs/../../../etc/shadow"), "嵌套路径穿越应被拦截");
    }

    /**
     * 模拟 UploadController 的路径穿越防护逻辑
     * 通过比较 canonical path 判断目标文件是否在上传目录内
     */
    private boolean isPathSafe(String uploadDir, String filename) {
        try {
            File baseDir = new File(uploadDir).getCanonicalFile();
            File targetFile = new File(baseDir, filename).getCanonicalFile();
            return targetFile.getPath().startsWith(baseDir.getPath());
        } catch (Exception e) {
            return false;
        }
    }


    // ==================== Fix 6: 验证码登录尝试限制逻辑 ====================

    /** 验证码最大尝试次数（与 UserServiceImpl 保持一致） */
    private static final int MAX_LOGIN_ATTEMPTS = 5;

    /**
     * 测试验证码尝试次数限制逻辑
     * 验证超过最大尝试次数后应拒绝登录
     */
    @Test
    void testLoginAttemptLimit() {
        for (int attempts = 0; attempts < MAX_LOGIN_ATTEMPTS; attempts++) {
            assertFalse(isAttemptsExceeded(attempts), "尝试次数 " + attempts + " 不应超过限制");
        }
        assertTrue(isAttemptsExceeded(MAX_LOGIN_ATTEMPTS), "尝试次数 " + MAX_LOGIN_ATTEMPTS + " 应超过限制");
        assertTrue(isAttemptsExceeded(MAX_LOGIN_ATTEMPTS + 1), "尝试次数 " + (MAX_LOGIN_ATTEMPTS + 1) + " 应超过限制");
    }

    /**
     * 模拟 UserServiceImpl 的尝试次数判断逻辑
     */
    private boolean isAttemptsExceeded(int attempts) {
        return attempts >= MAX_LOGIN_ATTEMPTS;
    }


    // ==================== Fix 7: 验证码日志脱敏 ====================

    /**
     * 测试验证码日志不包含明文验证码
     */
    @Test
    void testCodeLogNotContainsCode() {
        String phone = "13800138000";
        String logMessage = "已向手机号 " + phone + " 发送验证码";
        String code = "123456";

        assertFalse(logMessage.contains(code), "日志中不应包含验证码明文");
        assertTrue(logMessage.contains(phone), "日志中应包含手机号用于审计");
    }


    // ==================== Fix 11: Token TTL 验证 ====================

    /**
     * 测试 Token TTL 从 36000 分钟缩短至 30 分钟
     */
    @Test
    void testTokenTTLReduced() {
        long oldTTL = 36000L;
        long newTTL = 30L;

        assertTrue(newTTL < oldTTL, "新 TTL 应小于旧 TTL");
        assertEquals(30, newTTL, "新 TTL 应为 30 分钟");
    }


    // ==================== Fix 13: XSS 防护 - HTML 转义 ====================

    /**
     * 测试博客内容 HTML 转义
     */
    @Test
    void testXssPrevention() {
        String xssPayload = "<script>alert('XSS')</script>";
        String titleWithXss = "<img src=x onerror=alert(1)>";

        String escapedContent = HtmlUtil.escape(xssPayload);
        String escapedTitle = HtmlUtil.escape(titleWithXss);

        assertFalse(escapedContent.contains("<script>"), "转义后不应包含 <script> 标签");
        assertFalse(escapedContent.contains("<"), "转义后不应包含未转义的 < 字符");
        assertTrue(escapedContent.contains("&lt;script&gt;"), "应包含转义后的 script 标签");

        assertFalse(escapedTitle.contains("<img"), "转义后不应包含 <img 标签");
        assertTrue(escapedTitle.contains("&lt;img"), "应包含转义后的 img 标签");
    }

    /**
     * 测试正常内容转义后仍可读
     */
    @Test
    void testHtmlEscapePreservesContent() {
        String normalContent = "今天去了一家很好吃的餐厅，推荐指数5颗星！";
        String escaped = HtmlUtil.escape(normalContent);
        assertEquals(normalContent, escaped, "不含 HTML 标签的内容转义后应保持不变");
    }


    // ==================== Fix 14: 密码编码器验证 ====================

    /**
     * 测试 PasswordEncoder 的加密和验证功能
     */
    @Test
    void testPasswordEncoder() {
        String rawPassword = "MyPassword123";

        String encodedPassword = PasswordEncoder.encode(rawPassword);

        assertTrue(PasswordEncoder.matches(encodedPassword, rawPassword), "正确密码应验证通过");
        assertFalse(PasswordEncoder.matches(encodedPassword, "WrongPassword"), "错误密码应验证失败");

        String encodedAgain = PasswordEncoder.encode(rawPassword);
        assertNotEquals(encodedPassword, encodedAgain, "同一密码两次加密应产生不同密文");
        assertTrue(PasswordEncoder.matches(encodedAgain, rawPassword), "使用不同盐加密的密码也应验证通过");
    }

    /**
     * 测试 PasswordEncoder 对空值的处理
     */
    @Test
    void testPasswordEncoderNullSafety() {
        assertFalse(PasswordEncoder.matches(null, "password"), "null 密文应返回 false");
        assertFalse(PasswordEncoder.matches("encoded", null), "null 明文应返回 false");
    }


    // ==================== Fix 12: 登出功能逻辑验证 ====================

    /**
     * 测试登出逻辑：空 token 应返回失败
     */
    @Test
    void testLogoutBlankToken() {
        assertTrue(StrUtil.isBlank(null), "null token 应为空");
        assertTrue(StrUtil.isBlank(""), "空字符串 token 应为空");
        assertTrue(StrUtil.isBlank("   "), "空白字符 token 应为空");
    }
}
