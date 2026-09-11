# DianPing：技术面试 系统设计与业务逻辑要点

> **定位**：这是一份**结构化复习笔记**，按系统设计维度组织（一致性、幂等、可靠性、性能），面向面试快速复习与演练。
>
> **与题库文档的关系**：逐题背诵看 [08-interview-and-secondary-development-guide.md](08-interview-and-secondary-development-guide.md)；本文件只保留"设计要点 + 一到两条高频追问"，需要完整回答时跳转 08 对应题号。两处结论以当前仓库代码、自动化测试和 2026-09-06 隔离环境压测为准。

---

## 0. 一句话定位（面试开口句）

DianPing 是一个基于 **Java 8 + Spring Boot + MyBatis-Plus + MySQL + Redis + RabbitMQ** 的餐饮点评平台。业务面上是登录、商铺、笔记、点赞关注、Feed、签到、优惠券；技术深水区在**优惠券秒杀链路的异步化与可靠性治理**——从 Redis 原子预留、MySQL 事件账本（Outbox）、RabbitMQ 异步下单，到失败重试、幂等消费、库存回滚、双向对账和人工兜底，形成可追踪的业务闭环。

关键认知：这是个学习型项目，不缺 QPS 承诺，不缺"百万并发"包装；真正能讲的，是自己改造、测试、压测验证过的**可靠性与一致性设计**。

---

## 1. 整体架构与调用链速查

```
浏览器（Vue2 静态页）
   │ /api/*
   ▼
Nginx（静态资源 + 反向代理）
   ▼
Spring Boot
  RefreshTokenInterceptor → LoginInterceptor → Controller → Service → Mapper
                                        │                      │      │
                                        │                    Redis  MySQL
                                        └──────── RabbitMQ ◀────────┘（秒杀异步）
```

### 四条必须能当场画出的调用链

| 链路 | 入口 → 出口 |
| --- | --- |
| 登录 | 手机号 → 验证码(Redis) → 查/建用户 → 生成 Token → UserDTO 写 Redis Hash → 拦截器恢复 |
| 商铺缓存 | 查 Redis → 命中返回 → 未命中查 MySQL → 写缓存；DB 也不存在则缓存空值防穿透 |
| Feed | 作者发布 → 查粉丝 → 写粉丝 Feed ZSet → 读者按时间戳滚动分页 |
| 秒杀 | 校验 → 生成 eventId/orderId → Lua 原子预扣+写预留账本 → 写 PENDING 事件返回受理 → Outbox 任务 CAS+租约抢占发布 → Confirm/Return 落证据 → 失败决策统一裁决 → 消费者事务内 CAS+扣库存+落单+CONSUMED → 对账收敛 |

> 详细调用链见 [09-rabbitmq-seckill-flow.md](09-rabbitmq-seckill-flow.md)。

---

## 2. 认证与会话设计

**设计要点**
- **Token 存 Redis 而非 HttpSession**：解决多实例 Session 共享，利于横向扩容。
- **存 UserDTO（精简字段）而非完整 User**：省内存、避免密码/手机号等敏感字段进入上下文。
- **双拦截器分工（顺序 0 / 1）**：`RefreshTokenInterceptor` 无条件先恢复登录态并滑动续期 TTL（30 分钟）；`LoginInterceptor` 再判断是否必须登录。
- **ThreadLocal + 请求结束 remove()**：避免 Tomcat 线程复用导致的用户串号 / 内存泄漏。
- **验证码**：Redis `SET NX` 限 60s 重发，六位码存 2 分钟，登录成功即删除防重放。

**高频追问**
- 为什么登录状态必须存 Redis？→ 多实例共享会话、扩容。
- TTL 滑动续期怎么实现？为什么这么设计？→ 活跃续期 / 停止访问 30 分钟失效，兼顾体验与泄露窗口。
- 当前验证码真发了吗？→ **[缺口]** 测试模式直接返回，生产仅日志。对面谈时主动说。

---

## 3. 缓存与一致性设计

**设计要点**
- **商铺详情 = Cache Aside**：先查 Redis，未命中查 MySQL 回填；不存在缓存空值防**穿透**。
- **穿透 / 击穿 / 雪崩**三种都要能说清，且区分"当前实现"与"缺口"：
  - 穿透：空值缓存（已实现）；布隆过滤 + 参数校验 + 限流（未实现）。
  - 击穿：热点 Key 失效瞬间的并发击穿；互斥锁 / 逻辑过期两类实现**保留但默认入口未启用**。
  - 雪崩：大量 Key 同时失效；随机 TTL、预热、Redis 高可用、限流降级。
