package com.dish.review.service;

import com.dish.review.dto.LoginFormDTO;
import com.dish.review.dto.Result;
import com.dish.review.mapper.UserMapper;
import com.dish.review.service.impl.UserServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import javax.servlet.http.HttpSession;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证验证码登录使用原子 Lua 结果，并在失败时不访问用户表。 */
class UserServiceImplVerificationTests {

    private StringRedisTemplate redisTemplate;
    private UserMapper userMapper;
    private UserServiceImpl service;
    private LoginFormDTO form;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        userMapper = mock(UserMapper.class);
        service = new UserServiceImpl();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);
        ReflectionTestUtils.setField(service, "baseMapper", userMapper);

        form = new LoginFormDTO();
        form.setPhone("13800138000");
        form.setCode("123456");
    }

    @Test
    void wrongCodeUsesLuaAndStopsBeforeDatabase() {
        when(redisTemplate.execute(
                any(RedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenReturn(0L);

        Result result = service.login(form, mock(HttpSession.class));

        assertFalse(result.getSuccess());
        assertEquals("验证码错误", result.getErrorMsg());
        verify(redisTemplate).execute(
                any(RedisScript.class), anyList(), anyString(), anyString(), anyString());
        verify(userMapper, never()).selectList(any());
    }

    @Test
    void exhaustedCodeReturnsSpecificFailure() {
        when(redisTemplate.execute(
                any(RedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenReturn(-1L);

        Result result = service.login(form, mock(HttpSession.class));

        assertFalse(result.getSuccess());
        assertEquals("验证码错误次数过多，请重新获取验证码", result.getErrorMsg());
        verify(userMapper, never()).selectList(any());
    }
}
