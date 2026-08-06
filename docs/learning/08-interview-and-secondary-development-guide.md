# DishReview：面试与二次开发深读手册

> 目标：能基于当前代码讲清项目，而不是背“黑马点评”模板答案；读完后知道从哪里开始二次开发、哪些地方不能直接照搬到生产。
>
> 事实边界：本文以当前仓库 `main` 分支源码为准。代码中保留的辅助方法、常量和 README 中的设想，**不等于当前请求入口已经使用或已经完成生产验证**。

## 1. 先用两分钟说清这个项目

DishReview 是一个 Java 8 的餐饮点评系统，后端采用 Spring Boot + MyBatis-Plus，业务数据在 MySQL，Redis 用于登录会话、缓存、地理位置检索、点赞/Feed、签到、分布式锁和订单 ID。

用户可以验证码或密码登录，按分类/位置查商铺，发布探店笔记、点赞评论、关注用户并查看 Feed 流，还可以领取或秒杀优惠券。项目最值得讲的不是“功能多”，而是以下四组设计：

1. Token 放 Redis Hash、请求内放 `ThreadLocal`，通过两个拦截器实现认证和滑动续期。
2. 商铺详情采用 Cache Aside + 空值缓存防穿透；附近商铺使用 Redis GEO。
3. 点赞和 Feed 使用 ZSet，关注关系以 MySQL 为事实源、Redis Set 为加速索引。
4. 秒杀用 Redis 用户锁、MySQL 条件扣库存和 Spring 事务组合控制并发。

**面试表达纪律：**先说“代码已实现什么”，再说“它保障什么”，最后主动补充“目前的边界和我的改进方案”。这会比把项目包装成高并发生产系统可靠得多。

---

## 2. 架构、启动与阅读地图

```text
浏览器（nginx/html/dishreview，Vue 2 静态页面）
        │ /api/*
        ▼
Nginx（静态资源 + 反向代理到 127.0.0.1:8081）
        ▼
Spring Boot
  RefreshTokenInterceptor → LoginInterceptor → Controller → Service → Mapper
        │                         │                    │         │
        └──── Redis（会话/缓存/集合/锁） ───────────────┴── MySQL ┘
```

| 层级 | 重点位置 | 你应能回答的问题 |
| --- | --- | --- |
| 启动与配置 | `DishReviewApplication.java`、`application.yaml` | 为什么秒杀能通过 `AopContext` 调代理？依赖哪些外部服务？ |
| Web 入口 | `controller/`、`config/MvcConfig.java` | 哪些接口公开？写操作为何需要登录？ |
| 认证上下文 | `utils/RefreshTokenInterceptor.java`、`LoginInterceptor.java`、`UserHolder.java` | Token 怎样变成当前用户？为什么必须清理 ThreadLocal？ |
| 业务规则 | `service/impl/` | 每个场景的数据库事实源、Redis 加速结构和事务边界是什么？ |
| 数据访问 | `mapper/`、`resources/mapper/`、`db/dish_review.sql` | SQL 的条件、索引和唯一约束是否能兜底？ |
| 基础设施 | `utils/CacheClient.java`、`SimpleRedisLock.java`、`RedisIdWorker.java` | 缓存、锁、ID 的算法和边界是什么？ |
| 边缘部署 | `nginx/conf/nginx.conf`、`test/` | 当前已有的安全措施和未验证的上线能力有哪些？ |

### 一次普通受保护请求的完整生命周期

```text
Authorization 请求头
  → RefreshTokenInterceptor
      → 读取 login:token:{token} Hash
      → 转为 UserDTO，写入 UserHolder(ThreadLocal)
      → 刷新 Redis 会话 TTL
  → LoginInterceptor
      → 无 UserDTO：401；有：放行
  → Controller / Service
      → UserHolder.getUser() 获取当前用户
  → afterCompletion
      → 清理 ThreadLocal
```

两个拦截器的职责不能混为一谈：刷新拦截器对所有路径执行，负责“尽量恢复登录态”；登录拦截器仅对非白名单接口执行，负责“是否必须登录”。`afterCompletion` 清理很关键，因为 Tomcat 线程会复用；不清理可能让下一请求读到上一个用户。

---

## 3. 模块总览：数据从哪里来、到哪里去

