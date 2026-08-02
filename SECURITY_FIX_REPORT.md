# DishReview项目安全修复文档

## 项目概述
- 项目名称: dish-review (DishReview)
- 技术栈: Spring Boot 2.3.12 + MyBatis-Plus 3.4.3 + Redis + MySQL
- 代码版本: GitHub 7.14
- 修复时间: 2026-08-02
- 修复编号: Fix 1 ~ Fix 16

---

## Fix 1: 敏感凭证硬编码

### 问题根源
`application.yaml` 中 MySQL 密码 `MyStudy@2026_Sql` 和 Redis 密码以明文形式硬编码。一旦代码泄露，攻击者可直接获取数据库完整访问权限。

### 修复方案
所有敏感信息改用 Spring 环境变量占位符 `${ENV_VAR:default}`，默认值仅用于本地开发。

### 修改文件
- `application.yaml`

---

## Fix 2: 数据库和 Redis 公网暴露

### 问题根源
MySQL (`115.29.220.133:3306`) 和 Redis (`115.29.220.133:6379`) 使用公网 IP，任何人都可以尝试连接。

### 修复方案
配置文件默认连接地址改为 `127.0.0.1`（本地），通过环境变量在生产环境注入正确的内网地址。

### 修改文件
- `application.yaml`

---

## Fix 3: 文件上传接口无认证 + 无类型校验

### 问题根源
1. `MvcConfig.java` 将 `/upload/**` 排除在登录拦截器之外
2. `UploadController.java` 无文件类型校验、无大小限制

### 修复方案
1. 从 `MvcConfig.java` 的排除路径中移除 `/upload/**`
2. 添加文件扩展名白名单（`jpg/jpeg/png/gif/webp/bmp`）和大小限制（5MB）

### 修改文件
- `MvcConfig.java`、`UploadController.java`

---

## Fix 4: 文件删除接口路径穿越漏洞

### 问题根源
`filename` 参数用户可控，若传入 `../../重要文件` 可删除上传目录之外的任意文件。

### 修复方案
使用 `File.getCanonicalFile()` 获取规范化路径，比较目标文件路径是否以上传目录路径为前缀。

### 修改文件
- `UploadController.java`

---

## Fix 5: 管理类接口无鉴权

### 问题根源
`MvcConfig.java` 将 `/shop/**` 和 `/voucher/**` 整体排除在登录拦截器之外。

### 修复方案
`/shop/**` 细化为 `/shop/*` 和 `/shop/of/**`，`/voucher/**` 细化为 `/voucher/list/**`，仅查询接口公开。

### 修改文件
- `MvcConfig.java`

---

## Fix 6: 验证码可暴力破解 + 无发送频率限制

### 问题根源
验证码发送无频率限制，验证码校验无尝试次数限制，6位数字验证码可暴力破解。

### 修复方案
1. 使用 Redis `SETNX` 实现60秒频率限制
2. 记录尝试次数，超过5次后删除验证码要求重新获取

### 修改文件
- `IUserService.java`、`UserServiceImpl.java`

---

## Fix 7: 验证码记录在日志中

### 问题根源
`log.debug("发送验证码：{}给{}", code, phone)` 将验证码明文写入日志。

### 修复方案
改为仅记录手机号：`log.info("已向手机号 {} 发送验证码", phone)`

### 修改文件
- `UserServiceImpl.java`

---

## Fix 8: SQL 拼接注入风险

### 当前版本状态
当前 GitHub 7.14 版本不存在 `ORDER BY FIELD` 拼接，**本修复不适用**。

---

## Fix 9: ThreadLocal 内存泄漏风险

### 问题根源
`LoginInterceptor` 仅实现 `preHandle` 未实现 `afterCompletion`，异常场景下 ThreadLocal 可能未被清理。

### 修复方案
添加 `afterCompletion` 方法调用 `UserHolder.removeUser()` 作为纵深防御。

### 修改文件
- `LoginInterceptor.java`

---

## Fix 10: 秒杀下单 proxy 并发安全问题

### 当前版本状态
当前版本使用同步 `AopContext.currentProxy()`，基于 ThreadLocal 实现，是线程安全的，不存在跨线程共享 proxy 问题。**本修复不适用**。

---

## Fix 11: Session Token 有效期过长

### 问题根源
`LOGIN_USER_TTL = 36000L` 分钟 = 25天，Token 泄露后攻击窗口期过长。

### 修复方案
将 TTL 缩短至 30 分钟。

### 修改文件
- `RedisConstants.java`

---

## Fix 12: 登出功能未实现

### 问题根源
`logout()` 直接返回 `Result.fail("功能未完成")`。

### 修复方案
从请求头获取 token，删除 Redis 中对应的用户信息。

### 修改文件
- `IUserService.java`、`UserServiceImpl.java`、`UserController.java`

---

## Fix 13: XSS 漏洞 - 博客内容未过滤

### 问题根源
`BlogController.saveBlog()` 直接保存用户输入，无 HTML 转义。

### 修复方案
使用 `HtmlUtil.escape()` 对标题和内容进行 HTML 转义。

### 修改文件
- `BlogController.java`

---

## Fix 14: 密码登录功能缺失

### 问题根源
`login()` 只校验验证码，完全忽略 `password` 字段。`PasswordEncoder` 已实现但从未使用。

### 修复方案
判断 `password` 非空走密码登录，否则走验证码登录。抽取 `saveUserToRedis()` 公共方法。

### 修改文件
- `IUserService.java`、`UserServiceImpl.java`

---

## Fix 15: saveBlog Feed 流推送 Key 错误

### 当前版本状态
当前 GitHub 7.14 版本没有 Feed 流推送功能，**本修复不适用**。

---

## Fix 16: 无 HTTPS 配置

### 问题根源
应用仅使用 HTTP，Token 在请求头中明文传输。Nginx 缺少安全响应头。

### 修复方案
1. 添加安全响应头：`X-Frame-Options`、`X-Content-Type-Options`、`X-XSS-Protection`
2. 添加 HTTPS server 块配置模板

### 修改文件
- `nginx.conf`
