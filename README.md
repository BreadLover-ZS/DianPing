# DishReview：餐饮点评与高可靠秒杀系统

DishReview 是一个基于 **Java 8、Spring Boot 2.3、MyBatis-Plus、MySQL、Redis、RabbitMQ 和 Nginx** 的餐饮点评二次开发项目。

项目包含登录会话、商铺缓存、附近商铺、探店笔记、点赞、关注 Feed、签到和优惠券等业务。当前重点是秒杀链路：它不是简单地“Redis 扣库存后发一条 MQ”，而是实现了 Redis 预占账本、MySQL Outbox、发布证据、异步回调、统一决策、消费幂等、持久化回滚、DLQ 和双向对账。

> 项目已经形成可读的秒杀可靠性主线，但不宣称经过生产流量、百万 QPS、零消息丢失或完整故障演练。本轮 JDK 8 自动化测试已通过；RabbitMQ 实机、真实数据库事务、并发和宕机恢复仍需在目标环境验收。

## 目录

- [项目概览](#项目概览)
- [系统架构](#系统架构)
- [核心业务调用链](#核心业务调用链)
- [秒杀可靠性设计](#秒杀可靠性设计)
- [源码阅读导航](#源码阅读导航)
- [技术栈](#技术栈)
- [数据模型](#数据模型)
- [快速开始](#快速开始)
- [核心配置](#核心配置)
- [核心 API](#核心-api)
- [测试与验证边界](#测试与验证边界)
- [安全设计与已知边界](#安全设计与已知边界)
- [学习与开发文档](#学习与开发文档)
- [常见问题](#常见问题)

---

## 项目概览

### 主要模块

| 模块 | 当前实现 |
|---|---|
| 登录与会话 | 手机验证码/密码登录、Redis Hash 会话、双拦截器恢复用户、30 分钟滑动续期、登出 |
| 商铺 | 详情缓存、空值防穿透、分类/名称分页、Redis GEO 附近商铺、更新后删除缓存 |
| 探店笔记 | 发布、热门列表、详情、评论、HTML 转义 |
| 点赞与关注 | ZSet 点赞和 Top5、关注/取关、共同关注 |
| Feed 流 | 发布时推送粉丝收件箱，使用 `score + offset` 滚动分页 |
| 签到 | Redis BitMap 按月签到，位运算统计连续签到 |
| 优惠券 | 普通券、秒杀券、活动时间窗与库存管理 |
| 秒杀订单 | Redis Lua 预占、Outbox 发布、RabbitMQ 消费、状态查询、回滚、对账和失败处置 |
| 文件上传 | 图片扩展名、大小与文件魔数校验；按用户目录隔离删除权限 |

### 当前能力边界

| 项目 | 状态 |
|---|---|
| 秒杀可靠性代码、表结构与任务 | 已实现 |
| 单元测试与 Spring 装配测试 | JDK 8 下 200 个测试通过，0 failure、0 error、0 skipped |
| RabbitMQ 消费者 | 默认关闭，需显式设置环境变量开启 |
| 真实 RabbitMQ 故障注入 | 尚不能写成已验收能力 |
| 高并发压测与容量结论 | 尚无可复现报告 |
| 失败工单人工处置 | Service 和审计已实现；Controller 因缺少 RBAC 暂未开放 |
| 生产短信 | 未接入真实供应商；生产模式缺少通道时拒绝伪造成功 |
| 商铺逻辑过期缓存 | 保留实现供学习，当前详情查询默认使用缓存穿透方案 |

---

## 系统架构

```text
浏览器
  │
  ▼
Nginx :8080
  ├─ 静态页面
  └─ /api/* 反向代理
        │
        ▼
Spring Boot :8081
  ├─ Controller / Service / Mapper
  ├─ 登录拦截器与统一 Result
  ├─ Redis 业务编排
  ├─ RabbitMQ Producer / Consumer
  └─ 秒杀后台任务
        │
        ├──────────────┬─────────────────┐
        ▼              ▼                 ▼
      MySQL          Redis            RabbitMQ
  业务数据/Outbox   会话/缓存/Feed     主交换机/主队列
  attempt/失败单    GEO/签到/预占账本   DLX/DLQ
```

秒杀后台任务包括：

| 任务 | 作用 |
|---|---|
| `SeckillOrderPublishRetryTask` | 扫描到期 Outbox 事件，抢租约并发送 |
| `SeckillPublishConfirmTimeoutTask` | 将 30 秒仍未确认的 attempt 标为 UNKNOWN |
| `SeckillReservationRollbackTask` | 对确认可回滚的事件执行 Redis 补偿 |
| `SeckillOrderReconciliationTask` | Redis 预占与 MySQL 事件双向对账 |
| `SeckillStockInitScanTask` | 检查和安全初始化缺失的秒杀库存 Key |

---

## 核心业务调用链

### 1. 登录与会话

```text
POST /user/login
  -> UserServiceImpl.login()
  -> 校验验证码或密码
  -> 生成 Token
  -> Redis Hash 保存 UserDTO

后续请求
  -> RefreshTokenInterceptor 恢复 UserHolder 并续期
  -> LoginInterceptor 判断接口是否需要登录
  -> afterCompletion 清理 ThreadLocal
```

### 2. 商铺详情缓存

```text
GET /shop/{id}
  -> ShopServiceImpl.queryById()
  -> CacheClient.queryWithPassThrough()
     -> Redis 命中：直接返回
     -> Redis 未命中：查询 MySQL 并回填
     -> MySQL 不存在：缓存空字符串，短 TTL 防穿透
```

`queryWithLogicalExpire()` 和互斥重建代码仍保留，但不是当前商铺详情的默认入口；使用逻辑过期前需要先预热缓存。

### 3. Feed 流

```text
发布笔记
  -> BlogServiceImpl.saveBlog()
  -> 查询粉丝
  -> ZADD feed:{followerId}，score=发布时间

查看关注动态
  -> reverseRangeByScoreWithScores()
  -> 返回 list + minTime + offset
  -> 下一页继续使用 minTime/offset
```

`offset` 用来处理多条动态拥有相同毫秒时间戳时的重复或遗漏问题。

### 4. 秒杀订单主链

```text
POST /voucher-order/seckill/{voucherId}
  -> VoucherOrderServiceImpl.seckillVoucher()
  -> Redis Lua 原子预占
  -> MySQL 创建 PENDING 事件
  -> 返回 orderId，表示“已受理”

Outbox 后台任务
  -> 扫描到期事件
  -> 抢占租约
  -> 创建 WAITING attempt
  -> SeckillOrderPublisher 发送一次

RabbitMQ
  -> Confirm / Return 回调记录证据
  -> Consumer 调用 VoucherOrderHandler
  -> MySQL 事务：锁事件、扣库存、写订单、标记 CONSUMED
```

---

## 秒杀可靠性设计

### 1. Redis 不是只保存库存

秒杀 Lua 在预占成功时同时写入业务资格和可恢复账本。相关 Key 使用相同 `{voucherId}` Hash Tag，保证 Redis Cluster 下同槽执行 Lua。

| Key | 结构 | 职责 |
|---|---|---|
| `seckill:stock:{voucherId}` | String | Redis 可售库存 |
| `seckill:order:{voucherId}` | Set | 一人一单用户集合 |
| `seckill:reservation:{voucherId}` | Hash | `eventId -> orderId\|userId\|createdAt\|version`，用于重建事件 |
| `seckill:reservation:user:{voucherId}` | Hash | `userId -> eventId`，用于归属校验 |
| `seckill:reservation:pending:{voucherId}` | ZSet | `eventId -> reservedAt`，作为自动对账入口 |
| `seckill:reservation:order:{voucherId}` | Hash | `orderId -> eventId`，支持状态查询反向定位 |
| `seckill:reservation:manual:{voucherId}` | ZSet | 保存退出自动对账的异常预留 |

如果 Redis 已预占但 MySQL 事件写入失败，请求仍返回“已受理”，对账任务后续可以从详情 Hash 重建事件。入口不能立即回滚，因为调用结果和后续恢复路径仍可能存在不确定性。

### 2. Outbox：把“应该发送”保存成待办

`tb_seckill_order_event` 承担 Outbox 角色。请求线程不直接调用 RabbitMQ，而是创建 `PENDING` 事件；后台任务只选择：

- 状态为 `PENDING` 或 `PUBLISH_UNKNOWN`；
- `next_retry_time` 已到；
- 租约为空或已经过期。

候选事件按 `next_retry_time` 排序。查询只负责找候选，真正处理前还要执行数据库条件更新抢占租约。

### 3. 租约：多实例只允许一个执行者处理

抢占成功会写入：

- `lease_owner`：当前实例；
- `lease_until`：默认 60 秒后过期；
- `lease_token`：每次抢占递增的栅栏令牌。

多个实例可能同时查到同一事件，但只有一个实例的 `claimLease()` 能成功。实例崩溃后租约自动过期；旧执行者携带旧 token，不能释放或完成后来执行者的租约。

### 4. 每次发送都有独立证据

同一业务事件可以被重发，因此项目区分：

| ID | 含义 |
|---|---|
| `eventId` | 一次秒杀业务事件 |
| `attemptId` | 一次真实 MQ 发送 |
| `orderId` | 最终订单 |

发送前，`SeckillPublishAttemptService.createNextAttempt()` 在同一个 MySQL 事务中递增 `retry_count` 并插入 `WAITING` attempt。Publisher 只调用一次 `convertAndSend()`，模板内部重试关闭，避免发送次数失真。

### 5. 回调是异步的，不阻塞发送线程

```text
RabbitMQ Confirm ACK/NACK
  -> Spring 完成 CorrelationData Future
  -> SeckillPublishConfirmHandler 更新 attempt

消息无法路由
  -> Spring 调用 ReturnCallback
  -> RabbitMqPublisherCallback 记录 returned=true

30 秒仍无 Confirm
  -> 超时任务把 attempt 从 WAITING 改为 UNKNOWN
  -> 决策器把事件推进为 PUBLISH_UNKNOWN 并安排补偿发布
```

Spring 只负责触发回调，数据库更新由项目自己的 `SeckillPublishAttemptService` 完成。ACK、NACK、UNKNOWN 只允许更新仍为 `WAITING` 的 attempt，因此重复回调或超时竞争不会相互覆盖。

### 6. 决策器统一决定等待、重发还是回滚

回调只记录证据，不直接修改 Redis。`SeckillOrderFailureDecisionService` 汇总订单、事件和全部 attempt：

| 证据 | 决策 |
|---|---|
| MySQL 订单存在 | `MARK_CONSUMED` |
| 存在 ACK、WAITING、UNKNOWN 且未 Return 的 attempt | `WAIT` 或 `RETRY_PUBLISH`，禁止回滚 |
| 所有 attempt 都明确 NACK 或 Return，且没有订单 | `ROLLBACK` |
| 事件状态与订单事实矛盾 | `MANUAL_REVIEW` |

原因是“最后一次发送失败”不能证明更早的发送没有进入队列。

### 7. 八次发送与终局窗口

发布策略为：

```text
首次发送
  -> 1s -> 第2次
  -> 2s -> 第3次
  -> 4s -> 第4次
  -> 30s -> 第5次
  -> 120s -> 第6次
  -> 600s -> 第7次
  -> 1800s -> 第8次
  -> 90s 终局窗口
```

也就是首次发送加 7 次重发。第 8 次后不再创建第 9 个 attempt，而是等待 90 秒，让 Confirm、Return、30 秒超时任务或消费者事务有机会收敛；仍无进展才将事件和 `SOURCE_PUBLISH` 失败单在同一事务中转入人工处理。

### 8. 消费幂等与事务边界

`VoucherOrderHandler.createOrder()` 在一个 MySQL 事务中：

```text
SELECT event FOR UPDATE
  -> 已 CONSUMED：幂等返回
  -> 已 ROLLED_BACK：拒绝迟到消息
  -> 回滚执行中：稍后重试
  -> 必要时 CAS 取消待回滚
  -> 查询已有订单
  -> stock > 0 条件扣减 MySQL 库存
  -> 插入订单
  -> 事件改为 CONSUMED
```

RabbitMQ 默认不会拒绝内容相同的消息。项目通过事件状态、订单存在性检查和 `(user_id, voucher_id)` 唯一索引处理重复消费。Listener 正常返回后由 Spring 容器 ACK；异常则进入分类重试或 DLQ 流程。

### 9. 回滚、DLQ 与对账

- `SeckillReservationRollbackTask`：订单不存在且所有发布均明确失败后，按 `eventId` 幂等恢复 Redis 库存。
- `RabbitMqConfig`：消费临时故障有限重试；永久错误和一致性冲突不做无意义重试。
- `MessageRecoverer`：先持久化失败单和事件状态，再拒绝消息进入 DLQ；落库失败则重新入队。
- `SeckillOrderDeadLetterConsumer`：补充 `x-death` 证据，不直接回滚或自动重放。
- `SeckillOrderReconciliationTask`：每分钟扫描最近 7 天候选券中超过 30 分钟的预留；每小时游标分页遍历全部券，兜底历史孤儿预留。

---

## 源码阅读导航

### 第一次阅读：先看主链

| 顺序 | 文件 | 重点问题 |
|---|---|---|
| 1 | [`VoucherOrderServiceImpl`](src/main/java/com/dish/review/service/impl/VoucherOrderServiceImpl.java) | 请求为什么只受理，不直接发送 MQ |
| 2 | [`SeckillOrderPublishRetryTask`](src/main/java/com/dish/review/mq/SeckillOrderPublishRetryTask.java) | 哪些事件会被选择，如何抢租约 |
| 3 | [`SeckillOrderEventService`](src/main/java/com/dish/review/service/SeckillOrderEventService.java) | 状态条件更新、扫描、租约与 token |
| 4 | [`SeckillOrderPublisher`](src/main/java/com/dish/review/mq/SeckillOrderPublisher.java) | 哪个模块真正发送消息 |
| 5 | [`SeckillPublishConfirmHandler`](src/main/java/com/dish/review/mq/SeckillPublishConfirmHandler.java) | ACK/NACK 如何更新 attempt |
| 6 | [`SeckillOrderFailureDecisionService`](src/main/java/com/dish/review/service/SeckillOrderFailureDecisionService.java) | 为什么 UNKNOWN 不能直接回滚 |
| 7 | [`VoucherOrderHandler`](src/main/java/com/dish/review/service/VoucherOrderHandler.java) | 消费事务和幂等如何实现 |

### 第二次阅读：看异常收敛

| 文件 | 职责 |
|---|---|
| [`SeckillPublishConfirmTimeoutTask`](src/main/java/com/dish/review/mq/SeckillPublishConfirmTimeoutTask.java) | Confirm 长时间未返回的补偿入口 |
| [`RabbitMqPublisherCallback`](src/main/java/com/dish/review/mq/RabbitMqPublisherCallback.java) | 无法路由消息的 Return 证据 |
| [`SeckillReservationRollbackTask`](src/main/java/com/dish/review/mq/SeckillReservationRollbackTask.java) | Redis 预占持久化回滚 |
| [`SeckillOrderReconciliationTask`](src/main/java/com/dish/review/mq/SeckillOrderReconciliationTask.java) | Redis/MySQL 双向对账 |
| [`RabbitMqConfig`](src/main/java/com/dish/review/config/RabbitMqConfig.java) | 拓扑、消费重试、异常分类和 DLQ |

其他业务建议从 Controller 进入，沿 `Controller -> Service -> Mapper/Redis` 阅读，不要先通读全部实体和工具类。

---

## 技术栈

| 技术 | 项目版本/来源 | 用途 |
|---|---|---|
| Java | 8 | 开发语言 |
| Spring Boot | 2.3.12.RELEASE | Web、配置和容器 |
| Spring AMQP | 由 Spring Boot 管理 | RabbitMQ 发布、Confirm/Return、消费和 DLQ |
| MyBatis-Plus | 3.4.3 | ORM、条件更新和分页 |
| MySQL Connector | 5.1.47 | MySQL 驱动 |
| Spring Data Redis | 由 Spring Boot 管理 | RedisTemplate、Lettuce 和连接池 |
| Hutool | 5.7.17 | JSON、字符串和常用工具 |
| Vue 2 / Element UI / Axios | 静态页面 | 前端展示和接口调用 |
| Nginx | 外部安装 | 静态资源与 `/api` 反向代理 |

RabbitMQ Broker、MySQL、Redis 和 Nginx 由运行环境提供，仓库未包含 Docker Compose，也没有固定 Broker 版本。

---

## 数据模型

初始化脚本：[dish_review.sql](src/main/resources/db/dish_review.sql)。可靠性升级位于 [db/migration](src/main/resources/db/migration)。

### 核心业务表

| 表 | 作用 |
|---|---|
| `tb_user` / `tb_user_info` | 用户登录与资料 |
| `tb_shop` / `tb_shop_type` | 商铺与分类 |
| `tb_blog` / `tb_blog_comments` | 探店笔记与评论 |
| `tb_follow` | 关注关系 |
| `tb_voucher` / `tb_seckill_voucher` | 优惠券和秒杀库存 |
| `tb_voucher_order` | 秒杀订单；联合唯一索引保证一人一单 |

### 秒杀可靠性表

| 表 | 作用 |
|---|---|
| `tb_seckill_order_event` | Outbox 事件和主状态机 |
| `tb_seckill_publish_attempt` | 每次发送的 Confirm/Return/UNKNOWN 证据 |
| `tb_seckill_failure_case` | 发布、消费、回滚和对账失败单 |
| `tb_seckill_failure_audit` | 人工重放、回滚和关闭操作审计 |

事件普通状态推进使用“允许来源状态”作为更新条件，并递增 `row_version`。当前代码没有在 `WHERE` 中比较旧 `row_version`，因此不要把它描述成基于版本号的乐观锁；回滚完成等关键操作另有 `lease_token` 栅栏条件。

---

## 快速开始

### 1. 环境要求

- JDK 8+
- Maven 3.6+
- MySQL 5.6+
- Redis 5+
- RabbitMQ（运行秒杀异步链路时需要）
- Nginx（可选，只启动后端时不需要）

### 2. 初始化数据库

全新数据库先执行：

```bash
mysql -u <user> -p < src/main/resources/db/dish_review.sql
```

基础脚本只负责演示数据和基础表结构；秒杀可靠性、角色、迟到 Confirm 证据和查询索引仍需继续按下面顺序执行迁移。

已有数据库按顺序检查并执行增量迁移：

```bash
mysql -u <user> -p dish_review < src/main/resources/db/migration/20260803_add_follow_indexes.sql
mysql -u <user> -p dish_review < src/main/resources/db/migration/20260819_add_voucher_order_unique_index.sql
mysql -u <user> -p dish_review < src/main/resources/db/migration/20260820_add_seckill_order_event.sql
mysql -u <user> -p dish_review < src/main/resources/db/migration/20260821_seckill_reliability_upgrade.sql
mysql -u <user> -p dish_review < src/main/resources/db/migration/20260822_add_seckill_failure_audit.sql
mysql -u <user> -p dish_review < src/main/resources/db/migration/20260823_add_user_role.sql
mysql -u <user> -p dish_review < src/main/resources/db/migration/20260823_add_late_confirm_evidence.sql
mysql -u <user> -p dish_review < src/main/resources/db/migration/20260823_add_query_indexes.sql
```

项目没有集成 Flyway/Liquibase，不会自动执行这些脚本。生产或已有数据环境必须先备份并审查迁移中的前置检查。

角色迁移默认把历史用户设为 `USER`；只有确认操作者身份后，才执行迁移脚本末尾的管理员更新示例。管理写接口包括商铺和优惠券新增/修改，普通用户只保留查询权限。

### 3. 配置中间件

不要把真实密码提交进仓库。建议通过环境变量覆盖：

```bash
export MYSQL_HOST=127.0.0.1
export MYSQL_PORT=3306
export MYSQL_USER=dish_review
export MYSQL_PASSWORD='<your-password>'

export REDIS_HOST=127.0.0.1
export REDIS_PORT=6379
export REDIS_PASSWORD='<your-password>'

export RABBITMQ_HOST=127.0.0.1
export RABBITMQ_PORT=5672
export RABBITMQ_USERNAME=dish_review
export RABBITMQ_PASSWORD='<your-password>'
export RABBITMQ_VHOST=/dish-review

# 生产部署必须显式启用生产配置，短信和管理端点边界才会按生产值生效
export SPRING_PROFILES_ACTIVE=prod
```

RabbitMQ 拓扑由 `RabbitMqConfig` 声明，包括持久化 DirectExchange、主队列、DLX 和 DLQ。

### 4. 启动后端

```bash
mvn clean package -DskipTests
java -jar target/dish-review-0.0.1-SNAPSHOT.jar
```

默认端口为 `8081`。可先验证公开接口：

```bash
curl http://localhost:8081/shop-type/list
```

### 5. 开启秒杀消费者

消费者默认关闭。RabbitMQ 连接和拓扑确认后再设置：

```bash
export SECKILL_RABBIT_CONSUMER_ENABLED=true
```

重新启动应用后，`SeckillOrderConsumer` 才会监听主队列。秒杀后台任务默认关闭，确认依赖和配置后再设置 `SECKILL_TASKS_ENABLED=true` 统一开启。

### 6. 启动前端

将 [nginx.conf](nginx/conf/nginx.conf) 中的静态目录改为本机路径，启动 Nginx 后访问 `http://localhost:8080`。Nginx 会把 `/api` 转发到 `127.0.0.1:8081`。

---

## 核心配置

| 环境变量 | 作用 |
|---|---|
| `MYSQL_HOST/PORT/USER/PASSWORD` | MySQL 连接 |
| `REDIS_HOST/PORT/PASSWORD` | Redis 连接 |
| `RABBITMQ_HOST/PORT/USERNAME/PASSWORD/VHOST` | RabbitMQ 连接 |
| `SECKILL_RABBIT_CONSUMER_ENABLED` | 是否启动秒杀主队列消费者 |
| `SECKILL_TASKS_ENABLED` | 是否启动 Outbox、确认超时、回滚、对账和库存扫描任务，默认 false |
| `IMAGE_UPLOAD_DIR` | 图片存储根目录，默认 `./data/images` |
| `SECKILL_OUTBOX_BATCH_SIZE` | Outbox 每批候选事件数，默认 20 |
| `SECKILL_OUTBOX_LEASE_SECONDS` | 发布租约时长，默认 60 秒 |
| `SECKILL_CONFIRM_TIMEOUT_SECONDS` | Confirm 超时，默认 30 秒 |
| `SECKILL_RECONCILE_RESERVATION_THRESHOLD_MINUTES` | 孤儿预留检查阈值，默认 30 分钟 |

完整配置见 [application.yaml](src/main/resources/application.yaml)。仓库配置包含开发默认值，部署时应全部通过环境变量或密钥管理系统覆盖。

---

## 核心 API

请求统一返回 `Result`。除登录、验证码和部分公开查询外，请求头需要携带：

```http
authorization: <token>
```

### 用户

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/user/code?phone=` | 获取验证码；测试模式直接返回验证码 |
| POST | `/user/login` | 验证码或密码登录 |
| POST | `/user/logout` | 登出并删除 Redis Token |
| GET | `/user/me` | 当前用户 |
| POST | `/user/sign` | 当日签到 |
| GET | `/user/sign/count` | 本月连续签到天数 |

### 商铺与内容

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/shop/{id}` | 商铺详情 |
| GET | `/shop/of/type` | 按类型分页 |
| GET | `/shop/of/location` | 按类型查询附近商铺；传入经纬度时走 Redis GEO |
| GET | `/shop/of/name` | 按名称搜索 |
| GET | `/shop-type/list` | 商铺分类 |
| POST | `/blog` | 发布探店笔记 |
| GET | `/blog/hot` | 热门笔记 |
| PUT | `/blog/like/{id}` | 点赞/取消点赞 |
| GET | `/blog/of/follow` | 关注 Feed 滚动分页 |
| POST | `/blog-comments` | 新增评论 |

### 关注与优惠券

| 方法 | 路径 | 说明 |
|---|---|---|
| PUT | `/follow/{id}/{isFollow}` | 关注或取消关注 |
| GET | `/follow/common/{id}` | 共同关注 |
| POST | `/voucher` | 新增普通券 |
| POST | `/voucher/seckill` | 新增秒杀券 |
| GET | `/voucher/list/{shopId}` | 查询店铺优惠券 |

### 秒杀订单

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/voucher-order/seckill/{voucherId}` | 发起秒杀，返回异步受理结果 |
| GET | `/voucher-order/status/{voucherId}/{orderId}` | 推荐状态查询，可定位 Redis 预留 |
| GET | `/voucher-order/status/{orderId}` | 兼容接口；无法排除预留窗口时返回 `UNAVAILABLE` |

返回 `orderId` 不等于订单已落库。调用方应继续查询 `PROCESSING`、`SUCCESS`、`FAILED`、`MANUAL_REVIEW`、`NOT_FOUND` 或 `UNAVAILABLE`。

---

## 测试与验证边界

运行测试：

```bash
./mvnw test
```

Windows 使用 `mvnw.cmd test`。Wrapper 固定 Maven 3.8.8，首次运行需要联网下载该版本。

仓库测试主要覆盖：

- Redis Key、Feed offset、签到位运算和 DTO 等纯逻辑；
- 文件路径、XSS、验证码和密码等安全逻辑；
- 事件状态机、租约、attempt、发布决策和退避策略；
- 消费事务幂等、回滚竞争和订单状态查询；
- 回滚任务、双向对账、库存初始化和异常预留移交人工；
- Listener 前错误、消费失败证据、DLQ 和失败工单管理。

验证结论必须分层：

| 层级 | 能证明什么 |
|---|---|
| 编译/单元测试 | 方法分支、状态转换和策略符合预期 |
| Spring 上下文测试 | Bean 与基本配置能够装配 |
| MySQL/Redis 集成测试 | 真实 SQL、Lua 和事务行为 |
| RabbitMQ 实机测试 | Confirm、Return、重试、DLQ 和重连 |
| 并发与故障演练 | 重复投递、多实例竞争、宕机窗口和恢复能力 |

当前仓库不能仅凭 README 或单元测试声称后两层已经通过。

---

## 安全设计与已知边界

### 已实现

- Token 会话滑动续期与登出失效；
- `ThreadLocal` 请求结束清理；
- BCrypt 密码与旧 MD5 渐进升级；验证码 Lua 原子校验和发送频率限制；
- 对外使用不含手机号、密码的 `UserDTO`；
- 笔记标题和内容 HTML 转义；
- 上传扩展名/大小/魔数限制、删除路径穿越和用户目录所有权检查；
- 商铺和优惠券写接口需要 ADMIN；
- Nginx 安全响应头模板。

### 仍需改进

- 真实短信供应商、对象存储和恶意内容扫描尚未接入；
- 失败工单 Controller 需要 RBAC 和审批后才能开放；
- RabbitMQ 管理端口和 AMQP 端口不应直接暴露公网；
- 项目尚未提供正式 CI、容器编排、完整故障演练和容量报告。

---

## 学习与开发文档

| 文档 | 用途 |
|---|---|
| [00-07 项目学习总结](docs/learning/00-07-project-learning-summary.md) | 从数据库、登录、缓存、Feed、GEO 等模块理解项目 |
| [08 面试与二次开发指南](docs/learning/08-interview-and-secondary-development-guide.md) | 项目事实、面试问题和后续改造入口 |
| [09 RabbitMQ 秒杀源码阅读手册](docs/learning/09-rabbitmq-seckill-flow.md) | 按决策、回调、租约、消费、回滚和对账阅读 MQ 源码 |
| [10 秒杀可靠性开发规格](docs/development/10-rabbitmq-seckill-reliability-development-spec.md) | 状态、表结构和实现约束 |
| [11 秒杀可靠性交付报告](docs/development/11-rabbitmq-seckill-reliability-delivery-report.md) | 已交付内容、验证证据和剩余边界 |
| [12 项目全面审查与改进路线](docs/development/12-project-comprehensive-review-and-improvement-roadmap.md) | 面试向审查结论、优先级和后续验收清单 |

推荐顺序：先读 README 建立全局结构，再读 `09` 沿源码理解 MQ，最后用 `08` 做面试复盘。

---

## 常见问题

### 秒杀接口返回成功，订单一定存在吗？

不一定。接口返回的是“Redis 预占成功、请求已受理”。订单由 RabbitMQ 消费者异步写入 MySQL，应继续调用状态查询接口。

### 相同消息重复发送会被 RabbitMQ 拒绝吗？

不会。RabbitMQ 默认把它们视为两条消息。项目通过事件状态、订单查询和数据库唯一索引保证消费幂等。

### Confirm 30 秒没有返回，会阻塞线程吗？

不会。attempt 保持 `WAITING`，超时任务把它改为 `UNKNOWN`，事件进入 `PUBLISH_UNKNOWN` 并安排补偿发布。

### 为什么 UNKNOWN 不能立即回滚库存？

因为消息可能已经进入 Broker，只是 Confirm 丢失。立即回滚可能导致“订单成功但库存又被加回”。

### 为什么启动后没有消费秒杀消息？

确认 RabbitMQ 可达，并设置 `SECKILL_RABBIT_CONSUMER_ENABLED=true` 后重启应用。

### 项目可以直接部署到生产环境吗？

不建议。它适合作为学习、面试和二次开发基础；正式上线前仍需要密钥治理、RBAC、真实中间件验收、监控告警、备份恢复、压测和故障演练。

---

仓库当前未包含 `LICENSE`。在选择开源协议前，代码默认保留所有权利。
