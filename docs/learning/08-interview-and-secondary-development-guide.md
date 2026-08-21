# DishReview：面试题库与二次开发维护手册

> 用途：准备 Java 后端日常实习面试，并在后续二次开发时持续记录“代码事实、能力边界、改造结果和面试答案”。
>
> 当前核对基线：`main` 分支，提交 `5876354`，核对日期 `2026-08-18`。
>
> 事实纪律：先说当前代码，再说保障目标，最后说明缺口与改进。README、注释、常量或保留的辅助方法不等于当前请求链路已经启用。

## 0. 如何使用和维护本文

本文不是一次性背诵稿，而是项目的长期面试工作台。每次二次开发后，至少检查以下内容：

1. 修改了哪个真实调用链，对应题目的答案是否需要更新。
2. 原来的“问题/改进”是否已经变成“当前实现”。
3. 数据库索引、事务边界、Redis Key 和 TTL 是否变化。
4. 是否新增了单元测试、集成测试或真实环境验证证据。
5. 是否有新的失败场景、性能数据或安全边界可以诚实写入简历。

本文使用三类标签：

- **[当前实现]**：已从当前源码或 SQL 核对。
- **[当前缺口]**：当前代码尚未可靠解决，面试时不能说成已有能力。
- **[二开方向]**：建议方案，完成并验证后才能改成“当前实现”。

建议背诵顺序：先掌握第 1～20、33～48 题，再补充其他题。不要逐字背诵，优先记住“调用链 + 设计目的 + 边界”。

---

## 1. 项目事实速查

### 1.1 两分钟项目介绍

DishReview 是一个基于 Java 8、Spring Boot、MyBatis-Plus、MySQL、Redis 和 RabbitMQ 实现的餐饮点评平台。业务包括用户登录、商铺查询、探店笔记、点赞关注、Feed 流、附近商铺、签到和优惠券秒杀。

Redis 在项目中不仅承担缓存，还用于验证码、登录会话、全局 ID、点赞 ZSet、关注 Set、Feed ZSet、GEO、BitMap 和秒杀预扣。项目最值得讲的是缓存和秒杀两条链路：商铺详情使用 Cache Aside 和空值缓存；秒杀使用 Redis Lua、事件表、RabbitMQ 消费者、MySQL 条件扣库存和唯一索引分层保证正确性。

这是学习型项目，初版参考过教程。面试时不虚构线上 QPS、用户规模或独立架构经历，应重点说明自己真正读懂、修改和验证过的部分。

### 1.2 整体架构

```text
浏览器（Vue 2 静态页面）
        │ /api/*
        ▼
Nginx（静态资源 + 反向代理）
        ▼
Spring Boot
  RefreshTokenInterceptor → LoginInterceptor
        → Controller → Service → Mapper
                    │          │
                    └ Redis    └ MySQL
```

### 1.3 五条必须背熟的调用链

```text
登录：
手机号 → 验证码 Redis → 查询/创建用户 → 生成 Token
→ UserDTO 写入 Redis Hash → 拦截器恢复当前用户

商铺缓存：
查 Redis → 命中返回 → 未命中查 MySQL → 写入缓存
                         └ 不存在则缓存空值

附近商铺：
坐标 + 类型 → Redis GEO 筛选和排序 → 商铺 ID
→ MySQL 批量查询 → 按 GEO 顺序组装

Feed：
作者发布笔记 → 查询粉丝 → 写入粉丝 Feed ZSet
用户查看 Feed → 时间戳滚动分页 → 查询笔记详情

秒杀（可靠性闭环版）：
校验时间 → 生成 eventId/orderId（Lua 前）→ Redis Lua 原子预扣 + 写预留账本
→ 尽力写 PENDING 事件后返回受理 → Outbox 任务 CAS 抢占并发布 RabbitMQ
→ Confirm/Return 按 attemptId 落发布尝试证据 → 失败决策服务统一裁决
→ 消费者事务内 CAS 锁定状态、条件扣库存、写订单、标记 CONSUMED → 自动 ACK
→ 对账任务双向核对 Redis 预留与 MySQL 事件
```

### 1.4 Redis 使用总表

| 场景 | 数据结构 | 选择原因 |
| --- | --- | --- |
| 验证码、限流、计数器 | String | 简单值、`SET NX`、`INCR` |
| 登录用户 | Hash | 保存精简 `UserDTO` 字段 |
| 商铺详情缓存 | String | JSON 序列化对象 |
| 分布式锁 | String | `SET NX EX` 实现互斥 |
| 点赞 | ZSet | 判断成员并保留点赞时间顺序 |
| 关注 | Set | 去重和共同关注交集 |
| Feed 收件箱 | ZSet | 按发布时间排序和范围查询 |
| 附近商铺 | GEO | 经纬度半径查询和距离排序 |
| 签到 | BitMap | 每天只占一个 bit |

### 1.5 模块关系与数据库约束

```text
User ──< Blog ──< BlogComments
  │       │
  └──< Follow >── User

ShopType ──< Shop ──< Voucher ── SeckillVoucher
User ──< VoucherOrder >── Voucher
```

| 表 | 已有约束/索引 | 面试时必须说明的影响 |
| --- | --- | --- |
| `tb_user` | 手机号唯一索引 | 自动注册时可避免相同手机号最终重复落库，但业务仍应处理并发插入异常。 |
| `tb_follow` | `(user_id, follow_user_id)` 唯一索引；`follow_user_id` 索引 | 数据库可最终拒绝重复关注，按被关注者查询粉丝也有索引支撑。 |
| `tb_seckill_voucher` | `voucher_id` 主键 | 一张券只对应一条秒杀库存记录。 |
| `tb_shop` | `type_id` 索引 | 按商铺分类查询具备基础索引。 |
| `tb_voucher_order` | 订单主键；`(user_id, voucher_id)` 联合唯一索引 | Redis 或消息链路失效时，数据库仍可最终拒绝重复订单。 |
| `tb_seckill_order_event` | `(status, next_retry_time, lease_until)` 任务扫描索引；`row_version` CAS；租约列 | 事件表即 Outbox；状态机 10 态约束迁移，迟到回调不能覆盖终态。 |
| `tb_seckill_publish_attempt` | `uk(event_id, attempt_no)` | 每次实际发送一行证据，Confirm/Return/异常分开记录。 |
| `tb_seckill_failure_case` | `idempotency_key` 唯一索引 | DLQ、回滚异常、对账冲突的持久化事实，防重复落库。 |

初始化 SQL 已包含关注索引和订单唯一索引，`db/migration/` 也保留了对应增量脚本。项目没有自动迁移框架，执行前必须检查目标环境，后续可引入 Flyway/Liquibase。