```text
User ──< Blog ──< BlogComments
  │       │
  └──< Follow >── User

ShopType ──< Shop ──< Voucher ── SeckillVoucher
User ──< VoucherOrder >── Voucher
```

| 模块 | 主要表 | Redis 结构 | 当前核心事实 |
| --- | --- | --- | --- |
| 用户认证 | `tb_user`、`tb_user_info` | String、Hash | 验证码限流/校验；Token 映射最小 `UserDTO` |
| 商铺 | `tb_shop`、`tb_shop_type` | String、List、GEO | 详情空值缓存、分类列表缓存、5km GEO 查询 |
| 内容互动 | `tb_blog`、`tb_blog_comments` | ZSet | 点赞成员关系在 ZSet，DB 保存点赞计数 |
| 社交 | `tb_follow` | Set、ZSet | MySQL 是关注事实源；Set 做共同关注；ZSet 做收件箱 |
| 优惠券秒杀 | `tb_voucher`、`tb_seckill_voucher`、`tb_voucher_order` | String、INCR | 条件更新扣库存，用户维度 Redis 锁防重复请求 |
| 签到 | `tb_sign`（目前未作为实际写入） | Bitmap | 实际按月写 Redis 位图 |

### 必须记住的数据库约束

| 表 | 已有约束/索引 | 影响 |
| --- | --- | --- |
| `tb_user` | 手机号唯一索引 | 验证码自动注册时可避免相同手机号重复用户，但仍应处理并发插入异常。 |
| `tb_follow` | `(user_id, follow_user_id)` 唯一索引；`follow_user_id` 索引 | 关注关系可做数据库最终去重，按被关注者找粉丝更有索引支撑。 |
| `tb_seckill_voucher` | `voucher_id` 主键 | 一张券对应一条秒杀库存记录。 |
| `tb_shop` | `type_id` 索引 | 按分类查询有基础索引。 |
| `tb_voucher_order` | 只有订单主键 | **没有** `(user_id, voucher_id)` 唯一约束，不能作为“一人一单”的最终数据库兜底。 |

> 注意：初始化 SQL 已含关注索引，而 `db/migration/20260803_add_follow_indexes.sql` 也试图创建它们。新环境执行完整初始化脚本后，再执行该迁移前应先检查索引是否已存在，否则可能报重复索引错误。项目没有引入自动迁移框架，迁移执行顺序需要人为管理。

---

## 4. 用户认证、会话与签到

### 4.1 验证码与登录

```text
POST /user/code
  → 手机号格式校验
  → SETNX login:code:limit:{phone}，TTL 60 秒
  → 生成 6 位验证码
  → SET login:code:{phone}，TTL 2 分钟

POST /user/login
  → 有 password：校验盐值摘要
  → 否则：校验验证码，连续错误最多 5 次
  → 查用户；验证码登录无用户时创建用户
  → 随机 Token
  → login:token:{token} Hash 保存 UserDTO，TTL 30 分钟
```

### 4.2 为什么 Token 不直接放用户对象或 JWT

当前实现是“随机 Token + Redis 会话”。Redis Hash 中保存 `UserDTO`，而不是完整 `User`，避免把密码等字段放进会话。优点是服务端可以登出时删除 Token、续期时刷新 TTL，也便于未来做黑名单/强制下线；代价是每个认证请求都需要访问 Redis。

它不是 JWT：JWT 通常能本地验签、减少会话查询，但吊销、权限变更和过期控制需要额外设计。面试时不要说“JWT 一定更好”，应按是否需要服务端即时失效、多端管理、Redis 可用性来取舍。

### 4.3 密码实现的真实边界

`PasswordEncoder` 使用随机盐和 `MD5(password + salt)`，持久化格式为 `salt@hash`。盐能避免同密码得到固定摘要，但 **MD5 迭代成本太低，不能作为生产级密码存储方案**。

二次开发应迁移到 BCrypt 或 Argon2：新注册直接使用新算法；旧用户首次成功登录后升级摘要，或走安全的批量重置流程。迁移期间要支持旧、新格式校验，不能直接改算法导致全部用户无法登录。

### 4.4 签到为什么用 Bitmap

