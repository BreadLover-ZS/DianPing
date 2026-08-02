package com.dish.review.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dish.review.dto.LoginFormDTO;
import com.dish.review.dto.Result;
import com.dish.review.dto.UserDTO;
import com.dish.review.entity.User;
import com.dish.review.mapper.UserMapper;
import com.dish.review.service.IUserService;
import com.dish.review.utils.PasswordEncoder;
import com.dish.review.utils.RedisConstants;
import com.dish.review.utils.RegexUtils;
import com.dish.review.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 用户服务实现类
 *
 * 【安全修复】
 * Fix 6: 验证码发送增加频率限制（60秒内同一手机号只能发送一次）
 * Fix 6: 验证码校验增加尝试次数限制（最多5次，超过后验证码失效）
 * Fix 7: 移除验证码明文日志输出
 * Fix 12: 实现登出功能（主动删除 Redis 中的 Token）
 * Fix 14: 实现密码登录功能（支持验证码和密码两种登录方式）
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** 验证码发送频率限制时间（秒） */
    private static final long CODE_SEND_INTERVAL = 60L;

    /** 验证码最大尝试次数 */
    private static final int MAX_LOGIN_ATTEMPTS = 5;

    /**
     * 验证码发送模式（读取配置 dish-review.sms-code-mode）
     * test: 测试模式，接口直接返回验证码明文，便于本地联调
     * prod: 生产模式，接入真实短信通道发送
     */
    @Value("${dish-review.sms-code-mode:test}")
    private String smsCodeMode;

    /**
     * 发送手机验证码
     *
     * 【安全修复 Fix 6/7】
     * 1. 增加60秒发送频率限制，防止恶意刷接口
     * 2. 移除验证码明文日志，仅记录手机号用于审计
     *
     * @param phone   手机号
     * @param session HttpSession
     * @return 发送结果
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1. 校验手机号格式
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式无效");
        }

        // 2. 频率限制：60秒内同一手机号只能发送一次验证码
        String sendLimitKey = "login:code:limit:" + phone;
        Boolean canSend = stringRedisTemplate.opsForValue()
                .setIfAbsent(sendLimitKey, "1", CODE_SEND_INTERVAL, TimeUnit.SECONDS);
        if (canSend == null || !canSend) {
            return Result.fail("发送验证码过于频繁，请" + CODE_SEND_INTERVAL + "秒后再试");
        }

        // 3. 生成6位数字验证码
        String code = RandomUtil.randomNumbers(6);

        // 4. 保存验证码到 Redis，有效期2分钟
        stringRedisTemplate.opsForValue().set(
                RedisConstants.LOGIN_CODE_KEY + phone, code,
                RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // 5. 发送验证码
        // 【安全修复 Fix 7】生产模式不再将验证码明文记录到日志中，仅记录手机号用于审计
        // 测试模式（test）下直接返回验证码明文，便于本地联调测试
        if ("prod".equalsIgnoreCase(smsCodeMode)) {
            log.info("已向手机号 {} 发送验证码", phone);
            return Result.ok();
        }
        log.info("[测试模式] 手机号 {} 的验证码为 {}", phone, code);
        return Result.ok(code);
    }

    /**
     * 登录功能
     *
     * 【安全修复 Fix 14】支持两种登录方式：
     * - 密码登录：当 password 字段非空时，使用密码校验登录
     * - 验证码登录：当 password 字段为空时，使用验证码校验登录
     *
     * @param loginForm 登录表单（phone, code, password）
     * @param session   HttpSession
     * @return 登录结果，成功返回 token
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1. 校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式无效");
        }

        // 2. 判断登录方式：password 非空则走密码登录，否则走验证码登录
        if (StrUtil.isNotBlank(loginForm.getPassword())) {
            return loginByPassword(loginForm, phone);
        } else {
            return loginByCode(loginForm, phone);
        }
    }

    /**
     * 密码登录
     *
     * @param loginForm 登录表单
     * @param phone     手机号
     * @return 登录结果
     */
    private Result loginByPassword(LoginFormDTO loginForm, String phone) {
        // 1. 根据手机号查询用户
        User user = query().eq("phone", phone).one();

        // 2. 校验用户是否存在且已设置密码
        if (user == null || StrUtil.isBlank(user.getPassword())) {
            return Result.fail("用户不存在或未设置密码，请使用验证码登录");
        }

        // 3. 校验密码
        if (!PasswordEncoder.matches(user.getPassword(), loginForm.getPassword())) {
            return Result.fail("手机号或密码错误");
        }

        // 4. 保存用户信息到 Redis 并返回 token
        return saveUserToRedis(user);
    }

    /**
     * 验证码登录
     *
     * 【安全修复 Fix 6】增加验证码尝试次数限制，最多5次，超过后验证码失效需重新获取
     *
     * @param loginForm 登录表单
     * @param phone     手机号
     * @return 登录结果
     */
    private Result loginByCode(LoginFormDTO loginForm, String phone) {
        // 1. 校验尝试次数，防止暴力破解
        String attemptKey = "login:code:attempt:" + phone;
        String attemptsStr = stringRedisTemplate.opsForValue().get(attemptKey);
        int attempts = attemptsStr == null ? 0 : Integer.parseInt(attemptsStr);
        if (attempts >= MAX_LOGIN_ATTEMPTS) {
            // 超过最大尝试次数，删除验证码，要求重新获取
            stringRedisTemplate.delete(RedisConstants.LOGIN_CODE_KEY + phone);
            stringRedisTemplate.delete(attemptKey);
            return Result.fail("验证码错误次数过多，请重新获取验证码");
        }

        // 2. 从 Redis 获取验证码并校验
        Object cacheCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        if (cacheCode == null || !cacheCode.equals(loginForm.getCode())) {
            // 验证码不一致，增加尝试次数计数
            stringRedisTemplate.opsForValue().set(attemptKey, String.valueOf(attempts + 1),
                    RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
            return Result.fail("验证码错误");
        }

        // 3. 验证码一致，清除尝试计数
        stringRedisTemplate.delete(attemptKey);
        stringRedisTemplate.delete(RedisConstants.LOGIN_CODE_KEY + phone);

        // 4. 根据手机号查询用户
        User user = query().eq("phone", phone).one();

        // 5. 判断用户是否存在
        if (user == null) {
            // 6. 不存在，创建新用户
            user = createUserWithPhone(phone);
        }

        // 7. 保存用户信息到 Redis 并返回 token
        return saveUserToRedis(user);
    }

    /**
     * 登出功能
     * 【安全修复 Fix 12】从 Redis 中删除用户 Token，使其立即失效
     *
     * @param token 用户登录令牌
     * @return 登出结果
     */
    @Override
    public Result logout(String token) {
        if (StrUtil.isBlank(token)) {
            return Result.fail("未登录");
        }
        // 从 Redis 中删除 Token 对应的用户信息
        Boolean deleted = stringRedisTemplate.delete(RedisConstants.LOGIN_USER_KEY + token);
        if (deleted != null && deleted) {
            log.info("用户登出成功");
            return Result.ok();
        }
        return Result.fail("Token 已失效");
    }

    /**
     * 将用户信息保存到 Redis 并返回 token
     * 抽取公共逻辑，供密码登录和验证码登录复用
     *
     * @param user 用户实体
     * @return 包含 token 的 Result
     */
    private Result saveUserToRedis(User user) {
        // 1. 生成 token 作为登录令牌
        String token = UUID.randomUUID().toString(true);

        // 2. 将 User 对象转为 UserDTO，避免敏感信息（如密码）存入 Redis
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);

        // 3. 将 UserDTO 转为 Hash 结构存储
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true).setFieldValueEditor((fieldName, fieldValue) -> {
                    if (fieldValue == null) {
                        return null;
                    }
                    return fieldValue.toString();
                }));

        // 4. 存储到 Redis
        stringRedisTemplate.opsForHash().putAll(RedisConstants.LOGIN_USER_KEY + token, userMap);

        // 5. 设置有效期
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token,
                RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        // 6. 返回 token
        return Result.ok(token);
    }

    /**
     * 根据手机号创建新用户
     * 新用户昵称默认为 "user_" + 手机号
     *
     * @param phone 手机号
     * @return 新创建的用户实体
     */
    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + phone);
        // 保存用户到数据库
        save(user);
        return user;
    }
}