---

## 2. P0 高频题：项目、架构与真实性

### 1. 请介绍一下这个项目

**参考答案：**

DishReview 是一个基于 Spring Boot、MyBatis-Plus、MySQL 和 Redis 的餐饮点评平台，包含登录、商铺、笔记、点赞关注、Feed、签到和优惠券秒杀。Redis 除了缓存，还用于登录状态、分布式锁、ZSet、Set、GEO、BitMap 和全局 ID。项目中我重点理解了商铺缓存和秒杀并发控制，也分析了当前实现的生产化缺口。

### 2. 项目的整体架构是什么？

**参考答案：**

项目采用单体分层架构。Nginx 托管静态页面并反向代理到 Spring Boot；后端按 Controller、Service、Mapper 分层；MySQL 保存持久业务数据，Redis 负责缓存、会话和高性能数据结构场景。受保护请求还会先经过 Token 刷新和登录校验两个拦截器。

### 3. 为什么同时使用 MySQL 和 Redis？

**参考答案：**

MySQL 是用户、商铺、笔记和订单等数据的持久化事实源，提供事务和数据库约束。Redis 负责高频读取、临时状态及 Set、ZSet、GEO、BitMap 等场景。Redis 用来提高性能，但订单和库存的最终正确性仍应由数据库事务和约束兜底。

### 4. Redis 在项目中有哪些应用？

**参考答案：**

验证码和限流使用 String，登录用户使用 Hash，商铺详情使用 String 缓存，分布式锁使用 `SET NX EX`，订单 ID 使用 `INCR`，点赞和 Feed 使用 ZSet，关注使用 Set，附近商铺使用 GEO，签到使用 BitMap。

### 5. 项目中最值得讲的技术点是什么？

**参考答案：**

我会选择秒杀可靠性闭环。入口用 Redis Lua 原子预扣并写六 Key 预留账本（含 orderId 反向索引，全部同 `{voucherId}` 槽）；事件表作为 Outbox 是唯一生产者入口（CAS + 租约）；Confirm/Return 按 attemptId 落发布尝试证据，统一失败决策服务裁决是否回滚；消费端异常分三类处理，重试耗尽先落失败记录再进 DLQ；持久化回滚任务按 eventId 执行回滚 Lua；定时任务双层对账（7 天快速扫描 + 每小时全量分页兜底）。真实 RabbitMQ 故障演练和并发压测仍未完成，这是必须主动说明的边界。

### 6. 如何诚实描述这是一个教程项目？

**参考答案：**

项目初版参考了教程，但我重新阅读了登录、缓存、Feed 和秒杀的真实调用链，并分析、修改和验证了部分安全与业务问题。简历上我只写自己真正掌握和完成的内容，不会把它包装成独立设计或支撑百万并发的生产系统。

---

## 3. 登录与鉴权

### 7. 验证码登录的完整流程是什么？

**参考答案：**

先校验手机号格式，再使用 Redis `SET NX` 限制同一手机号 60 秒内重复发送，生成六位验证码并保存两分钟。登录时从 Redis 读取并校验验证码，错误最多尝试五次；成功后删除验证码和计数。如果用户不存在就在 MySQL 创建用户，最后生成 Token，把精简 `UserDTO` 以 Hash 形式保存到 Redis。

### 8. 为什么登录状态存 Redis，而不是只用 HttpSession？

**参考答案：**

默认 HttpSession 存在单个应用实例内存中，多实例部署会遇到 Session 共享问题。把 Token 对应的登录状态保存到 Redis 后，不同实例可以读取同一份会话，更利于横向扩容。

### 9. 为什么 Redis 中存 UserDTO，而不是完整 User？

**参考答案：**

业务请求通常只需要用户 ID、昵称和头像。使用 UserDTO 可以减少缓存占用，同时避免密码、手机号等敏感字段进入登录上下文或被接口意外返回。

### 10. 两个登录拦截器分别做什么？

**参考答案：**

`RefreshTokenInterceptor` 拦截所有请求，尝试根据请求头 Token 从 Redis 恢复用户，写入 ThreadLocal，并刷新 TTL。`LoginInterceptor` 只判断当前接口是否必须登录以及 ThreadLocal 中是否有用户。刷新拦截器顺序为 0，登录拦截器顺序为 1，所以先恢复登录态，再判断是否放行。

### 11. 为什么使用 ThreadLocal？

**参考答案：**

同一个请求通常由同一线程处理。拦截器把当前用户保存到 ThreadLocal 后，Controller 和 Service 可以直接获取用户，不需要层层传参。Tomcat 会复用线程，因此请求完成后必须 `remove()`，否则可能发生内存泄漏或用户串号。

### 12. Token 为什么采用滑动过期？

**参考答案：**

Token 当前 TTL 是 30 分钟。活跃用户每次请求都会由刷新拦截器续期，停止访问超过 30 分钟后自动失效。这兼顾了使用体验和 Token 泄露后的风险窗口。

### 13. 当前短信验证码真的发送了吗？

**参考答案：**

**[当前缺口]** 没有完整接入。测试模式会直接返回验证码，所谓生产模式当前只记录发送日志，并未调用真实短信服务。生产化需要接入短信供应商，并补充模板、签名、失败重试、审计、限流和成本控制。

### 14. 当前密码存储安全吗？

**参考答案：**

**[当前实现]** 使用随机盐加 MD5，比明文或无盐 MD5 好，但仍不适合生产，因为 MD5 计算快，容易被高速暴力破解。**[二开方向]** 应迁移到 BCrypt、Argon2 或 PBKDF2，并设计旧密码逐步升级策略。

---

## 4. 缓存

### 15. 商铺详情使用了什么缓存模式？

**参考答案：**

当前默认入口使用 Cache Aside。先查 Redis，命中直接返回；未命中则查询 MySQL，再写入 Redis。如果数据库也不存在，就缓存空字符串，防止相同不存在 ID 不断访问数据库。

### 16. 什么是缓存穿透？项目怎么解决？

**参考答案：**

缓存穿透是持续查询数据库中不存在的数据，Redis 无法命中，每次请求都落到数据库。当前项目使用空值缓存缓解。生产环境还可以结合参数校验、布隆过滤器和恶意请求限流。

### 17. 什么是缓存击穿？

**参考答案：**

缓存击穿是热点 Key 失效时，大量并发请求同时进入数据库。常见方案是互斥锁重建或逻辑过期。项目保留了这两类实现，但当前商铺详情默认走普通旁路缓存，不走逻辑过期。

### 18. 什么是缓存雪崩？

**参考答案：**

缓存雪崩是大量 Key 同时失效或 Redis 整体不可用，导致请求集中进入数据库。可以使用随机 TTL、缓存预热、多级缓存、Redis 高可用、限流和降级等方案缓解。

