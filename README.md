# 餐饮点评与高可靠秒杀系统

DianPing 是一个基于 **Java 8 + Spring Boot 2.3 + MyBatis-Plus + MySQL + Redis + RabbitMQ + Nginx** 的餐饮点评后端系统。业务覆盖**登录会话、商铺详情与缓存、附近商铺（GEO）、探店笔记与评论、点赞关注、Feed 流推送、连续签到、普通优惠券与秒杀优惠券**等完整点评场景。

本项目的差异化亮点在于**优惠券秒杀链路**：不是常见的"Redis 扣库存后发一条 MQ"，而是完整实现了 Lua 预占账本、MySQL Outbox、独立发送证据、Spring Publisher Confirm / Return 异步回调、统一决策机构、消费幂等事务、持久化 Lua 回滚、DLQ 分类和 Redis/MySQL 双向对账。可靠性主线形成闭环，代码、表结构和测试全部就位。

> 当前 JDK 8 自动化测试 200 个全部通过；RabbitMQ 实机故障注入、多实例宕机恢复、真实并发压测结论尚未形成可复现报告。README 描述均以代码和测试为准，不做超出范围的可靠性承诺。

---

## 目录

- [项目概览：业务模块 + 秒杀亮点](#项目概览业务模块--秒杀亮点)
- [系统架构（整体拓扑 + 后台任务）](#系统架构整体拓扑--后台任务)
- [优惠券秒杀全流程（面试主链）](#优惠券秒杀全流程面试主链)
- [九大可靠性设计点 + 硬数字](#九大可靠性设计点--硬数字)
- [秒杀决策机构（统一裁决）](#秒杀决策机构统一裁决)
- [核心表结构](#核心表结构)
- [技术栈](#技术栈)
- [快速开始](#快速开始)
- [核心 API（四类：用户/商铺内容/关注优惠/秒杀）](#核心-api四类用户商铺内容关注优惠秒杀)
- [源码阅读顺序（分两轮：主链 + 异常）](#源码阅读顺序分两轮主链--异常)
- [测试与能力边界（诚实版）](#测试与能力边界诚实版)
- [常见问题（点评 + 秒杀）](#常见问题点评--秒杀)

---

## 项目概览：业务模块 + 秒杀亮点

### 业务模块（餐饮点评主系统）

| 模块 | 实现要点 |
|---|---|
| 登录与会话 | 手机验证码或密码登录；Redis Hash 存 UserDTO；双拦截器 Refresh + Login 配合：前一个恢复 ThreadLocal 并滑动续期、后一个判权限；请求结束统一清理 |
| 商铺系统 | 详情穿透防缓存（空值+短TTL，缓存未命中查 MySQL 回填，不存在也写空串缓存占位防穿透）；Redis GEO 按类型+经纬度查附近商铺；商铺更新后删缓存（保持一致性） |
| 探店笔记 | 发布、详情、热门列表；点赞/取消点赞（ZSet 点赞排名 Top 5）；评论 CRUD（内容 HTML 转义防 XSS） |
| 关注关系 | 关注/取关；共同关注（交集）；Feed 流（**推模式**：发布笔记时把 ID + 时间戳 ZADD 进所有粉丝的滚动收件箱） |
| Feed 流分页 | 使用 `score(minTime) + offset` 滚动翻页，解决多条动态同一毫秒时间戳导致的重复或遗漏问题 |
| 签到 | Redis BitMap 按月存储签到记录，位运算统计连续签到天数 |
| 优惠券 | 普通券 + 秒杀券两张表；秒杀券独立活动时间窗和库存管理 |
| 文件上传 | 三重防护：扩展名白名单 + 大小限制 + **文件魔数字节校验**；删除时使用 `toRealPath()` + `startsWith()` 防路径穿越攻击；按用户目录隔离所有权 |
| 权限 | 管理写接口（商铺、优惠券新增/更新）需 ROLE_ADMIN；历史用户迁移为 USER；管理员需手动升级 |

### 秒杀亮点（一句话定位）

> **一次秒杀请求 → Lua 原子预占 → Outbox 受理 → 7 次自动发布尝试（Publisher Confirm 证据链）→ 统一决策裁决重试/回滚/人工 → 消费事务幂等落单 → 4 次自动回滚 → 孤儿对账兜底 → 最终人工工作台**。这是本项目和其他点评脚手架项目最大的区分度。

---

## 系统架构（整体拓扑 + 后台任务）

```
浏览器 / 移动端
     │
     ▼
Nginx :8080
 ├─ Vue 2 + Element UI 静态页面
 └─ /api/* 反向代理
        │
        ▼
Spring Boot :8081
 ├─ Result 统一返回体 + WebExceptionAdvice 统一异常
 ├─ 登录拦截器（双拦截器 + ThreadLocal + 滑动续期）
 ├─ Controller / Service / Mapper 三层
 │      ├─ 用户 / 商铺 / 笔记 / 关注 / 优惠券
 │      └─ 秒杀订单 VoucherOrderServiceImpl + VoucherOrderHandler
 ├─ Redis（业务 + 秒杀预占账本）
 ├─ RabbitMQ（发布/Confirm/Return/消费/重试/DLQ）
 └─ 定时任务后台
       ├─ SeckillOrderPublishRetryTask       每 1s  发布补偿/Outbox
       ├─ SeckillPublishConfirmTimeoutTask   每 5s  Confirm 超时兜底
       ├─ SeckillReservationRollbackTask     每 5s  库存回滚
       ├─ SeckillOrderReconciliationTask     每分+每小时 孤儿预留对账
       └─ SeckillStockInitScanTask           启动+定时 安全补齐库存Key
                │
                ▼
        MySQL            Redis           RabbitMQ
      业务表/Outbox     会话/缓存/Feed   主交换机/主队列
      attempt/失败单    GEO/签到/预占    DLX + DLQ
```

---

## 优惠券秒杀全流程（面试主链）

```
┌──────────────────────  ① 用户下单入口  ──────────────────────┐
│ POST /voucher-order/seckill/{voucherId}                         │
│   -> VoucherOrderServiceImpl.seckillVoucher()                   │
│   -> seckill.lua 原子校验（库存>0 + 一人一单SET + 时间窗）        │
│   -> 写 Redis 预占账本（{voucherId} Hash Tag，集群同槽避免CROSSSLOT）│
│   -> 本地事务：MySQL 写 voucher_order_event = PENDING（受理）     │
│   -> 返回 orderId + PROCESSING（接口成功 ≠ 订单落库）             │
└────────────────────────────────────────────────────────────────┘
                                 │
                                 ▼
┌──────────────────────  ② 发布补偿（Outbox） ───────────────────┐
│ SeckillOrderPublishRetryTask（每 1s 扫）                         │
│   条件：status IN (PENDING, PUBLISH_UNKNOWN)                    │
│      AND next_retry_time <= NOW()                               │
│      AND lease 空/过期                                           │
│   -> CAS claimLease（lease_token++，fencing 防过期执行者）         │
│   -> retry_count++ + createNextAttempt(WAITING)                 │
│   -> SeckillOrderPublisher.send()  单 convertAndSend（关闭模板重试）│
│   -> attach SettabelListenableFuture 回调                         │
│   -> deferNextRetry：退避序列 [1s→2s→4s→30s→2m→10m→30m]         │
│                 或第 8 次后 90 秒终局窗口                         │
│                                                                 │
│ 异常分支 A：发送同步异常                                          │
│   -> recordUnknown(send_exception)                               │
│   -> evaluateForRetry → RETRY_PUBLISH（安排补偿）或 MANUAL_REVIEW │
│                                                                 │
│ 异常分支 B：Confirm 超时                                          │
│   SeckillPublishConfirmTimeoutTask 每 5s                          │
│     -> attempt 超 30s 仍 WAITING → recordUnknown(confirm_timeout)│
│     -> evaluateForRetry                                          │
└────────────────────────────────────────────────────────────────┘
                                 │
                                 ▼
┌──────────────────────  ③ 回调证据 + 决策机构  ──────────────────┐
│ Confirm / Return / Future 异常                                    │
│   -> SeckillPublishConfirmHandler / RabbitMqPublisherCallback    │
│   -> recordAck / recordNack / recordReturned / recordUnknown     │
│       （WAITING 条件 CAS 挡板：谁先到谁改主状态；迟到写late_confirm）│
│   -> SeckillOrderFailureDecisionService.decide()                  │
│         五大优先级 → RETRY_PUBLISH / ROLLBACK / MANUAL_REVIEW /   │
│                     WAIT / MARK_CONSUMED                          │
└────────────────────────────────────────────────────────────────┘
                   │                │                │
                   ▼                ▼                ▼
            RETRY_PUBLISH       ROLLBACK        MANUAL_REVIEW
          退避推迟→Outbox      回滚任务执行     事务写 SOURCE_PUBLISH
                                             + 人工工作台可见
                                 │
                                 ▼
┌──────────────────────  ④ 消费事务落单  ────────────────────────┐
│ SeckillOrderConsumer 收到消息                                    │
│   -> 消息 MessageId = eventId（消费幂等参考键）                   │
│   -> VoucherOrderHandler.createOrder()（单 MySQL 事务）          │
│        SELECT event FOR UPDATE                                   │
│          CONSUMED       → 幂等返回                                │
│          ROLLED_BACK    → 拒绝迟到消息                            │
│          ROLLBACK_EXEC  → 稍后重试（回滚中抢同券库存不安全）         │
│          已有订单       → markConsumed 收敛                       │
│        stock > 0 条件扣减 MySQL 库存                              │
│        INSERT voucher_order（UNIQUE(user_id,voucher_id) 兜底）     │
│        markConsumed → 事件 = 终局                                 │
│                                                                 │
│ 消费异常：临时故障有限次分类重试；永久错误 → 先持久化失败单 + 拒消息  │
│             → 进 DLQ（SeckillOrderDeadLetterConsumer 补 x-death） │
└────────────────────────────────────────────────────────────────┘

┌──────────────────────  ⑤ 回滚 / 对账 / 死信 兜底  ─────────────┐
│ SeckillReservationRollbackTask（每 5s 扫 ROLLBACK_PENDING）     │
│   -> ① SELECT orderId 确认没有订单（防双扣：最后一道防线）          │
│   -> ② CAS claimForRollback → ROLLBACK_EXECUTING                │
│   -> ③ seckill_rollback.lua 按 eventId 幂等还库存                │
│        成功(1/0) → markRolledBack (带 lease_token fencing)       │
│        失败(-1/-2/异常) → revertWithBackoff                      │
│          退避序列 [5s→30s→300s→1800s]，超过 4 次                  │
│          → 事务内 MANUAL_REVIEW + SOURCE_ROLLBACK 工单           │
│                                                                 │
│ SeckillOrderReconciliationTask（孤儿预留对账）                    │
│   最近 7 天券：超过 30 分钟预留快扫 + 全量券分页每小时兜底          │
│   用 reservation:order Hash 反向定位 event                        │
│   → 判 ROLLBACK（Lua 还）或 MANUAL_REVIEW（SOURCE_ORPHAN）       │
│                                                                 │
│ SeckillOrderDeadLetterConsumer（死信）                            │
│   只补 x-death 证据，不做自动重放或回滚（避免决策分叉）              │
└────────────────────────────────────────────────────────────────┘
```

---

## 九大可靠性设计点 + 硬数字

### ① Lua 预占不只是扣库存：写入可恢复账本

全部使用 `{voucherId}` Hash Tag，避免 Redis Cluster CROSSSLOT。

| Key | 结构 | 作用 |
|---|---|---|
| `seckill:stock:{voucherId}` | String | Redis 可售库存 |
| `seckill:order:{voucherId}` | Set | 一人一单（Lua 秒级判重） |
| `seckill:reservation:{voucherId}` | Hash | eventId → orderId\|userId\|version（Redis 能反向重建 MySQL 事件）|
| `seckill:reservation:user:{voucherId}` | Hash | userId → eventId |
| `seckill:reservation:pending:{voucherId}` | ZSet | 快扫对账入口 |
| `seckill:reservation:order:{voucherId}` | Hash | orderId → eventId（状态查询接口反向定位）|
| `seckill:reservation:manual:{voucherId}` | ZSet | 超出自动对账范围的人工预留 |

### ② Outbox：请求线程不直连 MQ，把"应该发"存成待办

`tb_seckill_order_event` = Outbox 表。HTTP 线程写 PENDING + 返回"受理"；发 MQ 是后台任务的事。

### ③ 租约抢占 + 栅栏 token：多实例只有一个能执行

- `claimLease()`：单条 UPDATE 携带 `WHERE 状态合法 + next_retry_time 到期 + lease 空或过期` = MySQL InnoDB 行锁 CAS
- `lease_token`：每次抢占自增 1；后续 `markConfirmed / markRolledBack / revertWithBackoff` 全部带 `lease_token` 做 fencing

### ④ 每次发送独立 attempt 证据链

- `eventId`（业务事件级别，Outbox 一行）vs `attemptId`（真实发送一次，CorrelationData.id=attemptId，能精确定位到"第几次发送的 Confirm 回来了没有"）
- `confirm_status` 单向状态机：WAITING → ACK / NACK / UNKNOWN，只迁移一次

### ⑤ 三入口落证据，互不覆盖

| 方法 | UPDATE 条件 | 触发场景 |
|---|---|---|
| recordAck / recordNack | WHERE confirm_status = WAITING | Broker ACK / NACK 到达 |
| recordUnknown（3 处） | WHERE confirm_status = WAITING | 同步异常 / 30s 超时 / Future 异常 |
| recordReturned | 无 WAITING 限制 | ACK 帧和 Return 帧是两独立异步通道，都能写 returned=true |
| recordLateConfirm | WHERE confirm_status IN (UNKNOWN, NACK) | 迟到结果：不改主状态，只在旁路 late_confirm 字段留痕 |

### ⑥ Spring SettableListenableFuture：回调注册与结果完成顺序无关

- `getFuture().addCallback()`：**非阻塞**，发送线程立即返回
- Confirm 先到、回调注册后到也不丢（SettableFuture 内部 done=true 时，addCallback 会立刻同步执行回调）
- 锁 + done 标志保证回调恰好执行一次（注册后完成 / 完成后注册两种顺序都正确）

### ⑦ 决策机构 decide()：五大裁决优先级

```
1. MySQL 订单存在        → MARK_CONSUMED（其实成功了）
2. 证据矛盾（声称已消费但无订单等）→ MANUAL_REVIEW
3. 存在可能投递 attempt  →  按 Trigger：
                            RETRY_SIGNALLED(超时/异常) → RETRY_PUBLISH（主动推进补偿）
                            CONFIRM_COMPLETED(NACK)   → WAIT（等其他悬着结果自己收）
4. 全部 attempt 均明确失败 (NACK or Returned) 且无订单 → ROLLBACK（唯一允许自动回滚的情况）
5. 兜底                 → MANUAL_REVIEW
```

**铁律：只要有一次 attempt 属于 ACK / WAITING / UNKNOWN 且没退回，绝不自动回滚。** 理由：UNKNOWN 可能是"消息真的进了 Broker，只是 Confirm 回程丢了"，立即回滚 = 双扣。

### ⑧ 8 次发送（首 + 7 补偿）+ 90 秒终局窗口

```
 第1次 首发送
  └─ 1s 退避
 第2次
  └─ 2s 退避
 第3次
  └─ 4s 退避
 第4次
  └─ 30s 退避
 第5次
  └─ 120s 退避
 第6次
  └─ 600s 退避
 第7次（补偿）
  └─ 1800s 退避
 第8次（最后一次自动发送）
  └─ 90 秒终局等待窗口（给 Confirm + 消费侧收敛时间）
           无进展 → 事务内 MANUAL_REVIEW + SOURCE_PUBLISH 工单（人工工作台可见）
```

### ⑨ 4 次回滚 + 双路径对账兜底

- 回滚退避序列：`5s → 30s → 300s → 1800s`（第 4 次耗尽 → MANUAL_REVIEW + SOURCE_ROLLBACK）
- 对账：7 天券快扫（超 30 分钟孤儿预留）+ 全量券分页兜底（每小时）
- 死信：只补证据不做决策（决策只允许走 Service）
- 消费端：临时故障限次重试、永久错误先持久化再进 DLQ

---

## 秒杀决策机构（统一裁决）

### 触发入口（5 个调用方 → 2 类 Trigger）

```
evaluateAfterConfirm (Trigger = CONFIRM_COMPLETED)
  ├─ NACK 回调到达           SeckillPublishConfirmHandler:L83
  └─ ACK + Returned 回调     SeckillPublishConfirmHandler:L106

evaluateForRetry (Trigger = RETRY_SIGNALLED)
  ├─ 30s Confirm 超时扫描    SeckillPublishConfirmTimeoutTask:L120
  ├─ Future 回调异常         SeckillPublishConfirmHandler:L148
  └─ send() 同步异常         SeckillOrderPublishRetryTask:L261
```

### Decision → 后续动作

| Decision | 动作 |
|---|---|
| `MARK_CONSUMED` | markConsumed：事件 = CONSUMED，终局 |
| `RETRY_PUBLISH` | schedulePublishRetry：事件标 PUBLISH_UNKNOWN + next_retry_time 按退避推迟 |
| `ROLLBACK` | markRollbackPending：事件标 ROLLBACK_PENDING + next_retry_time=NOW（立刻进回滚队列）|
| `MANUAL_REVIEW` | 同一事务内：status=MANUAL_REVIEW + INSERT seckill_failure_case（SOURCE=PUBLISH/ROLLBACK/CONSUME/ORPHAN），人工工作台可查 |
| `WAIT` | 不做动作，等其他机制（Confirm 回调、消费落单、超时扫描）自行收敛 |

---

## 核心表结构

### 秒杀可靠性表（面试主表，4 张）

| 表 | 关键字段 | 作用 |
|---|---|---|
| `tb_seckill_order_event` | status, next_retry_time, retry_count, lease_owner/until/token, row_version, last_error_code/message | Outbox 事件表 + 主状态机 |
| `tb_seckill_publish_attempt` | event_id, attempt_no, confirm_status(WAITING/ACK/NACK/UNKNOWN), returned, late_confirm_status/reason, error_message, create_time/update_time | 每次发送完整证据：主状态（CAS 一次性迁移）+ 旁路字段 |
| `tb_seckill_failure_case` | id, source (PUBLISH/ROLLBACK/CONSUME/ORPHAN), error_code, event_id/voucher_id/order_id/user_id, status (OPEN/RESOLVED/CLOSED), assignee, evidence_json | 人工工作台工单（四类来源） |
| `tb_seckill_failure_audit` | case_id, action_type (重放/回滚/关闭), operator, before/after_json | 人工操作审计链 |

### 点评业务表（9 张）

`tb_user / tb_user_info / tb_shop / tb_shop_type / tb_blog / tb_blog_comments / tb_follow / tb_voucher / tb_seckill_voucher / tb_voucher_order`（其中订单表 `(user_id, voucher_id)` 联合唯一索引做一人一单的数据库兜底）。

---

## 技术栈

| 技术 | 版本 | 用途 |
|---|---|---|
| Java | 8 | 开发语言 |
| Spring Boot | 2.3.12.RELEASE | 容器、@Scheduled、配置 |
| Spring AMQP / RabbitMQ | 2.2.18 | Publisher Confirm / Return、Consumer、DLX + DLQ |
| MyBatis-Plus | 3.4.3 | ORM、条件构造器 UPDATE（CAS 挡板核心）、分页 |
| Spring Data Redis + Lettuce | Boot 管理 | 6 个 Lua 脚本 + Hash Tag Cluster 兼容 |
| Hutool | 5.7.17 | JSON、字符串、Bean 工具 |
| MySQL + Connector | 5.6+ / 5.1.47 | 业务库 + Outbox + 订单 + 工单 |
| Redis | 5+ | 会话/缓存/Feed/GEO/签到/预占账本 |
| RabbitMQ Broker | 环境提供 | 主交换器、主队列、DLX、DLQ |
| Vue 2 + Element UI + Axios | 静态页面 | 前端展示 |
| Nginx | 环境提供 | 静态资源 + `/api/*` 反向代理 |

---

## 快速开始

### 1. 环境要求

JDK 8+ / Maven 3.6+ / MySQL 5.6+ / Redis 5+ / RabbitMQ（启用秒杀链路时需要）

### 2. 初始化数据库

```bash
mysql -u <user> -p < src/main/resources/db/dish_review.sql
# 增量脚本（必须顺序执行，不会自动迁移 Flyway/Liquibase）
for f in src/main/resources/db/migration/*.sql; do
  mysql -u <user> -p dish_review < "$f"
done
```

### 3. 环境变量覆盖（不要把密码提交仓库）

```bash
export MYSQL_HOST=127.0.0.1    MYSQL_PORT=3306    MYSQL_USER=dish_review    MYSQL_PASSWORD='<pwd>'
export REDIS_HOST=127.0.0.1    REDIS_PORT=6379    REDIS_PASSWORD='<pwd>'
export RABBITMQ_HOST=127.0.0.1 RABBITMQ_PORT=5672 RABBITMQ_USERNAME=dish_review \
       RABBITMQ_PASSWORD='<pwd>' RABBITMQ_VHOST=/dish-review
# 秒杀链路默认关闭（避免测试环境误触发）
export SECKILL_RABBIT_CONSUMER_ENABLED=true
export SECKILL_TASKS_ENABLED=true
# 生产部署时再打开 prod（短信通道/管理边界按生产生效）
# export SPRING_PROFILES_ACTIVE=prod
```

### 4. 构建运行

```bash
mvn clean package -DskipTests
java -jar target/dish-review-0.0.1-SNAPSHOT.jar   # 默认 8081
curl http://localhost:8081/shop-type/list          # 验证启动
```

---

## 核心 API（四类：用户/商铺内容/关注优惠/秒杀）

统一返回 `Result<T>`。需要登录的接口请求头携带 `authorization: <token>`。

### 用户与会话

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/user/code?phone=` | 获取验证码（测试模式直接返回） |
| POST | `/user/login` | 验证码或密码登录，返回 Token |
| POST | `/user/logout` | 登出，删 Redis Token |
| POST | `/user/sign` | 当日签到（BitMap） |
| GET | `/user/sign/count` | 本月连续签到天数 |

### 商铺与内容

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/shop/{id}` | 商铺详情（穿透防缓存） |
| GET | `/shop/of/location` | 附近商铺（Redis GEO，支持按类型过滤） |
| GET | `/shop/of/name` | 按名称搜索商铺 |
| POST | `/blog` | 发布探店笔记 |
| GET | `/blog/hot` | 热门笔记 |
| PUT | `/blog/like/{id}` | 点赞/取消点赞（ZSet Top 5） |
| GET | `/blog/of/follow` | Feed 流滚动分页（推模式） |
| POST | `/blog-comments` | 新增评论（HTML 转义） |

### 关注与优惠券

| 方法 | 路径 | 说明 |
|---|---|---|
| PUT | `/follow/{id}/{isFollow}` | 关注/取关 |
| GET | `/follow/common/{id}` | 与目标用户的共同关注 |
| GET | `/voucher/list/{shopId}` | 店铺下优惠券列表（普通券 + 秒杀券）|

### 秒杀订单（面试重点）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/voucher-order/seckill/{voucherId}` | 发起秒杀 → 返回 orderId + PROCESSING（受理 ≠ 落库成功）|
| GET | `/voucher-order/status/{voucherId}/{orderId}` | **推荐**状态查询，带 voucherId 支持 Redis reservation:order 反向定位；返回 PROCESSING / SUCCESS / FAILED / MANUAL_REVIEW / NOT_FOUND / UNAVAILABLE |
| GET | `/voucher-order/status/{orderId}` | 兼容接口，缺少 voucherId 时无法过预留窗口检查 → 可能返回 UNAVAILABLE |

---

## 源码阅读顺序（分两轮：主链 + 异常）

### 第一轮 · 主链（面试 30 分钟版）

```
1. VoucherOrderServiceImpl             请求为什么只"受理"不直接发 MQ
2. SeckillOrderPublishRetryTask        Outbox 怎么选事件、CAS 怎么抢租约
3. SeckillOrderPublisher + SeckillPublishConfirmHandler
                                        send() 发消息、handleConfirm() 落证据
4. SeckillOrderFailureDecisionService  decide() 五大优先级顺序
5. SeckillReservationRollbackTask      ROLLBACK 之后两阶段 + 退避序列
6. VoucherOrderHandler                 消费事务（FOR UPDATE）如何做消费幂等
```

### 第二轮 · 异常收敛 + 失败兜底

```
SeckillPublishConfirmTimeoutTask  → 30s Confirm 超时的补偿入口
RabbitMqPublisherCallback         → 无法路由消息的 Return 证据（独立于 Confirm）
SeckillOrderReconciliationTask    → 7 天快扫 + 全量兜底，孤儿预留对账
SeckillOrderDeadLetterConsumer    → 死信补证据不自动处理
SeckillStockInitScanTask           → 安全初始化缺失的秒杀库存 Key
RabbitMqConfig                    → 拓扑、消费重试分类、异常分类、DLQ 编排
SeckillVoucherLuaExecutor + 6 Lua → 所有 Redis 操作原子保证
```

点评业务模块建议按 Controller 入手：`User → Shop/Voucher → Blog + BlogComments + Follow`，读 Controller 接口再进 Service 读 Redis/Mapper 细节，不要一开始通读所有 entity。

---

## 测试与能力边界（诚实版）

运行：`./mvnw test`（JDK 8：200 个用例，0 failure / 0 error / 0 skipped）

主要覆盖：状态机条件迁移、租约抢占、attempt CAS、发布+回滚两套退避序列、决策分类、消费幂等、回滚竞争场景、订单状态查询、DLQ 证据补录、对账孤儿预留兜底、Feed offset 位运算、文件安全（魔数/路径穿越）、XSS 转义等。

### 已验证 vs 待验收

| 已通过自动化（本地可复现） | 需真实环境验收（不在本 README 能力声明范围）|
|---|---|
| 单元测试（分支/策略/状态转换） | RabbitMQ 实机：Confirm / NACK / Return / 断链故障注入 |
| Spring 装配与 Bean | 多实例并发：租约过期衔接 + 实例重启无回灌 |
| 条件更新 / CAS / Lua 逻辑 | 并发压测容量与峰值 qps、毛刺报告 |
| 决策分类 + 退避序列 | Prometheus + Grafana 监控告警 + 告警规则 |
| 回滚竞争 / 对账一致性 | 备份恢复：MySQL/Redis/RabbitMQ 灾难演练 |
| 安全逻辑（XSS/密码/上传等） | 生产 RBAC：工单管理 Controller 审批流后开放 |

**不宣称通过百万 QPS、零消息丢失、全链路故障演练。** 面试遇到相关追问时按上表如实对接。

---

## 常见问题（点评 + 秒杀）

**Q1：商铺详情为什么默认用穿透防缓存，不用逻辑过期重建？**
A：逻辑过期依赖提前预热缓存，对秒杀券、新商铺上线场景不稳。穿透防缓存（查不到先占位空串 + 短 TTL）实现更简单、冷启动安全。逻辑过期代码仍保留（可按需切）。

**Q2：Feed 流为什么选推模式（写时扩散），不选拉模式？**
A：点评场景粉丝量远小于微博抖音量级，写时扩散的代价可控；用户打开 APP 直接查自己的 ZSet 收件箱毫秒返回，体验更好、分页天然连续、不依赖时间窗口偏移。score + offset 方案解决了同时间戳重复问题。

**Q3：秒杀接口返回成功，订单一定存在吗？**
A：不一定。返回的是"Redis 预占 + Outbox 受理成功"，订单异步落库；应调状态查询接口持续跟踪。

**Q4：Confirm 30 秒没返回，会阻塞发送线程吗？**
A：不会。addCallback 非阻塞注册。attempt 保持 WAITING，30s 之后超时扫描把它改成 UNKNOWN，进入补偿轨道安排下一次发送。

**Q5：为什么 attempt = UNKNOWN 时绝不回滚库存？**
A：UNKNOWN 含义是"确认结果未知"，可能消息其实进了 Broker 但 Confirm 回程丢了。如果此时回滚，就可能出现"订单真的落库成功 + 库存又被加回"的双扣。

**Q6：什么时候才允许自动回滚？**
A：只有决策第 4 优先级命中：事件的**全部 attempt**（通常 7 条自动尝试 + 1 条首次 = 8 条）都是明确 NACK 或 Returned（没有 ACK/WAITING/UNKNOWN），并且 MySQL 订单查询为空。这是唯一安全的回滚入口。

**Q7：重复消息 RabbitMQ Broker 会自动拒绝吗？**
A：不会，内容相同的两条消息 Broker 视为两独立投递。消费幂等靠 VoucherOrderHandler 的 SELECT FOR UPDATE 行锁 + 订单存在性检查 + `UNIQUE(user_id, voucher_id)` 数据库兜底实现。

**Q8：消费者或回滚任务实例崩溃怎么办？整个链路会卡住吗？**
A：不卡。发布侧：事件仍在 PUBLISH_UNKNOWN，next_retry_time 一到，Outbox 还会捞；回滚侧：事件仍在 ROLLBACK_PENDING，5 秒后下一轮回滚任务再捞；极端孤儿预留 30 分钟后对账任务还会扫一遍；所有路径超过重试阈值都**事务性转 MANUAL_REVIEW + 写失败工单**，保证自动链路停手后人工工作台一定能查到，不会有"消失的事件"。

---

仓库当前未包含 `LICENSE`。在选定开源协议前，代码默认保留所有权利。