键格式为 `sign:{userId}:yyyyMM`，每月一个 Bitmap；第 N 天签到写第 N-1 位。连续签到统计用 `BITFIELD` 取从当月第一天到今天的位串，再从低位连续计数 `1`。

它适合“人 × 天”的布尔状态：空间小、写入 O(1)、连续天数无需逐行扫描。它不适合存签到时间、地点等明细；那些需要单独的事件表。

---

## 5. 商铺：缓存、分类与附近查询

### 5.1 商铺详情：当前默认链路是空值缓存

`ShopServiceImpl.queryById()` 当前调用 `CacheClient.queryWithPassThrough()`：

```text
GET /shop/{id}
  → GET cache:shop:{id}
  → 有非空 JSON：反序列化后返回
  → 值是空字符串：返回不存在
  → key 不存在：查 MySQL
        → 查不到：缓存空字符串（短 TTL）
        → 查到：缓存 Shop（正常 TTL）
```

这解决的是**缓存穿透**：有人不断请求不存在的 ID 时，空值缓存让后续请求不再穿透到数据库。它不等于完全消除风险：大量随机 ID 会产生大量缓存键，仍可以叠加参数校验、布隆过滤器、限流等方案。

### 5.2 缓存更新：为什么是“先更新库，再删缓存”

商铺更新在事务中更新 MySQL 后删除 `cache:shop:{id}`。这属于 Cache Aside 的失效策略：读操作负责回填，写操作让旧缓存失效。比“更新数据库后同步更新缓存”更容易避免复杂对象的漏字段问题。

但要能回答并发窗口：

```text
线程 A：更新 DB ────── 删除缓存
线程 B：          读到旧 DB / 回填旧缓存
```

极端交错下仍可能短暂回填旧值。常用增强包括消息队列/订阅 binlog 做最终失效、延迟双删、版本号或更严格的一致性方案；不是简单地“加一把 Redis 锁”就万事大吉。当前代码没有这些增强，不要虚报。

### 5.3 逻辑过期代码存在，但不是默认详情入口

`CacheClient.queryWithLogicalExpire()` 保留了逻辑过期与异步重建实现：逻辑时间到期后，一条请求获得互斥锁并提交线程池重建，其他请求先返回旧数据。这是面向**缓存击穿**的思路。

不过当前 `queryById()` 不调用它，不能说“项目当前商铺详情使用逻辑过期防击穿”。并且该辅助实现的锁值固定为 `"1"`，解锁时直接 `delete`；若未来真正启用，必须改成“唯一持有者值 + Lua 比较删除”，或使用成熟锁组件，否则锁过期后可能误删后来者的锁。

### 5.4 分类 List 与附近商铺 GEO

- 分类列表：`shopType:typeList` 采用 Redis List，未命中查询 MySQL 后整体写入。类型变更时需要明确失效策略；当前重点是读缓存，后台维护链路需自行补齐。
- 附近商铺：每个类型使用一个 `shop:geo:{typeId}` GEO 键。首次没有该键时，代码把同类型商铺坐标载入 Redis，再用半径 5km 查询；Redis 返回按距离排序的 ID 与距离，服务层手动分页、按 ID 批量回查，再按 Redis 返回顺序重排。

问“为什么不直接用 MySQL 经纬度 SQL？”：Redis GEO 的地理索引和按距离排序适合高频邻近检索；但数据更新、首次预热、地理精度、数据规模和故障回退都需要设计。当前实现首次加载时可能有并发重复装载，且没有看到商铺坐标更新后的 GEO 同步逻辑，应作为二开项。

---

## 6. 内容、点赞、关注与 Feed

### 6.1 发布博客与 XSS 边界

博客发布入口对标题和内容调用 `HtmlUtil.escape` 后再保存，目的是把 HTML 特殊字符转义，降低存储型 XSS 风险。它是输入处理的一层，前端渲染仍应避免不安全 HTML 注入；如将来支持富文本，应使用白名单净化器而不是简单关闭转义。

热门博客按 `liked` 倒序查询，随后逐条查询作者并回填。这个实现易读，但数据量变大时可能产生 N+1 查询；可优化为批量查询作者、联表查询或 DTO 投影。

### 6.2 点赞：ZSet 存成员关系，MySQL 存展示计数