### 19. 逻辑过期方案如何工作？

**参考答案：**

缓存值中同时保存业务数据和逻辑过期时间。未过期直接返回；过期后尝试获得互斥锁，成功的线程异步重建缓存，当前请求先返回旧值。优点是热点 Key 不会物理失效，缺点是短时间读到旧数据，而且需要提前预热。

### 20. 更新商铺后如何处理缓存？

**参考答案：**

当前实现先更新数据库，再删除 Redis 缓存，后续请求未命中时重新加载。删除缓存比直接更新更简单，也能避免字段遗漏。**[当前缺口]** 删除发生在数据库事务提交前，仍存在其他线程回填旧值的窗口；可以改为事务提交后删除，并结合消息通知、延迟双删或 Binlog 订阅。

### 21. 为什么不采用“先删缓存，再更新数据库”？

**参考答案：**

先删除后更新时，两个操作之间的查询可能读取旧数据库数据并重新写入缓存。数据库更新完成后，缓存仍是旧值。因此通常优先采用先更新数据库再删除缓存。

### 22. 当前缓存实现还有哪些问题？

**参考答案：**

通用 `queryWithPassThrough` 对正常数据和空值使用同一个传入 TTL，当前商铺入口传入 30 分钟，因此空值也可能缓存 30 分钟。缓存与数据库没有强一致事务；逻辑过期依赖预热；重建线程池、锁和异常处理也缺少完整监控与治理。

---

## 5. Redis 数据结构与业务

### 23. 附近商铺为什么使用 Redis GEO？

**参考答案：**

GEO 支持经纬度存储、半径查询、距离计算和距离排序。项目按照商铺类型维护 GEO Key，member 是商铺 ID。查询五公里内商铺后，再回查 MySQL 获取完整信息。

### 24. GEO 查询为什么还要回查 MySQL？

**参考答案：**

GEO 中只保存 ID 和坐标，不保存名称、图片和评分等完整业务字段。Redis 负责空间过滤和排序，MySQL 负责完整业务数据。`listByIds` 不保证输入顺序，所以代码按 GEO 结果重新组装。

### 25. 用户签到为什么使用 BitMap？

**参考答案：**

每天的签到状态只有 0 和 1，可以用一个 bit 表示。项目按“用户 + 月份”建 Key，第 N 天对应第 N-1 位。相比每天保存独立记录，BitMap 空间占用更小，也方便一次读取整月状态。

### 26. 连续签到天数如何计算？

**参考答案：**

使用 BitField 读取本月第一天到今天的签到位，得到一个无符号整数。从最低位，也就是今天开始判断：为 1 就累计并无符号右移，直到遇到 0，得到截至今天的连续签到天数。

### 27. 点赞为什么使用 ZSet，而不是 Set？

**参考答案：**

Set 可以判断是否点赞，但无法保存顺序。ZSet 的 member 保存用户 ID，score 保存点赞时间戳，既能实现一人一赞，又能按点赞时间查询用户。

### 28. 点赞数为什么还保存在 MySQL？

**参考答案：**

ZSet 保存点赞成员和顺序，笔记表的 `liked` 字段用于热门排序。点赞时会同时更新 MySQL 计数和 Redis 成员关系。**[当前缺口]** 这是跨存储双写，当前只有尽力补偿，不是强一致；生产中需要消息队列、对账或重新设计事实源。

### 29. 共同关注如何实现？

**参考答案：**

每个用户关注的人保存在 Redis Set。对当前用户和目标用户的两个 Set 求交集，得到共同关注用户 ID，再批量查询 MySQL 转成 UserDTO。

### 30. Feed 流如何实现？

**参考答案：**

当前采用推模式。作者发布笔记后查询全部粉丝，把笔记 ID 写入每个粉丝的 Feed ZSet，score 为发布时间戳。粉丝读取时按 score 倒序滚动分页，再批量查询笔记详情。

### 31. Feed 为什么使用滚动分页？

**参考答案：**

Feed 会持续新增数据，普通页码分页可能因数据插入发生重复或遗漏。滚动分页使用上一页最小时间戳作为下一页上限；多个元素 score 相同时，再用 offset 记录已经读取的同分值元素数量。

### 32. 推模式和拉模式有什么区别？

**参考答案：**

推模式发布时写入粉丝收件箱，读快但大 V 会产生写放大；拉模式读取时聚合关注人的内容，写简单但读放大。当前项目使用纯推模式，生产中可对普通用户推、对大 V 拉，形成推拉结合。

### 补充：商铺分类缓存和评论链路

- 商铺分类列表使用 Redis List。缓存未命中时查询 MySQL 并整体写入；**[当前缺口]** 分类后台变更时还缺少明确的主动失效或重建策略。
- 新增评论在事务中写入 `tb_blog_comments`，再使用 `comments = COALESCE(comments, 0) + 1` 原子递增笔记评论数；计数更新失败会抛出异常并回滚。
- 评论列表当前按创建时间倒序直接查询。增加回复树、审核、用户展示或高频点赞后，需要重新设计 DTO、索引、分页和计数一致性。
- GEO 首次缺少对应 Key 时会加载同类型商铺坐标；**[当前缺口]** 并发首次加载可能重复执行，也没有完整的商铺坐标更新后 GEO 同步链路。

---

## 6. 秒杀与并发

### 33. 秒杀下单的完整调用链是什么？

**参考答案：**

Controller 调用 `seckillVoucher`，校验登录和活动时间后**先生成 `orderId/eventId`**，再执行 Lua 原子预扣（同时写库存、用户集合和预留账本六个 Key），尽力写入 `tb_seckill_order_event(PENDING)` 后立即返回受理结果。Outbox 发布任务扫描到期事件，CAS + 租约抢占后同事务记录发布尝试并发送 RabbitMQ；消费者事务内 CAS 锁定事件状态、检查一人一单、条件扣 MySQL 库存、保存订单并标记 `CONSUMED`；对账任务最终清理 Redis 预留账本（保留一人一单集合）。

### 34. 项目用了乐观锁还是悲观锁？

**参考答案：**

当前是分层控制：Redis Lua 原子判断库存和用户集合，消费者用 MySQL `stock > 0` 条件更新兜底，数据库联合唯一索引保证一人一单；条件更新不是 `@Version` 版本号锁。

### 35. `stock > 0` 为什么能防止超卖？

**参考答案：**

执行 `UPDATE ... SET stock = stock - 1 WHERE voucher_id = ? AND stock > 0` 时，MySQL 会对目标记录进行并发控制。库存变成 0 后，后续更新不再满足条件，影响行数为 0，业务据此返回库存不足，不会继续扣成负数。

