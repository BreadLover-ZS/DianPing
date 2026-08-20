# DishReview 项目学习总览（精简版）

## 1. 项目

- Controller：接收请求、获取路径/查询参数，调用 Service，返回 `Result`。
- Service：校验业务规则、组织 Redis 与数据库调用、定义事务边界。
- Mapper / MyBatis-Plus：执行数据库读写；实体类映射数据表。
- Redis：缓存、短期会话、验证码、限流、排行榜/收件箱、分布式锁、ID 序列。

## 2. 数据模型

主要实体关系：

```text
User --< Blog --< BlogComments
User --< Follow >-- User
ShopType --< Shop --< Voucher -- SeckillVoucher
User --< VoucherOrder >-- Voucher
```

| 表                                   | 作用       | 当前值得关注的约束/查询                               |
| ----------------------------------- | -------- | ------------------------------------------ |
| `tb_user` / `tb_user_info`          | 用户和扩展资料  | 手机号登录、最小化返回 `UserDTO`                      |
| `tb_shop` / `tb_shop_type`          | 商铺与分类    | 按类型分页、按经纬度附近查询                             |
| `tb_blog` / `tb_blog_comments`      | 探店内容与评论  | 热门排序、作者信息回填、点赞计数                           |
| `tb_follow`                         | 关注关系     | `(user_id, follow_user_id)` 唯一索引；同时有被关注者索引 |
| `tb_voucher` / `tb_seckill_voucher` | 普通券与秒杀库存 | 秒杀表以 `voucher_id` 为主键                      |
| `tb_voucher_order`                  | 秒杀订单     | `(user_id, voucher_id)` 联合唯一索引兜底一人一单       |
| `tb_seckill_order_event`            | 秒杀消息事件   | `event_id` 主键、`order_id` 唯一、状态与补偿时间索引       |

## 3. 业务链路

### 3.1 登录与会话

```text
POST /user/code
  -> 校验手机号
  -> Redis SETNX 限制 60 秒发送频率
  -> Redis 保存验证码（2 分钟）

POST /user/login
  -> 密码登录，或验证码登录（最多 5 次错误）
  -> 查询/创建用户
  -> Redis Hash 保存 UserDTO，返回随机 Token

任意请求
  -> RefreshTokenInterceptor 从 authorization 读取 Token
  -> Redis Hash 恢复 UserDTO 到 ThreadLocal
  -> 刷新 TTL
  -> LoginInterceptor 对非公开接口要求登录
  -> afterCompletion 清理 ThreadLocal
```

### 3.2 商铺详情、分类与附近查询

```text
GET /shop/{id}
  -> ShopServiceImpl.queryById()
  -> CacheClient.queryWithPassThrough()
  -> Redis 命中直接返回
  -> 未命中回源 MySQL，写入正常缓存
  -> 数据库不存在则缓存空字符串，防止缓存穿透
```

其他商铺能力：

- 店铺类型列表使用 Redis List 缓存；未命中后回源数据库并写回。
- 附近商铺使用 Redis GEO；缓存不存在时从数据库加载该类型坐标，再按距离查询并手动分页。
- 商铺更新先更新数据库，再删除对应缓存，属于 Cache Aside 的失效策略。

### 3.3 博客、关注、点赞与 Feed

```text
发博客
  -> 保存 tb_blog
  -> 查询粉丝
  -> 向每个粉丝的 feed:{userId} ZSet 写入 blogId，score 为时间戳

查询关注 Feed
  -> 从 ZSet 按 score 倒序滚动分页
  -> 回查 Blog 并按 ZSet 顺序重排

点赞
  -> 对 blogId + userId 获取 Redis 锁
  -> Redis ZSet 判断是否已赞
  -> 更新数据库 liked 计数
  -> 写入/删除 ZSet；Redis 失败时尽力补偿数据库计数
```

关注关系以 MySQL 为事实源、Redis Set 为共同关注加速结构：关注写库后同步 Set，取消关注时同时删除；共同关注使用 Redis Set 交集后回查用户 DTO。数据库的关注联合唯一索引会将并发重复关注转为可处理的重复键异常。