```text
POST /blog/like/{id}
  → 获取 lock:blog:like:{blogId}:{userId}
  → ZSET blog:liked:{blogId} 判断 userId 是否已存在
  → 点赞：DB liked + 1，再 ZADD(userId, timestamp)
  → 取消：DB liked - 1，再 ZREM(userId)
  → Redis 操作失败时，尽力把 DB 计数反向补偿
```

ZSet 的 member 是用户 ID，score 是点赞时间。因此它同时支持“一人是否点过赞”、按时间取点赞用户。这里的 Redis 锁按“博客 + 用户”粒度，不会把所有人给同一博客点赞串行化。

边界也要主动说：DB 和 Redis 不是同一事务，补偿是尽力而为，不是严格原子一致。崩溃、网络中断、补偿失败都可能使 `liked` 计数与 ZSet 关系短暂或长期不一致。可通过 Outbox 事件、异步对账任务、以一种存储为权威并重建另一种来增强。

`queryBlogLikes()` 使用 ZSet 的 `range(key, 0, 4)`，即按 score **升序**取得最早的五个点赞用户，而不是“最近五个”。若产品需要最近点赞者，应使用逆序范围查询并明确排序语义。

### 6.3 关注：数据库为真，Redis 为加速

关注/取关先写 `tb_follow`，再同步 `follows:{userId}` Set；共同关注对两个 Set 做交集，再回查用户 DTO。数据库联合唯一索引能阻止重复关注，代码也把重复键异常按幂等结果处理。

这是一个常见思路：关键关系依赖 MySQL 可追溯与约束，Redis 提供快速集合运算。缓存丢失时应能由数据库重建；如果 Redis 与 MySQL 写入之间失败，当前代码没有可靠消息或重试队列，需有补偿/重建方案。

### 6.4 Feed：推模式收件箱 + ZSet 滚动分页

```text
发布者发博客
  → 查询其粉丝
  → 对每个粉丝执行 ZADD feed:{followerId} (blogId, publishTime)

粉丝查询 Feed
  → ZREVRANGEBYSCORE WITHSCORES (max=lastId, offset, limit=2)
  → 记录本页最小时间戳与同分 offset
  → 批量查 Blog，再按 ZSet 的 ID 顺序重排
```

它是典型的 Fan-out on Write（写扩散）：读很快、天然适合“关注的人动态”，但一个大 V 发一次内容会向所有粉丝写入，写放大明显。中小规模项目可行；大规模应按粉丝量采用推拉结合、按需拉取、异步队列与热点作者分层。

滚动分页不使用传统 `pageNo`，因为新内容持续插入会导致普通分页重复或漏读。`score + offset` 处理同一毫秒多个元素的情况：下一页带着最小 score 和该 score 的已读数量继续查。

### 6.5 评论

新增评论在事务内保存 `tb_blog_comments`，再通过 `comments = COALESCE(comments, 0) + 1` 原子递增博客评论数；计数更新失败会抛异常，使事务回滚。评论列表目前按 `create_time` 倒序直接查询。若二开加入用户展示、回复树、审核和高频点赞，要重新设计 DTO、索引、分页和计数一致性。

---

## 7. 秒杀：最容易被追问的并发链路

### 7.1 当前下单路径

```text
POST /voucher-order/seckill/{voucherId}
  → 查询 tb_seckill_voucher，判断开始/结束时间、初始库存
  → 获取 lock:order:{userId}（Redis，1200 秒 TTL）
  → 通过 AopContext.currentProxy() 调用事务方法
  → 查询该用户是否已有同券订单
  → UPDATE tb_seckill_voucher
      SET stock = stock - 1
      WHERE voucher_id = ? AND stock > 0
  → RedisIdWorker 生成订单 ID
  → INSERT tb_voucher_order
  → 提交/回滚；finally 中 Lua 校验后释放 Redis 锁
```

### 7.2 这到底是乐观锁还是悲观锁？

它是混合控制，准确答案如下：