### 36. 前面先查一次库存能防止超卖吗？

**参考答案：**

不能。查询结果在下一瞬间就可能变化，它只是提前快速失败。真正防止超卖的是事务中的条件更新及其影响行数判断。

### 37. Redis 锁为什么按用户 ID 加锁？

**参考答案：**

业务目标是一人一单，只需让同一用户的并发请求串行化。不同用户仍可并发下单。如果对整个优惠券使用一把全局锁，所有用户都会串行，吞吐量明显下降。

### 38. 分布式锁如何获取？

**参考答案：**

使用 Redis `SET key value NX EX timeout` 的语义。`NX` 保证 Key 不存在时才能写入，过期时间防止持锁服务宕机造成死锁。锁值由应用级 UUID 和线程 ID组成，用于识别锁持有者。

### 39. 为什么释放锁不能直接 `DEL`？

**参考答案：**

线程 A 的锁可能已过期，线程 B 随后获得同名锁。如果 A 直接删除，就会误删 B 的锁。因此必须先比较锁值是否属于当前线程，再执行删除。

### 40. 为什么解锁使用 Lua？

**参考答案：**

分别执行 GET 和 DEL 时，两条命令之间可能发生锁过期或线程切换。Lua 把“比较持有者”和“删除锁”作为 Redis 内的原子操作，避免检查后锁所有权变化。

### 41. 当前分布式锁有哪些不足？

**参考答案：**

订单锁固定为 1200 秒，没有看门狗续期，也没有完整的可重入和故障处理能力。租约过长会让异常锁长期阻塞，业务执行超过租约又可能提前释放。生产中可以使用 Redisson，并配置合理的等待、租约、续期和监控策略。

### 42. `@Transactional` 为什么可能失效？

**参考答案：**

Spring 事务通常通过 AOP 代理实现，同一个对象内部用 `this` 调用事务方法不会经过代理。旧版同步秒杀曾使用 `AopContext.currentProxy()` 绕过自调用问题；当前实现已把事务拆到独立的 `VoucherOrderHandler` Bean，由消费者跨 Bean 调用，事务代理可以正常生效。

### 43. `@Transactional` 是锁吗？

**参考答案：**

不是。它定义数据库事务边界，使扣库存和保存订单共同提交或回滚。具体 SQL 执行时数据库可能产生行锁，但不能把事务注解本身称为悲观锁或乐观锁。

### 44. 一人一单如何保证？

**参考答案：**

四层防线：Lua 在 Redis 用户集合中原子拦截重复预留；消费事务先 CAS 检查事件状态（CONSUMED 直接幂等返回）；消费者查询已有订单；数据库 `(user_id, voucher_id)` 联合唯一索引最终兜底，重复键只有在确认订单确实存在后才按幂等成功处理。

### 45. 为什么有 Redis 锁还需要数据库唯一索引？

**参考答案：**

Redis 锁可能因过期、故障、实现缺陷或运维操作失效。数据库唯一索引位于最终写入层，能够真正阻止重复订单落库。Redis 锁减少冲突并提高性能，唯一索引负责数据底线。

### 46. RedisIdWorker 如何生成全局 ID？

**参考答案：**

高位是当前时间与自定义起始时间之间的秒数差，低 32 位是 Redis 按业务和日期维护的自增序列。左移后按位或得到 Long 类型 ID，具备趋势递增和跨实例不重复的特点。

### 47. 当前秒杀方案能支撑真正高并发吗？

**参考答案：**

当前入口已经把 MySQL 写入移到 RabbitMQ 消费者，前端请求只做校验、Redis Lua 预扣、事件落库和消息发布；但远端 RabbitMQ 连接、消费者启用、重复消费、故障恢复和真实并发压测仍需验收，不能直接声称“支撑某个 QPS”。

### 48. 当前秒杀实现还有哪些问题？

**参考答案：**

代码层面的可靠性闭环已完成（预留账本、Outbox、失败决策、持久化回滚、双向对账、DLQ 失败记录与处置 Service、订单状态查询），但仍需：真实 RabbitMQ 故障注入演练（规格 18.4 节）、跨存储崩溃窗口演练（18.5 节）、并发压测、上线迁移人工步骤（17.1 节）、RBAC 完成后才能开放失败处置 Controller。消费者默认关闭，需真实连通性验收后启用。

### 6.1 RabbitMQ 秒杀改造面试追问（可靠性闭环已完成）

> **事实边界：** 已完成（代码 + 单元测试）：交换机/队列/死信拓扑、JSON 持久化消息、六 Key 预留账本 Lua（含 orderId 反向索引，同 `{voucherId}` 槽）、事件表 Outbox（CAS + 租约）、发布尝试证据表、统一失败决策服务、异常三分类、监听前 ErrorHandler、DLQ 消费者、持久化回滚任务、双层对账任务（7 天快速扫描 + 每小时全量分页兜底）、库存安全初始化与缺失扫描、失败记录 + 审计 + 处置 Service、订单状态查询接口（新旧两版）。未完成：远端 RabbitMQ 可达性验收、真实并发/故障注入演练、跨存储崩溃窗口演练、上线迁移人工步骤、RBAC 与失败处置 Controller。

#### R1. 秒杀订单为什么选择 DirectExchange，而不是 FanoutExchange 或 TopicExchange？

**参考答案：**

当前只有一种明确事件：创建秒杀订单。生产者使用固定 routing key，例如 `seckill.order.create`，DirectExchange 只把消息投递给 routing key 完全匹配的队列，语义直接且易于排查。FanoutExchange 会忽略 routing key，更适合广播；TopicExchange 支持通配符，更适合多类分层事件。当前场景使用后两者会增加不必要的复杂度。

#### R2. 为什么要给秒杀订单配置死信队列？哪些消息会成为死信？

**参考答案：**

死信队列用于隔离主流程无法继续处理的消息，便于人工检查、补偿和告警。常见死信条件包括：消费者执行 `reject/nack` 且 `requeue=false`、消息超过 TTL、队列达到长度上限，以及 quorum queue 中消息超过 delivery limit。

消费者服务器宕机并不等于消息立即成为死信。消费者连接断开后，未确认消息通常会重新入队，等待其他消费者或该消费者恢复。同一用户重复下单也应该优先由 Redis Lua 拦截，并由数据库联合唯一索引最终兜底；消费者确认重复消息不可重试后，才拒绝并转入死信队列。

#### R3. 数据库暂时不可用时，为什么不能让消息无限重新入队？

**参考答案：**