### 3.4 秒杀下单：Lua 预扣、事件状态与 RabbitMQ

```text
POST /voucher-order/seckill/{voucherId}
  -> 校验登录、秒杀时间窗
  -> Lua 原子判断库存/用户集合并预扣
  -> 生成 orderId/eventId，写 tb_seckill_order_event(PENDING)
  -> RabbitMQ 持久化发布，Confirm/Return 更新事件状态
  -> 消费者事务内条件扣 MySQL 库存、写订单、标记 CONSUMED
  -> 发布结果未知时按 1/2/4 秒补偿；消费重试耗尽进入 DLQ
```

这里仍然是分层并发控制，不应简单说成只用了乐观锁或悲观锁：

| 目标            | 当前做法                                      | 准确分类                       |
| ------------- | ----------------------------------------- | -------------------------- |
| 防止同一用户重复预扣 | Lua 对库存和用户 Set 的原子判断 | Redis 脚本原子性 |
| 防止数据库超卖 | 消费者 `stock > 0` 条件更新，以影响行数判断成功 | 乐观式条件更新；不是 `@Version` 版本号锁 |
| 保证数据库步骤同成同败 | `VoucherOrderHandler.createOrder()` 的 `@Transactional` | 事务边界，不是锁 |
| 多实例订单 ID 唯一   | Redis `INCR` + 时间戳高位                      | 分布式 ID 生成                  |

数据库已增加 `(user_id, voucher_id)` 联合唯一索引，Redis 锁失效或消息重复消费时仍可拒绝重复订单；业务代码还需把重复键识别为幂等结果。

RabbitMQ 改造已经接入入口、消费者、事件状态和有限补偿；真实 RabbitMQ 连接、并发压测与故障演练仍需单独验收。

## 4. Redis 使用总表

| Key/结构                        | 用途                          | 当前调用位置                          |
| ----------------------------- | --------------------------- | ------------------------------- |
| `login:code:*` String         | 验证码                         | `UserServiceImpl`               |
| `login:code:limit:*` String   | 验证码发送频率限制                   | `UserServiceImpl`               |
| `login:token:*` Hash          | Token 对应的 `UserDTO` 与滑动 TTL | 登录和两个拦截器                        |
| `cache:shop:*` String         | 商铺对象或空值缓存                   | `CacheClient`、`ShopServiceImpl` |
| `shopType:typeList` List      | 店铺类型缓存                      | `ShopTypeServiceImpl`           |
| `shop:geo:*` GEO              | 附近商铺坐标索引                    | `ShopServiceImpl`               |
| `blog:liked:*` ZSet           | 一人一赞与按点赞时间取用户               | `BlogServiceImpl`               |
| `feed:*` ZSet                 | 粉丝收件箱与滚动分页                  | `BlogServiceImpl`               |
| `follows:*` Set               | 关注集合与共同关注                   | `FollowServiceImpl`             |
| `sign:*` Bitmap               | 月度签到与连续签到统计                 | `UserServiceImpl`               |
| `lock:order:*` String         | 历史同步秒杀用户锁（当前异步入口不使用） | `SimpleRedisLock`（保留实现） |
| `icr:order:yyyy:MM:dd` String | 订单号自增序列                     | `RedisIdWorker`                 |
| `seckill:stock:{id}` String   | Lua 预校验与预扣库存                  | `SeckillVoucherLuaExecutor`、`VoucherOrderServiceImpl` |
| `seckill:order:{id}` Set      | Lua 判断一人一单                     | `SeckillVoucherLuaExecutor`、`VoucherOrderServiceImpl` |

补充边界：Java 只使用带花括号的 Key；消费者默认由 `SECKILL_RABBIT_CONSUMER_ENABLED` 控制，远端 RabbitMQ 未监听时不要开启。历史的不带花括号 Key 仍可能保留，不能与当前 Key 混用。

## 5. 安全、测试与部署现状

### 已有保护