- **更新 = 先更新 DB，再删缓存**，而非先删后更（避免中间读回旧值重填 Chache）。
- **[缺口]** 缓存删除在 DB 事务提交前，存在回填旧值窗口；优化方向：提交后删除、延迟双删、Binlog/MQ 兜底。空值缓存与正常数据共用同一 TTL（30 分钟）。

**高频追问**
- 先删缓存再更新 DB 有什么问题？→ (见 08 §21)
- 逻辑过期为什么允许读旧值？→ 用短暂新鲜度换高可用低延迟，需预热。
- 为什么有缓存还需要 DB？→ DB 是持久化事实源，Redis 是高性价比的热路径。

---

## 4. Redis 数据结构选型（高频考察）

| 场景 | 结构 | 选择原因 |
| --- | --- | --- |
| 验证码/限流 | String | `SET NX`、`INCR` |
| 登录用户 | Hash | 存精简 UserDTO |
| 商铺缓存 | String | JSON 序列化 |
| 分布式锁 | String | `SET key val NX EX` |
| 点赞 | ZSet | member=用户，score=时间，保序且判一赞 |
| 关注/共同关注 | Set | 去重 + 交集 |
| Feed 收件箱 | ZSet | 按时间排序 + 范围（滚动）分页 |
| 附近商铺 | GEO | 经纬度半径查询 + 距离排序，回查 MySQL 补全字段 |
| 签到 | BitMap | 每用户每月 1 Key，一天 1 bit，按位运算统计连续签到 |

**高频追问**
- 点赞为什么用 ZSet 不用 Set？→ Set 无法保序；ZSet 用 score 存时间可回溯点赞顺序。(08 §27)
- GEO 为什么还要回查 MySQL？→ GEO 只存 ID+坐标，无完整业务字段，且 `listByIds` 不保序，需按 GEO 结果重排序。(08 §24)
- BitMap 连续签到怎么算？→ BitField 读本月签到为无符号数，从低位（今天）右移数到第一个 0。(08 §26)
- Feed 为什么用滚动分页？→ 页码分页在动态插入时可能重复/遗漏；用 lastId+offset 规避。(08 §31)

---

## 5. 秒杀系统设计（Interview Core，务必深挖）

秒杀接口返回"请求已受理"，正式订单由消费者异步创建。`eventId / orderId` 在 Lua 前生成，贯穿预留、发布、消费、对账、回滚全程，实现跨 Redis/MySQL/RabbitMQ 的端到端可追踪。

### 5.1 并发正确性：不超卖 + 一人一单（分层防线）

三层防线，各司其职，缺一不可：
1. **Redis Lua 原子预扣**（第一道 / 性能层）：一次脚本内完成"判库存足 → 判用户未参与 → 扣库存 → 记用户集合 → 写预留账本"。同 `{voucherId}` HashTag 保证六 Key 同槽，Lua 才能单点原子执行。
2. **MySQL 条件更新兜底**（正确性层）：`UPDATE ... SET stock=stock-1 WHERE voucher_id=? AND stock>0`，影响行数 0 即返回库存不足，绝不为负。
3. **数据库 `(user_id, voucher_id)` 联合唯一索引**（最终底线）：Redis 锁/消息链路全部失效时仍能拒绝重复订单。

**高频追问**
- `stock>0` 为什么能防超卖？影响行数是核心 (08 §35)。
- 前面先查一次库存能防超卖吗？→ 不能，只是快速失败 (08 §36)。
- 为什么有 Redis 锁 + Lua 还需要唯一索引？→ 锁会过期/失效，唯一索引是落库最终底线 (08 §45)。
- 秒杀为什么按用户加锁而不按券加全局锁？→ 一人一单只需串行化同用户，全局锁拖垮吞吐 (08 §37)。

### 5.2 可靠性闭环：Outbox + 发布证据 + 失败决策

- **请求线程不直接发 MQ**，事件表 `tb_seckill_order_event` 即 **Outbox**：先持久化 PENDING，再由 `SeckillOrderPublishRetryTask` 唯一发布入口（**CAS + 租约抢占 + lease_token 栅栏**）到期扫描发布，关闭"发前崩溃丢事件"窗口。已禁用 `spring.rabbitmq.template.retry`，避免与 Outbox 重试叠加导致发送次数不可解释。
- **每次真实发送一行证据** `tb_seckill_publish_attempt`（`uk(event_id,attempt_no)`），Confirm / Return / 同步异常 / 确认超时分别落库，不依赖内存。
- **统一失败决策**：证据只落库，是否回滚由 `SeckillOrderFailureDecisionService` 裁决，固定优先级：
  **MARK_CONSUMED → MANUAL_REVIEW → RETRY_PUBLISH → ROLLBACK → MANUAL_REVIEW(兜底)**。
  核心原则：**不用"最后一次发送失败"推断"事件从未到达 Broker"**——存在 ACK 或未知（PUBLISH_UNKNOWN）尝试时禁止回滚。