数据库短暂超时或连接失败属于暂时性错误，可以进行有限次数重试，并使用退避间隔，例如 1 秒、2 秒、4 秒。持续立即重新入队会形成热循环，占用消费者线程、网络和 Broker 资源，还会阻塞正常消息。超过重试次数后应拒绝消息并进入死信队列，再由补偿任务或人工处理。

消息格式错误、字段缺失等永久性错误通常不应重复重试，因为重试不会改变结果。生产实现需要区分暂时性错误和永久性错误。

#### R4. 秒杀订单队列的 durable、exclusive 和 autoDelete 应该怎样设置？

**参考答案：**

- `durable=true`：RabbitMQ 重启后保留队列定义。
- `exclusive=false`：队列不绑定单个连接，允许多个应用实例共同消费。
- `autoDelete=false`：最后一个消费者断开后仍保留队列及积压消息。

消费者断开后，RabbitMQ 本来就不会继续向该消费者投递消息。若设置 `autoDelete=true`，最后一个消费者离线时队列反而可能被删除，不适合长期存在的订单队列。

#### R5. 队列持久化为什么不能单独保证订单消息不丢失？

**参考答案：**

`durable=true` 只保证 RabbitMQ 重启后队列定义仍存在，不能单独保证消息仍在。完整保障至少包含：交换机和队列持久化、消息使用持久化投递模式、生产者使用 Publisher Confirm 判断 Broker 是否接收消息，以及消费者在数据库事务成功后才发送 ACK。

Publisher Confirm 证明 Broker 是否接收了发布请求；消息持久化决定 Broker 重启后能否保留消息；Consumer ACK 决定消费成功前是否可以从队列删除消息。三者解决的问题不同。

#### R6. Consumer ACK 应该在什么时候发送？

**参考答案：**

消费者应在订单幂等校验、数据库扣库存和订单落库成功后再 ACK。若业务尚未完成就提前 ACK，后续数据库失败时 RabbitMQ 已经删除消息，会造成订单丢失。若消费者处理过程中宕机且没有 ACK，Broker 会在连接断开后重新投递，因此消费逻辑还必须具备幂等性。

#### R7. ConfirmCallback 和 ReturnCallback 分别解决什么问题？

**参考答案：**

在当前项目使用的 Spring Rabbit 2.2.18 中，`ConfirmCallback` 用于判断消息是否到达交换机的处理流程；`ReturnCallback` 用于发现交换机已经收到消息，但无法根据 routing key 路由到任何队列的情况。启用 `mandatory=true` 后，不可路由消息才会退回生产者。

只配置 Confirm 不够，因为交换机可能返回 ACK，但消息没有进入任何队列；只配置 Return 也不够，因为它不能完整判断消息是否成功到达交换机。新版资料中可能出现 `ReturnsCallback`，回答时应结合项目实际依赖版本。

#### R8. routing key 写错时，Confirm 和 Return 分别是什么结果？

**参考答案：**

如果交换机名称正确，但 routing key 从 `seckill.order.create` 错写为一个没有绑定的值，交换机已经成功收到消息，因此 Confirm 通常是 ACK；因为找不到匹配队列，在 `mandatory=true` 时会触发 ReturnCallback。

这类消息没有进入任何队列，所以不会自动进入死信队列。死信通常是消息进入队列后，因为拒绝、过期或超过限制而被死信交换机重新路由。

#### R9. 交换机名称写错时，应由哪个机制发现？

**参考答案：**

如果生产者向一个不存在的交换机发布消息，消息没有到达目标交换机，应通过 Confirm 的 NACK、Channel 异常或发布异常发现，而不是依赖 ReturnCallback。可以记成：exchange 名称决定能否到达交换机，routing key 决定交换机能否找到队列。

#### R10. 什么是 Outbox 模式？项目里怎么实现的？

**参考答案：**

业务操作和消息发送之间存在崩溃窗口：消息发出前进程崩溃，订单事件就丢了。Outbox 把“要发送的消息”先持久化到数据库，再由独立任务扫描发送。项目中事件表就是 Outbox：请求线程只写 PENDING 事件，`SeckillOrderPublishRetryTask` 是唯一发布入口——CAS + 租约抢占到期事件，同一事务内创建发布尝试记录并递增 retry_count，事务提交后调用一次 `convertAndSend`。发布前崩溃则事件仍在数据库，到期会被重新扫描。同时禁用了 `spring.rabbitmq.template.retry`，避免模板重试和 Outbox 重试相乘导致发送次数无法解释。

#### R11. Redis 预留账本是什么？为什么需要？

**参考答案：**

六个同 hash tag 的 Key：库存 String、用户 Set、事件预留详情 Hash（eventId -> orderId|userId|createdAt|version）、用户事件映射 Hash（userId -> eventId）、待对账 ZSet（eventId -> reservedAt）、orderId 反向索引 Hash（orderId -> eventId，供订单状态查询直达）。它关闭“Lua 预扣成功后、事件落库前崩溃”的窗口：旧实现里这个窗口只留下库存数字变化，没有可发现的持久化记录；有了账本，对账任务可以按待对账 ZSet 幂等补建 PENDING 事件或核对订单后收敛状态。同 hash tag 保证六 Key 在 Redis Cluster 同一槽，Lua 才能原子操作（反向索引必须也是券维度，否则跨槽报 CROSSSLOT）。

#### R12. 回滚为什么必须按 eventId 校验，不能只按 userId？

**参考答案：**

同一用户可能先预留事件 A、回滚后再预留事件 B。只按用户回滚时，事件 A 的迟到回滚会误删事件 B 的预留并错误恢复库存。回滚 Lua 检查用户事件映射（userId -> eventId）：映射不存在返回 0（已处理，幂等）；映射指向其他事件返回 -2（冲突，禁止动库存）；只有映射匹配才删除账本、SREM 用户，且只有确实移除了用户才 INCR 库存。

#### R13. 失败决策服务怎么避免误回滚？

**参考答案：**

Confirm NACK、Return、发送异常只按 attemptId 落发布尝试证据，是否回滚统一由 `SeckillOrderFailureDecisionService` 按固定顺序裁决：先查 MySQL 订单，订单存在则收敛 CONSUMED 禁止回滚；存在“ACK 且无 Return”的可路由尝试或任何未知尝试时禁止回滚（消息可能已到 Broker）；只有所有尝试都明确 NACK 或 Return 且没有消费证据才进入 ROLLBACK_PENDING。核心原则是不用“最后一次发送失败”推断“整个事件从未到达 RabbitMQ”。决策拆成纯函数 + 执行层，纯函数不碰 Redis/RabbitMQ，可完整单元测试。

#### R14. 消费者怎么处理和回滚任务的并发竞争？

**参考答案：**

