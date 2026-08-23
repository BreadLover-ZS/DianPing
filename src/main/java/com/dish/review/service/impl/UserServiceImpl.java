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
import com.dish.review.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
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
     * 在 Redis 内原子完成验证码读取、错误次数递增和成功后的删除。
     * 返回值：1=成功，0=验证码错误，-1=超过次数，-2=验证码不存在或已过期。
     */
    private static final DefaultRedisScript<Long> VERIFY_CODE_SCRIPT =
            new DefaultRedisScript<>(
                    "local code = redis.call('get', KEYS[1]) "
                            + "if not code then return -2 end "
                            + "local attempts = tonumber(redis.call('get', KEYS[2]) or '0') "
                            + "if attempts >= tonumber(ARGV[2]) then "
                            + "redis.call('del', KEYS[1]); redis.call('del', KEYS[2]); return -1 end "
                            + "if code == ARGV[1] then "
                            + "redis.call('del', KEYS[1]); redis.call('del', KEYS[2]); return 1 end "
                            + "attempts = redis.call('incr', KEYS[2]) "
                            + "if attempts == 1 then redis.call('expire', KEYS[2], ARGV[3]) end "
                            + "if attempts >= tonumber(ARGV[2]) then redis.call('del', KEYS[1]) end "
                            + "return 0",
                    Long.class
            );

    /**
     * 验证码发送模式（读取配置 dish-review.sms-code-mode）
     * test: 测试模式，接口直接返回验证码明文，便于本地联调
     * prod: 生产模式，接入真实短信通道发送
     */
    @Value("${dish-review.sms-code-mode:test}")
    private String smsCodeMode;

    /**
     * 发送手机验证码
     * 60秒发送频率限制，防止恶意刷接口
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

        if ("prod".equalsIgnoreCase(smsCodeMode)) {
            // 当前仓库没有真实短信适配器，生产模式必须 fail-closed，不能留下可登录验证码。
            log.error("生产短信通道未配置，拒绝伪造验证码发送结果");
            return Result.fail("短信服务暂不可用");
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

        // 5. 测试模式直接返回验证码，生产模式由真实短信适配器替换此分支。
        log.info("[测试模式] 验证码已生成，phone={}", maskPhone(phone));
        return Result.ok(code);
    }

    /**
     * 登录功能
     * 支持两种登录方式：
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

        // 兼容旧的 salt@MD5 密码：成功登录后升级为 BCrypt，避免长期保留弱哈希。
        if (PasswordEncoder.needsUpgrade(user.getPassword())) {
            user.setPassword(PasswordEncoder.encode(loginForm.getPassword()));
            updateById(user);
        }

        // 4. 保存用户信息到 Redis 并返回 token
        return saveUserToRedis(user);
    }

    /**
     * 验证码登录
     * 验证码尝试次数限制，最多5次，超过后验证码失效需重新获取
     *
     * @param loginForm 登录表单
     * @param phone     手机号
     * @return 登录结果
     */
    private Result loginByCode(LoginFormDTO loginForm, String phone) {
        String codeKey = RedisConstants.LOGIN_CODE_KEY + phone;
        String attemptKey = "login:code:attempt:" + phone;
        Long verifyResult = stringRedisTemplate.execute(
                VERIFY_CODE_SCRIPT,
                Arrays.asList(codeKey, attemptKey),
                loginForm.getCode() == null ? "" : loginForm.getCode(),
                String.valueOf(MAX_LOGIN_ATTEMPTS),
                String.valueOf(RedisConstants.LOGIN_CODE_TTL * 60L)
        );

        if (verifyResult == null || verifyResult == -2L) {
            return Result.fail("验证码错误或已过期");
        }
        if (verifyResult == -1L) {
            return Result.fail("验证码错误次数过多，请重新获取验证码");
        }
        if (verifyResult != 1L) {
            return Result.fail("验证码错误");
        }

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
     * 从 Redis 中删除用户 Token，使其立即失效
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
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(8));
        user.setRole(SystemConstants.ROLE_USER);
        // 保存用户到数据库
        save(user);
        return user;
    }

    /** 脱敏手机号，避免测试日志和生产日志泄露完整手机号。 */
    private String maskPhone(String phone) {
        if (StrUtil.isBlank(phone) || phone.length() < 7) {
            return "***";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    /**
     * 用户签到（基于 Redis BitMap，按月存储）
     * key 为 sign:{userId}:yyyyMM，第 N 天对应第 N-1 个 bit
     *
     * @return 签到结果
     */
    @Override
    public Result sign() {
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = RedisConstants.USER_SIGN_KEY + userId + keySuffix;
        int dayOfMonth = now.getDayOfMonth();
        // 第 N 天对应第 N-1 个 bit（bit 从 0 开始）
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    /**
     * 统计当前用户本月连续签到天数
     * 从今天开始向低位遍历，统计连续为 1 的 bit 个数
     *
     * @return 连续签到天数
     */
    @Override
    public Result signCount() {
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = RedisConstants.USER_SIGN_KEY + userId + keySuffix;
        int dayOfMonth = now.getDayOfMonth();
        // 取本月从第 1 天到今天的所有签到位（一个无符号整数）
        List<Long> result = stringRedisTemplate.opsForValue().bitField(key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if (result == null || result.isEmpty()) {
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }
        // 从最低位（今天）开始，统计连续为 1 的个数
        int count = 0;
        while ((num & 1) != 0) {
            count++;
            num >>>= 1;
        }
        return Result.ok(count);
    }
}