- 登录：公开接口白名单；Token 续期；请求完成清理 ThreadLocal。
- 写接口：上传、商铺新增/更新、优惠券新增等不在公开白名单内。
- 上传：图片扩展名白名单、5 MB 限制、UUID 文件名、删除时 canonical path 校验。
- 内容：博客标题和内容在上层做 HTML 转义。
- 异常：全局异常处理记录服务端错误，对客户端返回通用失败消息。
- 代理：Nginx 已配置基本安全响应头和 `/api` 反向代理。

### 仍需验证或优化的边界

| 范围    | 当前事实                                             | 下一步                                   |
| ----- | ------------------------------------------------ | ------------------------------------- |
| 凭据    | 配置应仅通过环境变量/密钥管理提供                                | 不在仓库保留真实账号、密码或地址；上线前轮换已暴露的密钥          |
| HTTPS | Nginx 的 HTTPS 示例仍为注释配置                           | 真实证书、跳转、TLS 验证后才可宣称启用 HTTPS           |
| 缓存重建锁 | `CacheClient` / `ShopServiceImpl` 的旧辅助锁写固定值并直接删除 | 若启用该方案，应复用带持有者标识和 Lua 解锁的实现           |
| 秒杀幂等  | Redis Lua 用户集合 + 消费者查询 + 数据库联合唯一索引 | 增加重复投递、ACK 丢失和并发场景的真实验收 |
| 测试    | 有 Spring 上下文、功能与安全相关 JUnit 测试                    | 增加真实 Redis/MySQL 集成测试、并发压测、接口级回归与发布验收 |
| 部署    | Nginx -> 本机 Spring Boot；配置依赖外部 MySQL/Redis       | 用独立环境变量、健康检查、日志/监控和备份恢复验证             |

## 6. 面试时的项目表达

### 30 秒版本

> 这是一个基于 Spring Boot、MyBatis-Plus、MySQL、Redis 和 RabbitMQ 的餐饮点评项目。我重点理解了缓存、Feed 和秒杀调用链；当前秒杀入口使用 Redis Lua 预扣、事件表和 RabbitMQ 异步落库，并通过唯一索引和消费者幂等兜底。真实依赖和并发验收仍需补充。

### 追问要点

| 题目           | 回答核心                                                      |
| ------------ | --------------------------------------------------------- |
| 商铺缓存如何防穿透？   | 缓存未命中回源数据库；不存在时缓存空字符串并设置较短 TTL。                           |
| 缓存击穿如何处理？    | 代码保留逻辑过期和互斥重建方案；但默认入口当前采用缓存穿透方案，不能混淆。                     |
| 一人一单如何保证？    | Redis Lua 用户集合先拦截，消费者查询和数据库联合唯一索引最终兜底；重复消息按幂等成功处理。            |
| 为什么库存不超卖？    | 最终由 `UPDATE ... WHERE stock > 0` 的原子条件更新决定；影响行数为 0 即失败。   |
| 锁是乐观还是悲观？    | 用户 Redis 锁是悲观分布式锁；库存扣减是乐观式条件更新；事务不是锁。                     |
| 登录用户如何跨请求传递？ | Token 在 Redis Hash 中映射 UserDTO；刷新拦截器写入 ThreadLocal，结束后清理。 |

表达纪律：先说“当前代码已做什么”，再说“存在什么边界”，最后才说“我会如何优化”。不要把保留的辅助方法、常量或注释中的方案描述成正在使用的生产能力。

## 7. 复习清单

完成以下问题的口述，说明已掌握本项目：

1. 从一次 `/shop/{id}` 请求讲到 Redis、MySQL 和空值缓存。
2. 解释 Token Hash、滑动 TTL、ThreadLocal 清理各自解决什么问题。
3. 解释 ZSet 在点赞与 Feed 中的不同 score、查询和分页用途。
4. 解释 Redis 锁、条件扣库存、事务三者分别保障什么。
5. 说清历史 Redis 用户锁、当前 Lua 用户集合与数据库唯一索引各自的边界。
6. 区分默认商铺缓存入口与保留的逻辑过期/互斥锁辅助实现。
7. 说清上线前还必须验证的 HTTPS、凭据、并发、真实依赖和接口回归。

---