- **退避策略**（纯函数，可单测）：
  - 发布重试：FAST `[1,2,4]` → SLOW `[30,120,600,1800]`，共 **首次发送 + 7 次退避重发 = 8 次自动发送**；最后一次发送后必须等待 **90 秒终局窗口**（Confirm/Return/确认超时先收敛），窗口内事件仍未进展才由 Outbox 扫描统一转 MANUAL_REVIEW。
  - 回滚重试：`5s,30s,300s,1800s`，此后转 MANUAL_REVIEW。
- **幂等闭环**：消息允许重复到达，靠事件状态 CAS + 订单唯一约束 + 身份一致性校验收敛。

**高频追问**
- Outbox 解决什么崩溃窗口？(08 R10)
- Confirm 与 Return 各自发现什么问题？(08 R7 / R8 / R9)
- 为什么结果未知时不能直接回滚？(08 R13)
- Publisher Confirm 证明"Broker 收了"，为什么还不够？还需持久化模式 + Consumer ACK。(08 R5 / R6)

### 5.3 事件状态机（终态不可被迟到回调覆盖）

```text
PENDING / PUBLISH_UNKNOWN / CONFIRMED
      │
      ├── CONSUMED（订单创建成功，自动终态）
      ├── ROLLBACK_PENDING → ROLLBACK_EXECUTING → ROLLED_BACK（自动终态）
      ├── DLQ（消费失败隔离）
      └── MANUAL_REVIEW（人工兜底）
```
关键设计：`CONSUMED`、`ROLLED_BACK` 为**自动终态**，迟到的 Confirm / Return / 任务结果不能覆盖终态。`row_version` 做状态变更计数 / 并发栅栏，配合租约防多实例互相踩踏。

**高频追问**
- 消费与回滚并发怎么收敛？→ 状态机保证不会同时成功；ROLLBACK_PENDING 消费者先 CAS 取消回滚，ROLLBACK_EXECUTING 消费者抛可重试异常等待收敛 (08 R14)。

### 5.4 回滚：按 eventId 精确、防误回滚

- **必须按 eventId 校验**，不能只按 userId：同一用户先后预留事件 A、B 时，只按用户回滚会误删 B 的预留并错误恢复库存。
- 回滚 Lua 检查用户→事件映射：映射不存在=已处理（幂等返回 0）；映射指向其他事件=冲突（返回 -2，禁止动库存）；匹配才删除账本、SREM 用户，且**只有确实移除用户才 INCR 库存**。
- 回滚任务执行前**再次确认订单不存在**，再恢复库存撤销占用。

### 5.5 消费失败：先落库再进 DLQ（死信顺序有讲究）

- 死信转发不是可靠持久化边界（DLX 不可用 / 路由错误会丢），**MySQL 失败记录才是持久化事实，DLQ 只是运维副本**。
- 顺序：MessageRecoverer 用独立 `@Transactional` 方法**先提交失败记录**（幂等键防重）→ 标记 DLQ → 事务提交后才拒绝转发死信；落库失败则抛 `ImmediateRequeueAmqpException` 强制重新入队。
- **失败记录与拒绝异常不能放同一事务**，否则拒绝会连带回滚记录。
- **反序列化失败发生在 Listener 前**，业务代码捕不到：容器级 `SeckillRabbitListenerErrorHandler` 从原始 AMQP Message 提取 messageId/Header/受限摘要幂等落失败记录后拒绝进 DLQ。

### 5.6 双向对账 + 库存安全

- **对账是双向**：Redis 预留 → MySQL（孤儿预留幂等补建 PENDING 事件 / 收敛）；MySQL 事件 → Redis（CONSUMED 清理预留、ROLLBACK_EXECUTING 超时收敛、PUBLISH_UNKNOWN 超时转人工）。
- **快扫 + 兜底**：7 天回看快速扫描 + 每小时全量券分页兜底扫描，覆盖跨存储写入间隙。
- **库存不能直接 Redis=MySQL 覆盖**：会把在途有效预留再卖出造成超卖。安全公式（见 08 R18）。
- **库存安全初始化 + 缺失扫描**：`seckill_stock_init.lua` 幂等初始化，定时扫描缺失库存。

**高频追问**
- 对账为什么分批？→ 防全量 SCAN 阻塞 Redis。(08 R18)