状态机保证回滚和消费不会同时成功。回滚决定后事件是 ROLLBACK_PENDING：消费者到达时先 CAS 取消回滚（ROLLBACK_PENDING → PENDING），成功才继续创建订单；失败说明回滚任务已抢占，等待。回滚任务执行时事件是 ROLLBACK_EXECUTING：消费者遇到该状态抛可重试异常等回滚收敛；这处理了“回滚 Lua 已恢复库存、数据库状态未更新”的窗口，防止库存已恢复又同时创建订单。

#### R15. 消费失败为什么要先落失败记录再进 DLQ？顺序有什么讲究？

**参考答案：**

经典队列的死信转发不是可靠持久化边界——DLX 目标不可用或路由错误时死信可能丢失，所以 MySQL 失败记录才是消费失败的持久化事实，DLQ 只是运维副本。顺序：MessageRecoverer 先用独立 `@Transactional` 方法提交失败记录（幂等键防重），再把事件标记 DLQ，事务提交后才拒绝消息让 Broker 转发死信。失败记录落库失败时抛 `ImmediateRequeueAmqpException` 强制重新入队，禁止 ACK 或丢弃。失败记录和拒绝异常不能放同一事务，否则拒绝异常会把刚写入的记录一起回滚。

#### R16. 反序列化失败发生在 Listener 之前，怎么持久化？

**参考答案：**

`@RabbitListener` 方法执行前的消息转换异常无法被业务代码捕获，MessageRecoverer 也不一定被调用。项目配置了容器级 `SeckillRabbitListenerErrorHandler`：从失败的原始 AMQP Message 提取 messageId、Header 和受限长度的消息摘要，先幂等写入失败记录，再拒绝进 DLQ；持久化失败强制重新入队。禁止把无法转换的原始消息无限完整写入日志或数据库。

#### R17. 订单状态查询为什么区分 NOT_FOUND 和 UNAVAILABLE？

**参考答案：**

MySQL 或 Redis 查询失败时如果返回 NOT_FOUND，用户会以为订单不存在而重新下单——技术故障被伪装成业务结果。所以裁决顺序是 MySQL 订单 → 事件状态 → Redis 预留；所有数据源都查询成功且确实无记录才返回 NOT_FOUND；任一依赖查询失败返回 UNAVAILABLE 提示稍后再查。同时只能查自己的订单，他人订单按 NOT_FOUND 处理不泄露存在性。

#### R18. 对账任务为什么分批？库存为什么不能直接 Redis = MySQL？

**参考答案：**

分批防止全量 SCAN 阻塞 Redis。库存直接覆盖会把仍在途的有效预留再次卖出造成超卖，安全公式是“Redis 可售库存 = MySQL 剩余库存 − 尚未创建订单但仍有效的 Redis 预留数”；用户集合由“MySQL 已下单用户 + 有效预留用户”重建。对账是双向的：Redis 预留 → MySQL（孤儿预留补建事件或收敛）和 MySQL 事件 → Redis（CONSUMED 清理预留、ROLLBACK_EXECUTING 超时收敛、PUBLISH_UNKNOWN 超时转人工）。

---

## 7. 工程、安全与二次开发

### 49. 文件上传有哪些安全措施和不足？

**参考答案：**

当前限制 5MB、校验扩展名白名单、使用 UUID 重命名，并通过规范路径检查防止明显的目录穿越，上传接口也要求登录。**[当前缺口]** 只校验后缀仍可伪造，删除接口兼容 GET 且没有验证文件所有者。生产中还需校验 MIME、魔数、图片解码结果，使用对象存储和独立域名，并完善资源级权限。

### 50. 如果继续二次开发，你如何排优先级？

**参考答案：**

第一阶段处理正确性和安全性：唯一索引已经完成，下一步补齐消息消费幂等、回滚和失败补偿；其他安全项包括短信、密码哈希和上传权限。第二阶段完成异步入口、测试与监控，第三阶段再做 Feed、搜索、对象存储和链路追踪。

---

## 8. 高频补充追问

### 8.1 Token 为什么放请求头？

当前代码从 `authorization` 请求头读取 Token。这样便于前后端分离，但生产中还要结合 HTTPS、XSS、CSRF 和前端存储位置评估风险。

### 8.2 Redis 挂了还能登录吗？

当前登录和鉴权强依赖 Redis，Redis 不可用会直接影响验证码、会话和多个业务功能。生产中需要 Redis 高可用、超时、熔断、降级和故障演练。

### 8.3 为什么登录成功后删除验证码？

避免验证码在有效期内被重复使用，降低重放风险。

### 8.4 为什么关注关系在 MySQL 和 Redis 都保存？

MySQL 是持久化事实源，Redis Set 用于快速求共同关注。双写可能不一致，因此需要补偿、重建或消息同步机制。

### 8.5 热门笔记如何排序？

当前直接按照 MySQL `tb_blog.liked` 字段降序分页，不是使用 Redis 排行榜。

### 8.6 热门笔记查询有什么性能问题？

代码逐条查询作者信息，可能出现 N+1 查询。可通过批量查询、关联查询或用户信息缓存优化。

### 8.7 为什么 `listByIds` 后还要重新排序？

SQL 的 `IN` 查询不保证按照传入 ID 顺序返回，而 GEO 和 Feed 的顺序具有业务意义，所以需要按原 ID 序列重新组装。

### 8.8 为什么不能只依赖 Redis 判断一人一单？

Redis 和数据库可能不一致，Redis 锁也可能失效。订单最终写入 MySQL，因此数据库联合唯一索引才是最后防线。

### 8.9 逻辑过期为什么允许返回旧数据？

它用短暂的数据新鲜度换取热点查询的高可用和低延迟。过期后先返回旧值，再由后台线程重建。

### 8.10 现有测试能证明系统可上线吗？

不能。当前测试覆盖部分工具逻辑、安全修复和 Spring 上下文，但这不等于真实 MySQL、Redis、Nginx 环境下的并发、故障和性能验收。单元测试、集成测试和真实环境压测证据必须区分。

---

## 9. 简历表达模板

### 9.1 推荐写法

> 基于 Spring Boot、MyBatis-Plus、MySQL 与 Redis 完成餐饮点评学习项目，系统梳理登录、缓存、Feed 与秒杀调用链；使用 Redis Hash/ZSet/Set/GEO/BitMap 支撑会话、互动、附近商铺和签到场景；分析并改进分布式锁、缓存一致性与接口安全问题。

当前阶段可以写成：