| 要解决的问题 | 当前做法 | 准确说法 |
| --- | --- | --- |
| 同一用户并发点多次下单 | `SimpleRedisLock`，先获取锁再继续 | **悲观式分布式锁**：假设会冲突，先互斥。 |
| 多用户抢最后库存 | `UPDATE ... WHERE stock > 0`，根据影响行数判断 | **乐观式条件更新**：不先锁住读到的库存，提交时用条件竞争；它不是 `@Version` 版本号锁。 |
| 扣库存与创建订单同成同败 | `@Transactional` | **事务边界，不是锁**。 |
| 多实例生成订单 ID | 时间戳高位 + Redis 每日 INCR | 分布式 ID，不负责库存或幂等。 |

“乐观锁是不是都是条件判断？”可以这样回答：本质是“更新时验证前提仍成立，失败就重试/失败返回”。版本号 `WHERE version = ?`、库存条件 `WHERE stock > 0`、状态机条件 `WHERE status = 'PENDING'` 都是常见实现；但并非只要有 `if` 就是乐观锁，关键在于**条件必须在数据库/Redis 等共享原子操作中被校验**。普通 Java `if (stock > 0)` 后再 update，在并发下并不可靠。

### 7.3 为什么 `AopContext.currentProxy()` 必不可少

`createVoucherOrder()` 标有 `@Transactional`。如果同一个对象内直接 `this.createVoucherOrder()`，调用绕过 Spring 代理，事务注解通常不会生效。启动类开启 `@EnableAspectJAutoProxy(exposeProxy = true)`，外层通过 `AopContext.currentProxy()` 取得代理对象调用，从而进入事务拦截器。

更干净的二开方式是把事务方法拆到独立 Service，依赖注入后调用；可读性和测试性通常优于依赖 `AopContext`。

### 7.4 现有保障与真实缺口

| 已保障 | 仍有缺口 | 建议 |
| --- | --- | --- |
| 库存不会因并发扣成负数：最终条件更新以影响行数裁决。 | `tb_voucher_order` 缺少 `(user_id, voucher_id)` 唯一索引。Redis 锁失效、过期、绕过该入口时，数据库无法最终拒绝重复单。 | 添加唯一索引，并将 duplicate key 转为“已下单”的幂等响应。 |
| 锁值含应用 UUID + 线程 ID；Lua 比较后删除，避免误删别人的锁。 | 锁 TTL 固定为 1200 秒，没有续租。业务超过 TTL 时可能并发进入；过长又会让异常请求等待更久。 | 先测量事务耗时并收紧 TTL；高可靠场景评估 Redisson watchdog/可重入锁。 |
| 扣库存与建订单处于一个数据库事务。 | 下单前的时间和库存查询只是快速反馈，不是最终判定。 | 把最终规则放入原子 SQL/数据库约束；必要时锁定/校验时间窗。 |
| Redis ID 生成可减少单库自增依赖。 | Redis 不可用时不能生成新订单 ID。 | 根据业务目标做降级、号段服务或数据库序列备选。 |

> 不要把“加 Redis 锁”说成数据库唯一性的替代品。可用性组件会故障，关键业务规则要有数据库约束或可证明的最终一致性机制。

---

## 8. Redis 设计总表：数据结构为什么这样选

| Key 模式 | 类型 | 读写位置 | 为什么选它 | 恢复/一致性注意点 |
| --- | --- | --- | --- | --- |
| `login:code:{phone}` | String | `UserServiceImpl` | 短文本 + TTL | 验证码自然过期；不要记录明文到生产日志。 |
| `login:code:limit:{phone}` | String | `UserServiceImpl` | `SETNX` 做短窗口限流 | 仅是单维限流；还可叠加 IP/设备维度。 |
| `login:token:{token}` | Hash | 登录服务、拦截器 | 字段型 DTO，便于反序列化和续期 | Redis 宕机将影响会话；需要高可用/降级策略。 |
| `cache:shop:{id}` | String(JSON/空值) | `CacheClient`、商铺服务 | 对象缓存和空值占位 | 写后失效，需处理回填旧值窗口。 |
| `shopType:typeList` | List | 分类服务 | 有序分类列表 | 分类后台变更时要主动失效/重建。 |
| `shop:geo:{typeId}` | GEO | 商铺服务 | 地理距离检索和排序 | 坐标更新、首次预热、分片规模要设计。 |
| `blog:liked:{id}` | ZSet | 博客服务 | member 是用户，score 是点赞时间 | 与 MySQL 计数双写，需对账策略。 |
| `feed:{userId}` | ZSet | 博客服务 | member 是博客，score 是发布时间 | 大 V 写扩散，需推拉结合。 |
| `follows:{userId}` | Set | 关注服务 | 交集求共同关注快 | MySQL 是真源，Set 可重建。 |
| `sign:{userId}:yyyyMM` | Bitmap | 用户服务 | 每日布尔签到占用小 | 不保存签到明细。 |
| `lock:order:{userId}` | String | 秒杀服务 | SETNX + TTL + Lua 解锁 | 需评估超时、续租、异常处理。 |
| `icr:order:yyyy:MM:dd` | String | `RedisIdWorker` | 每日递增序列 | Redis 可用性决定发号能力。 |