### 5.7 订单状态查询：区分 NOT_FOUND 与 UNAVAILABLE

- 裁决顺序：MySQL 订单 → 事件状态 → Redis 预留；查询成功且无记录才返回 `NOT_FOUND`。
- 任一依赖查询失败返回 `UNAVAILABLE`（提示稍后再查），**避免把技术故障伪装成"订单不存在"**诱导用户重复下单。
- 只能查自己的订单，他人订单按 NOT_FOUND 处理，不泄露存在性。

---

## 6. 分布式锁与全局 ID

**分布式锁（SimpleRedisLock）**
- `SET key val NX EX`：NX 保证互斥，EX 防持锁宕机死锁；锁值 = 应用 UUID + 线程 ID。
- 释放锁必须 **GET 比对持有者后再 DEL**，且用 **Lua 合并为原子**操作，防止"锁已过期、他人持同名锁、误删他人锁"。
- **[缺口]** 固定 1200s 无看门狗续期，无重入；生产可换 Redisson 配等待/租约/续期/监控。

**全局 ID（RedisIdWorker）**
- 高位 = 时间秒差，低 32 位 = Redis 按业务+日期自增序列，左移按位或 → Long 型 ID。
- 特点：趋势递增、跨实例不重复（时间戳 + 自增）。

---

## 7. 性能与容量边界（拿证据说话）

> 以 [16-seckill-capacity-test-report-20260906.md](../development/16-seckill-capacity-test-report-20260906.md) 为准，单机压测机经 SSH 隧道打两个应用实例，隔离 MySQL/Redis/RabbitMQ。

| 目标 | 时长 | 实际速率 | HTTP 失败 | 业务受理 | p95 | 结论 |
| ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 50 QPS | 30s | 49.96 | 0 | 100% | 40.57ms | 通过 |
| 100 QPS | 30s | 99.87 | 0 | 100% | 22.81ms | 通过 |
| 200 QPS | 30s | 199.74 | 0 | 100% | 16.13ms | 通过 |
| 300 QPS | 30s | 299.56 | 0 | 100% | 16.73ms | 通过 |
| 500 QPS | 30s | 360.16 | 4.04% | 95.95% | 17.09ms* | 失败 |

`*` 500 QPS 档最大耗时 28.43s，p95 不能代表该档健康。

- 全链路验收：券 11 的 **37,177 个事件全部 CONSUMED**，正式订单 37,177，剩库存 82,823；Redis 预留、主队列、DLQ 全部清空。
- **诚实边界**：300 QPS 是**30 秒短时测试**的最高通过档，不是生产容量承诺；500 QPS 失败后需临时调高恢复参数才清空积压，默认恢复时延未专项验证；真实 RabbitMQ 故障注入、多实例宕机恢复、10 分钟以上稳定性**仍待验证**。

---

## 8. 必须主动说出口的「缺口清单」（面试加分/减分关键）

> 会主动交代边界，比被追问强逼出真相更能体现工程严谨。

- 短信仅测试模式 / 生产仅日志，未接真实供应商。
- 密码用**加盐 MD5**，不适合生产，应迁 BCrypt/Argon2/PBKDF2。
- 缓存删除在事务提交前，存在回填旧值窗口。
- 商铺详情默认走 Cache Aside，逻辑过期/互斥锁实现保留但**未启用**。
- 空值缓存与正常数据同 TTL。
- 分布式锁无看门狗续期、无重入。
- 点赞数 / 关注数为 MySQL-Redis **跨存储双写**，仅尽力补偿非强一致。
- Feed 纯推模式写放大，未做推拉结合。
- 秒杀：真实 MQ 故障注入、跨存储崩溃窗口、并发压测、长时间稳定性仍待完成；失败处置 Controller 因无 RBAC 未开放。
- 测试覆盖以单元/组件/Spring 装配为主，204 项全绿，但不等同真实环境验收证据。

---

## 9. 五分钟复习路线

1. 先背 §0 + §1 四条调用链（两分钟介绍过关）。
2. 秒杀 §5 逐小节过一遍（这是深水区，也是加分区）。
3. 缓存/一致性 §3 + Redis 选型 §4 表格刷一遍。
4. 倒背 §8 缺口清单（防"被逼问穿帮"）。
5. 需要逐题精读时进 [08-interview-and-secondary-development-guide.md](08-interview-and-secondary-development-guide.md)。

---

## 10. 更新记录

| 日期 | 说明 |
| --- | --- |
| 2026-09-11 | 依据 README、08 题库、09 调用链与 16 压测报告，建立结构化系统设计/业务逻辑复习笔记；结论以 2026-09-06 压测与当前仓库代码为准。 |