> 为秒杀链路完成可靠性闭环改造：Redis Lua 原子预扣 + 六 Key 同槽预留账本（含 orderId 反向索引 Hash）、MySQL 事件表 Outbox（CAS + 租约抢占）、发布尝试证据表与统一失败决策服务、消费异常三分类（可重试/永久/一致性）、先落失败记录再进 DLQ 的死信闭环、持久化事件级回滚任务、Redis/MySQL 双向对账（7 天回看快速扫描 + 每小时全量分页兜底，异常预留先写人工集合再移除待对账入口）与库存安全初始化、六态订单状态查询；185 个单元测试覆盖状态机、决策与任务逻辑。真实 RabbitMQ 故障演练和并发压测仍待完成。

### 9.2 禁止夸大的说法

- “独立设计了大型分布式点评系统”。
- “支撑百万 QPS”或“解决千万级数据问题”，但没有测试报告。
- “生产短信已接入”，但代码只有日志。
- “秒杀已经通过真实高并发验收”，但当前还没有完整压测证据。
- “逻辑过期解决缓存击穿”，却没有说明默认入口并未启用。
- “异步链路已通过高并发验收”，但当前仍缺真实并发和故障演练证据。

---

## 10. 二次开发工作台

### P0：正确性与安全底线

- [ ] 从配置中移除默认敏感凭据，使用安全的环境变量或密钥服务。
- [ ] 接入真实短信通道，并增加审计、限流、失败重试和成本保护。
- [ ] 将加盐 MD5 迁移为 BCrypt 或 Argon2，并兼容旧用户升级。
- [x] 为 `tb_voucher_order(user_id, voucher_id)` 增加联合唯一索引。
- [ ] 修复文件删除的资源所有权校验，移除 GET 删除兼容。
- [ ] 增加 MIME、文件魔数、图片解码验证与对象存储隔离。
- [ ] 检查 `save(voucherOrder)` 失败时的事务行为和异常传播。

### P1：性能与可靠性

- [x] 编写并接入 Redis Lua，原子判断库存和一人一单。
- [x] 完成 RabbitMQ 消费者并切换秒杀入口。
- [x] 增加消费幂等、重试、死信、发布补偿与事件状态记录。
- [x] 增加死信人工处理 Service（重放/回滚/关闭 + 审计；Controller 待 RBAC）、事件对账和 Redis 预扣崩溃恢复（预留账本）。
- [x] 完成 Outbox 改造：请求线程不再直接发布，统一由发布任务 CAS + 租约抢占发送。
- [x] 增加失败决策服务、发布尝试证据表、异常三分类和监听前 ErrorHandler。
- [ ] 真实 RabbitMQ 故障注入演练（规格 18.4 节）和跨存储崩溃窗口演练（18.5 节）。
- [ ] 执行上线迁移人工步骤（规格 17.1 节）。
- [ ] 把缓存删除移动到事务提交后，评估延迟双删或消息同步。
- [ ] 修正空值缓存 TTL，区分正常数据和空值过期时间。
- [ ] 解决热门笔记作者查询的 N+1 问题。
- [ ] 评估 Redisson 锁、合理租约、续期和故障语义。
- [ ] 为 Feed 大 V 场景设计推拉结合方案。

### P2：工程化与可验证能力

- [ ] 增加 Testcontainers 或可重复的 MySQL/Redis 集成测试。
- [ ] 增加秒杀并发测试、一人一单和不超卖验收。
- [ ] 增加缓存故障、Redis 不可用和消息重复消费演练。
- [ ] 接入结构化日志、指标、链路追踪和告警。
- [ ] 记录压测环境、数据规模、指标定义与原始结果。
- [ ] 增加自动数据库迁移和回滚规范。

---

## 11. 源码回扣地图

| 主题 | 入口文件 |
| --- | --- |
| 登录和签到 | [`UserServiceImpl.java`](../../src/main/java/com/dish/review/service/impl/UserServiceImpl.java) |
| 拦截器顺序 | [`MvcConfig.java`](../../src/main/java/com/dish/review/config/MvcConfig.java) |
| Token 恢复 | [`RefreshTokenInterceptor.java`](../../src/main/java/com/dish/review/utils/RefreshTokenInterceptor.java) |
| 商铺缓存和 GEO | [`ShopServiceImpl.java`](../../src/main/java/com/dish/review/service/impl/ShopServiceImpl.java) |
| 通用缓存工具 | [`CacheClient.java`](../../src/main/java/com/dish/review/utils/CacheClient.java) |
| 点赞和 Feed | [`BlogServiceImpl.java`](../../src/main/java/com/dish/review/service/impl/BlogServiceImpl.java) |
| 关注和共同关注 | [`FollowServiceImpl.java`](../../src/main/java/com/dish/review/service/impl/FollowServiceImpl.java) |
| 秒杀下单与状态查询 | [`VoucherOrderServiceImpl.java`](../../src/main/java/com/dish/review/service/impl/VoucherOrderServiceImpl.java) |
| Lua 预扣/回滚/完成/初始化 | [`SeckillVoucherLuaExecutor.java`](../../src/main/java/com/dish/review/service/SeckillVoucherLuaExecutor.java)、[`seckill.lua`](../../src/main/resources/seckill.lua)、[`seckill_rollback.lua`](../../src/main/resources/seckill_rollback.lua)、[`seckill_reservation_complete.lua`](../../src/main/resources/seckill_reservation_complete.lua)、[`seckill_stock_init.lua`](../../src/main/resources/seckill_stock_init.lua) |
| RabbitMQ 拓扑与 Outbox | [`RabbitMqConfig.java`](../../src/main/java/com/dish/review/config/RabbitMqConfig.java)、[`SeckillOrderPublishRetryTask.java`](../../src/main/java/com/dish/review/mq/SeckillOrderPublishRetryTask.java)、[`SeckillOrderPublisher.java`](../../src/main/java/com/dish/review/mq/SeckillOrderPublisher.java)、[`SeckillPublishRetryPolicy.java`](../../src/main/java/com/dish/review/service/SeckillPublishRetryPolicy.java) |
| Confirm/Return 与超时 | [`SeckillPublishConfirmHandler.java`](../../src/main/java/com/dish/review/mq/SeckillPublishConfirmHandler.java)、[`RabbitMqPublisherCallback.java`](../../src/main/java/com/dish/review/mq/RabbitMqPublisherCallback.java)、[`SeckillPublishConfirmTimeoutTask.java`](../../src/main/java/com/dish/review/mq/SeckillPublishConfirmTimeoutTask.java) |
| 失败决策 | [`SeckillOrderFailureDecisionService.java`](../../src/main/java/com/dish/review/service/SeckillOrderFailureDecisionService.java) |
| 事件状态机与租约 | [`SeckillOrderEventStateMachine.java`](../../src/main/java/com/dish/review/service/SeckillOrderEventStateMachine.java)、[`SeckillOrderEventService.java`](../../src/main/java/com/dish/review/service/SeckillOrderEventService.java)、[`SeckillOrderEvent.java`](../../src/main/java/com/dish/review/entity/SeckillOrderEvent.java) |
| RabbitMQ 消费与幂等 | [`SeckillOrderConsumer.java`](../../src/main/java/com/dish/review/mq/SeckillOrderConsumer.java)、[`SeckillRabbitListenerErrorHandler.java`](../../src/main/java/com/dish/review/mq/SeckillRabbitListenerErrorHandler.java)、[`VoucherOrderHandler.java`](../../src/main/java/com/dish/review/service/VoucherOrderHandler.java) |
| 持久化回滚 | [`SeckillReservationRollbackTask.java`](../../src/main/java/com/dish/review/mq/SeckillReservationRollbackTask.java)、[`SeckillRollbackRetryPolicy.java`](../../src/main/java/com/dish/review/service/SeckillRollbackRetryPolicy.java) |
| 双向对账与库存扫描 | [`SeckillOrderReconciliationTask.java`](../../src/main/java/com/dish/review/mq/SeckillOrderReconciliationTask.java)、[`SeckillStockInitScanTask.java`](../../src/main/java/com/dish/review/mq/SeckillStockInitScanTask.java) |
| DLQ 闭环与失败处置 | [`SeckillOrderDeadLetterConsumer.java`](../../src/main/java/com/dish/review/mq/SeckillOrderDeadLetterConsumer.java)、[`SeckillFailureEvidence.java`](../../src/main/java/com/dish/review/mq/SeckillFailureEvidence.java)、[`SeckillFailureCaseService.java`](../../src/main/java/com/dish/review/service/SeckillFailureCaseService.java)、[`SeckillOrderFailureAdminService.java`](../../src/main/java/com/dish/review/service/SeckillOrderFailureAdminService.java) |
| Redis 分布式锁 | [`SimpleRedisLock.java`](../../src/main/java/com/dish/review/utils/SimpleRedisLock.java) |
| 全局 ID | [`RedisIdWorker.java`](../../src/main/java/com/dish/review/utils/RedisIdWorker.java) |
| 数据库约束 | [`dish_review.sql`](../../src/main/resources/db/dish_review.sql)、[`db/migration`](../../src/main/resources/db/migration) |
| 测试现状 | [`src/test/java`](../../src/test/java) |