`RedisConstants.SECKILL_STOCK_KEY` 虽然存在，但当前 Java 秒杀服务没有以它进行库存预扣或扣减；因此不能把本项目描述为“Redis 预扣库存 + 异步下单”架构。

---

## 9. 安全、部署和测试：什么能说，什么不能说

### 已有措施

- 非公开接口通过登录拦截器保护；请求结束清理用户上下文。
- 博客文本做 HTML 转义。
- 图片上传限制扩展名和 5MB 大小，保存为 UUID + 两级目录。
- 图片删除尝试通过 canonical path 校验防路径穿越，且支持 `DELETE`。
- 全局异常处理记录服务端异常，对客户端返回通用失败信息。
- Nginx 配置了基础安全响应头和 `/api` 反向代理。

### 不能过度宣称的事项

| 主题 | 当前事实 | 面试时的正确说法 / 二开动作 |
| --- | --- | --- |
| 短信 | 代码中测试模式返回验证码；生产分支目前只记录“已发送”日志，没有接入实际短信供应商调用。 | “接口和限流逻辑已预留，真实短信通道尚待接入和回执、重试设计。” |
| HTTPS | Nginx HTTPS 示例为注释，未看到真实证书和跳转验证。 | “具备反代骨架，HTTPS 需要证书、TLS 配置和验收后才算启用。” |
| 配置密钥 | `application.yaml`/README 存在不应入库的连接信息或默认凭据。 | 立即移除并轮换暴露凭据，改环境变量、密钥管理；文档和提交记录都不要再复制。 |
| 文件删除 | 接口同时兼容 `GET` 和 `DELETE`。 | 删除状态的操作应只保留 `DELETE`，增加 CSRF、权限/归属校验；路径判断改用 `Path.startsWith` 等边界安全做法。 |
| 上传内容 | 仅校验文件名扩展名。 | 还应校验 MIME/文件魔数、重编码图片、隔离存储和下载响应头。 |
| 登录密码 | 使用加盐 MD5。 | 迁移 BCrypt/Argon2，增加登录审计、异常登录与令牌管理。 |
| 测试 | 有 Spring 上下文、功能和安全相关 JUnit 测试。 | “具备基础自动化测试；真实 MySQL/Redis 集成、并发压测、恢复演练和发布验收仍需补足。” |

---

## 10. 面试官连续追问（压力训练）

练习方法：先用 30 秒回答“结论 + 当前代码证据”，再用 60 秒说明边界；答不上来时回到本手册的路径读代码，不要编造。

### A. 架构与认证

1. **为什么有两个拦截器？**
   - 刷新拦截器负责从 Redis 恢复用户并续期，所有请求都可执行；登录拦截器负责对受保护路径返回 401。拆开可让公开接口也携带登录态而不强制登录。
2. **为什么 `UserHolder` 要在两个拦截器里都清理？**
   - 关键是请求结束必须清理。多个位置清理属于防御性处理；必须避免线程池复用导致串用户。
3. **Token 为什么存在 Redis，而不是直接放数据库？**
   - 会话高频读写适合 Redis TTL；数据库保存用户事实数据。Redis 删除即可登出，但 Redis 可用性需要保障。
4. **验证码发送如何防刷？**
   - 当前按手机号 `SETNX + 60s TTL`；不足以对抗分布式攻击，应加 IP、设备、图形验证、审计和供应商限额。
5. **密码安全吗？**
   - 有随机盐，但 MD5 不够强；明确提出 BCrypt/Argon2 的兼容迁移方案。

### B. 缓存与 Redis

