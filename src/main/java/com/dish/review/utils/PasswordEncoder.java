package com.dish.review.utils;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 密码编码工具。
 *
 * <p>新密码使用 BCrypt；历史“随机盐@MD5”密文仍可校验，
 * 登录成功后由 UserServiceImpl 渐进升级，避免一次性重置全部用户密码。</p>
 */
public final class PasswordEncoder {

    private static final BCryptPasswordEncoder BCRYPT =
            new BCryptPasswordEncoder();

    private PasswordEncoder() {
    }

    /** 使用自适应密码哈希生成新密文。 */
    public static String encode(String password) {
        Objects.requireNonNull(password, "password");
        return BCRYPT.encode(password);
    }

    /** 校验 BCrypt 或历史兼容格式。 */
    public static boolean matches(String encodedPassword, String rawPassword) {
        if (encodedPassword == null || rawPassword == null) {
            return false;
        }

        if (isBcrypt(encodedPassword)) {
            return BCRYPT.matches(rawPassword, encodedPassword);
        }

        int separator = encodedPassword.indexOf('@');
        if (separator <= 0 || separator == encodedPassword.length() - 1) {
            return false;
        }

        String salt = encodedPassword.substring(0, separator);
        String expectedDigest = encodedPassword.substring(separator + 1);
        String actualDigest = org.springframework.util.DigestUtils
                .md5DigestAsHex((rawPassword + salt)
                        .getBytes(StandardCharsets.UTF_8));
        return expectedDigest.equalsIgnoreCase(actualDigest);
    }

    /** 判断是否需要在登录成功后升级存储格式。 */
    public static boolean needsUpgrade(String encodedPassword) {
        return encodedPassword != null && !isBcrypt(encodedPassword);
    }

    private static boolean isBcrypt(String encodedPassword) {
        return encodedPassword.startsWith("$2a$")
                || encodedPassword.startsWith("$2b$")
                || encodedPassword.startsWith("$2y$");
    }
}