---

## 12. 建议的源码阅读与实操顺序

1. **认证主线**：`MvcConfig` → 两个拦截器 → `UserHolder` → `UserServiceImpl`。独立画出 Token 创建、续期、登出和 ThreadLocal 清理流程。
2. **商铺主线**：`ShopController` → `ShopServiceImpl` → `CacheClient`。分别请求存在和不存在的商铺，观察普通缓存和空值缓存。
3. **社交主线**：阅读 `BlogServiceImpl` 和 `FollowServiceImpl`，写清每个 ZSet 的 member、score，以及 Feed 的 `lastId + offset`。
4. **秒杀主线**：`VoucherOrderServiceImpl`（ID 前置 + Lua 预留）→ `SeckillOrderEventService`（PENDING + CAS/租约）→ `SeckillOrderPublishRetryTask`（Outbox）→ `SeckillPublishConfirmHandler`（尝试证据）→ `SeckillOrderFailureDecisionService`（统一裁决）→ `SeckillOrderConsumer` → `VoucherOrderHandler`（事务内 CAS + 落库）→ `SeckillReservationRollbackTask` / `SeckillOrderReconciliationTask`（收敛）。分别验证 Redis 预留账本、事件状态、发布尝试、数据库订单和重复消息。
5. **表与索引**：对主要查询执行 `EXPLAIN`，观察索引、回表、排序和范围扫描，而不是只背表结构。
6. **开始二开**：先为 RabbitMQ 改造写验收条件、失败矩阵和数据库迁移，再修改生产者、消费者和测试，最后分别核对 Redis、RabbitMQ 与 MySQL 状态。可靠性闭环改造规格见 [`10-rabbitmq-seckill-reliability-development-spec.md`](../development/10-rabbitmq-seckill-reliability-development-spec.md)，流程文档见 [`09-rabbitmq-seckill-flow.md`](09-rabbitmq-seckill-flow.md)。

---

## 13. 更新记录

| 日期 | 基线 | 变更 | 证据状态 |
| --- | --- | --- | --- |
| 2026-08-18 | `5876354` | 建立 50 题面试题库、事实边界、二开清单和源码地图 | 已回扣当前源码与 SQL；未声称真实生产压测 |
| 2026-08-19 | `aa7392d` + `33a0303` | 合并历史面试指南，补回数据库约束、分类缓存、评论链路、GEO 同步边界、Redis 秒杀事实与实操顺序 | 文档合并；代码能力没有因此发生变化 |
| 2026-08-19 | `codex/rabbitmq-seckill` | 增加 RabbitMQ 秒杀改造面试追问 | Spring AMQP 依赖和连接配置通过 JDK 8 编译 |
| 2026-08-20 | `main`（RabbitMQ 秒杀改造） | 接通 Lua 预扣、PENDING 事件、RabbitMQ 生产/消费、Confirm/Return 回滚、有限补偿与 DLQ 状态记录；远端创建事件表 | 主源码和测试源码编译通过、事件表结构核验通过；RabbitMQ 5672 当前拒绝连接，真实并发验收未完成 |
| 2026-08-21 | 秒杀可靠性闭环改造 | 按规格 10 完成阶段 1-6：预留账本、Outbox、失败决策、异常三分类、DLQ 闭环、持久化回滚、双向对账、库存安全初始化、订单状态查询、失败处置 Service；新增 R10-R18 面试题；三轮验收修复关键状态机和前后端联动；最终调整人工移交 Lua 为“先写目标、后删源入口”，避免运行时错误留下不完整迁移 | 185 个单元测试全部通过；迁移 SQL 静态检查通过；真实 RabbitMQ 故障演练未执行 |

后续更新建议使用以下格式：

```markdown
| YYYY-MM-DD | commit | 完成了什么改造，哪些题目同步更新 | 单测/集成测试/真实环境证据 |
```

每次面试前最终自检：

- [ ] 能在两分钟内说清项目，不堆砌 Redis 名词。
- [ ] 能画出登录、缓存、Feed、秒杀四条调用链。
- [ ] 能区分 Redis 悲观式用户锁、MySQL 条件更新和事务边界。
- [ ] 能说明订单唯一索引已完成，并主动说出消费者、补偿、短信、密码和上传校验等缺口。
- [ ] 简历中的每个动词都有对应代码、提交或测试证据。
- [ ] 不虚构 QPS、用户规模、生产部署和个人贡献。