6. **缓存穿透、击穿、雪崩分别是什么？**
   - 穿透：反复查不存在数据，当前用空值缓存；击穿：热点 key 到期瞬间回源，代码有逻辑过期辅助实现但默认未启用；雪崩：大量 key 同时失效或 Redis 故障，应做 TTL 打散、限流、降级、高可用。
7. **逻辑过期为什么能缓解击穿？**
   - 缓存本身不因 TTL 消失；逻辑过期后只有一个线程重建，其他请求先返回旧值，以一致性换可用性。
8. **逻辑过期方法为什么不能直接说已经上线？**
   - 当前 `queryById()` 走 `queryWithPassThrough()`；存在某个工具方法不代表主链路调用它。
9. **GEO 查询后为什么还要查数据库并重排？**
   - GEO 给 ID 和距离；完整商铺数据在 MySQL。`IN (...)` 返回顺序不保证与 GEO 一致，所以要按 Redis ID 顺序重排。
10. **Feed 为什么使用 ZSet？**
    - 时间戳做 score，支持倒序范围查询、带 score 的滚动分页；List 不擅长按分数范围与同分 offset。
11. **点赞计数为何既在 DB 又在 Redis？**
    - Redis ZSet 保存“谁在何时点赞”，DB 保存展示/排序计数；双写不是原子操作，需要补偿、对账或事件化改造。

### C. MySQL、事务与并发

12. **秒杀为什么不会超卖？**
    - 最终 `UPDATE ... SET stock=stock-1 WHERE stock>0` 是单条原子条件更新；影响行数为 0 则库存已被别人抢完。前置查询只是提示。
13. **这是乐观锁吗？为什么？**
    - 是乐观式条件更新：不预先锁住读取结果，而在更新时验证前提。它不使用版本号，因此不要叫 `@Version` 乐观锁。
14. **Redis 锁是乐观锁还是悲观锁？**
    - 悲观式：先拿互斥锁再进入临界区，假定同一用户会并发冲突。
15. **事务能解决一人一单吗？**
    - 不能单独保证。事务只定义原子性边界；最终还应有 `(user_id, voucher_id)` 唯一索引处理锁失效、绕过和多实例异常。
16. **为什么要通过代理调用事务方法？**
    - Spring 事务基于代理；同类 `this` 调用绕过代理。当前启用 `exposeProxy` 并用 `AopContext.currentProxy()`。
17. **Redis 锁的解锁为什么要 Lua？**
    - “GET 比较持有者”与“DEL”必须原子；否则锁过期重入后，旧线程可能删掉新线程的锁。
18. **锁 TTL 为什么仍有风险？**
    - 业务超过 TTL 时锁可能提前失效；固定很长又降低可用性。测量耗时、续租或成熟锁方案才是完整治理。
19. **数据库隔离级别/MVCC 是否能取代锁？**
    - 不能。MVCC 主要服务一致性读；当前写竞争的最终保障是条件更新和约束。涉及当前读、写冲突时仍需数据库锁或原子条件。

### D. 二次开发设计判断

20. **你会先改什么？**
    - 先消除凭据泄露、短信假实现、弱密码摘要、订单唯一约束与 `GET` 删除；这些是正确性和安全底线。
21. **百万粉丝大 V 发帖怎么办？**
    - 不能同步逐个写收件箱；用 MQ 异步、推拉混合、热点作者拉模式、批量/分片与限流，并定义最终一致性体验。
22. **如何让 Redis-MySQL 双写可靠？**
    - 不承诺单事务强一致；明确权威数据源，采用 Outbox/消息、重试幂等消费者和定期对账重建。
23. **如何验证这些优化真的有效？**
    - 先设指标：P95/P99 延迟、DB QPS、缓存命中率、库存/订单正确性、重复单数；再做可复现并发压测、故障注入和集成测试。
24. **如何避免只会背项目？**
    - 在本地断开 Redis、并发请求同一券、让缓存过期、插入同 score Feed，观察日志/数据库/Redis，再把现象和代码对应起来。

---

## 11. 二次开发优先级：先补底线，再加能力

### P0：上线前必须解决

1. **凭据与配置治理**：从仓库移除敏感默认值并轮换已有凭据；使用环境变量、配置中心或密钥管理，提供不含秘密的示例配置。
2. **认证改造**：接入真实短信服务（发送结果、失败重试、供应商限额、审计）；密码从加盐 MD5 平滑迁移至 BCrypt/Argon2。
3. **订单最终幂等**：给 `tb_voucher_order(user_id, voucher_id)` 添加唯一索引；服务捕获重复键并返回已下单，而不是 500。
4. **接口安全**：删除 `GET /upload/blog/delete` 兼容入口，校验资源归属；上传增加 MIME/魔数检查、对象存储隔离与访问控制。
5. **数据库迁移规范**：引入 Flyway/Liquibase 或至少维护可重复执行的迁移登记；先修复重复关注索引的部署风险。

### P1：性能、可靠性与产品能力

1. **缓存治理**：按数据类别决定空值缓存、逻辑过期或主动失效；统一锁实现，补充 TTL 抖动、缓存预热、命中率和重建耗时监控。
2. **秒杀演进**：若目标是高并发，再评估 Redis Lua 原子资格校验/库存预扣、Stream/MQ 异步建单、消费者幂等和库存对账；不要在当前小项目里盲目堆组件。
3. **Feed 演进**：粉丝规模分层；普通作者推送，大 V 拉取；异步投递并支持未读数、清理策略和重试。
4. **查询优化**：为热点查询做 `EXPLAIN`；消除热门博客作者回填的 N+1；增加评论分页、必要索引和 DTO 投影。
5. **商铺位置同步**：明确新增/更新/删除商铺时 MySQL、缓存和 GEO 的同步契约，补充重建任务。

### P2：可运营、可观测、可验证

1. 日志中加入请求 ID、用户/订单的脱敏标识和关键业务事件；禁止记录验证码、密码、Token、连接密钥。
2. 增加指标：缓存命中率、Redis 锁等待/超时、秒杀成功率、条件更新失败数、重复键数、Feed 投递积压。
3. 编写 Testcontainers 或独立测试环境的 MySQL/Redis 集成测试；增加同用户并发下单、最后库存竞争、缓存重建、Redis 短暂故障等场景。
4. 形成发布清单：数据库备份/迁移、健康检查、回滚、HTTPS、告警、容量和恢复演练。

---

## 12. 建议的源码阅读与实操顺序

1. **认证主线**：`MvcConfig` → 两个拦截器 → `UserHolder` → `UserServiceImpl`。自己画一次 Token 的创建、续期、登出、清理图。
2. **商铺主线**：`ShopController` → `ShopServiceImpl` → `CacheClient`。分别请求存在和不存在的商铺，观察普通缓存和空值缓存。
3. **社交主线**：`BlogServiceImpl`、`FollowServiceImpl`。把 ZSet 的 member、score、查询命令写在纸上，解释 Feed 的 `lastId + offset`。
4. **秒杀主线**：`VoucherOrderServiceImpl` → `SimpleRedisLock` → SQL 条件更新 → `RedisIdWorker`。用并发请求验证库存不为负，再检查重复订单风险。
5. **表与索引**：对上述每个查询在 MySQL 执行 `EXPLAIN`，确认索引、回表、排序和范围扫描，而不是只记表结构。
6. **开始二开**：从 P0 拆一个独立需求，先写验收条件和迁移脚本，再改 Service/接口/测试，最后验证 Redis 与 MySQL 的真实状态。

## 13. 面试前的最终自检

- 我能从 `/voucher-order/seckill/{id}` 讲到 Redis 锁、代理事务、条件扣库存和订单落库，并区分它们各自的责任。
- 我能解释默认商铺详情实际走空值缓存，而逻辑过期只是保留的辅助方案。
- 我能说出至少三个当前未完成的生产化问题：订单联合唯一索引、真实短信、密码摘要/凭据治理、HTTPS/删除接口等。
- 我不会把 Redis 常量、工具方法或 README 里的目标当成已上线事实。
- 我能为每一项二开优化给出验收指标或测试场景，而不是只说“加缓存、加锁、上 MQ”。

做到这些，不代表面试不会有陌生问题；但面对这个项目的架构、数据、一致性、并发和安全追问时，你能基于代码作答，并且能把“已实现”和“下一步设计”清楚地区分开。
