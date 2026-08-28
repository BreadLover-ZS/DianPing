# 高可靠优惠券秒杀系统（餐饮点评承载模块）

DianPing 是基于 **Java 8 + Spring Boot 2.3 + MyBatis-Plus + MySQL + Redis + RabbitMQ + Nginx** 的后端项目。它以餐饮点评业务为脚手架（登录会话、商铺缓存、探店笔记、Feed、签到），**核心能力是一条经过系统化可靠性设计的优惠券秒杀异步链路**：不是"Redis 扣库存再发一条 MQ"，而是完整实现了 Lua 预占账本、Outbox、独立尝试证据、异步 Confirm/Return、统一决策机构、消费幂等、持久化回滚、DLQ 和双向对账。

> 当前 JDK 8 自动化测试 200 个全部通过；RabbitMQ 实机、宕机恢复、真实并发与压测结论尚未形成可复现报告。README 中所有描述均以代码和测试为准，不做超出范围的可靠性承诺。

---

## 目录

- [秒杀全流程](#秒杀全流程)
- [九大可靠性设计点 + 硬指标](#九大可靠性设计点--硬指标)
- [秒杀决策机构（统一裁决）](#秒杀决策机构统一裁决)
- [核心表结构](#核心表结构)
- [后台定时任务一览](#后台定时任务一览)
- [其他业务模块（概览一句话）](#其他业务模块概览一句话)
- [技术栈](#技术栈)
- [快速开始](#快速开始)
- [核心秒杀 API](#核心秒杀-api)
- [源码阅读顺序](#源码阅读顺序)
- [测试与能力边界](#测试与能力边界)
- [常见问题](#常见问题)

---

## 秒杀全流程

```
┌─────────────────────  1. 用户下单入口  ─────────────────────────┐
│ POST /voucher-order/seckill/{voucherId}                         │
│   -> VoucherOrderServiceImpl.seckillVoucher()                   │
│   -> Lua 原子校验（库存>0 + 一人一单 + 活动时间窗）                │
│   -> 写 Redis 预占账本（{voucherId} Hash Tag，集群同槽）         │
│   -> MySQL 事务写 Outbox：status=PENDING（受理）                 │
│   -> 返回 orderId + PROCESSING（不保证已落库）                    │
└────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────  2. 发布补偿（Outbox） ────────────────────┐
│ SeckillOrderPublishRetryTask（每 1s 扫）                         │
│   条件：status IN (PENDING, PUBLISH_UNKNOWN)                    │
│      AND next_retry_time <= NOW()                               │
│      AND 租约空或过期                                            │
│   -> CAS claimLease（lease_token++ 栅栏）                        │
│   -> retry_count++ + createNextAttempt(WAITING)                 │
│   -> SeckillOrderPublisher.send() 只调用一次 convertAndSend      │
│   -> attach Future 回调                                          │
│   -> deferNextRetry：退避 [1s 2s 4s 30s 2m 10m 30m] 或 90s 终局  │
│                                                                 │
│ 同步异常分支：                                                     │
│   -> recordUnknown(send_exception)                               │
│   -> 决策机构 evaluateForRetry → RETRY_PUBLISH 或转人工          │
│                                                                 │
│ Confirm 超时分支：                                                 │
│   SeckillPublishConfirmTimeoutTask（每 5s）                       │
│     -> attempt WAITING 超 30s → recordUnknown(confirm_timeout)   │
│     -> 决策机构 evaluateForRetry                                 │
└────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────  3. 回调证据 + 决策机构 ──────────────────┐
│ Confirm/Return 到达 / Future 异常                                 │
│   -> SeckillPublishConfirmHandler / RabbitMqPublisherCallback    │
│   -> recordAck / recordNack / recordReturned / recordUnknown     │
│      （WAITING 条件 CAS：谁先到谁生效，迟到记 late_confirm）       │
│   -> 判 RETRY_PUBLISH / ROLLBACK / MANUAL_REVIEW / WAIT / CONSUMED│
└────────────────────────────────────────────────────────────────┘
                              │
             ┌────────────────┼────────────────┐
             ▼                ▼                ▼
    ┌──────────────┐  ┌──────────────┐  ┌──────────────┐
    │ RETRY_PUBLISH│  │  ROLLBACK    │  │ MANUAL_REVIEW│
    │ 退避→Outbox  │  │ 回滚任务     │  │ SOURCE_PUBLISH│
    └──────────────┘  └──────────────┘  │ 失败记录+    │
                                        │ 人工工作台    │
                                        └──────────────┘
                              │
                              ▼
┌─────────────────────  4. 消费侧事务落单  ──────────────────────┐
│ SeckillOrderConsumer 收到消息（MessageId=eventId 做幂等）         │
│   -> VoucherOrderHandler.createOrder()（单 MySQL 事务）          │
│     SELECT event FOR UPDATE                                      │
│       CONSUMED       → 幂等返回                                   │
│       ROLLED_BACK    → 拒绝迟到消息                               │
│       ROLLBACK_EXEC  → 稍后重试                                   │
│       已有订单       → markConsumed 收敛                          │
│     stock>0 条件扣 MySQL 库存                                     │
│     INSERT voucher_order（UNIQUE(user_id,voucher_id)）            │
│     markConsumed → 事件彻底结束                                    │
│                                                                 │
│ 消费异常：分类重试有限次数；永久错误 → 持久化失败单 → 进 DLQ         │
└────────────────────────────────────────────────────────────────┘

┌─────────────────────  5. 回滚 / 对账 / 死信兜底  ─────────────┐
│ SeckillReservationRollbackTask（每 5s 扫 ROLLBACK_PENDING）     │
│   -> 核对订单不存在 → CAS claimForRollback → ROLLBACK_EXECUTING │
│   -> Lua 按 eventId 幂等还库存                                   │
│   成功 → ROLLED_BACK；失败 → 退避 [5s 30s 5m 30m]                │
│   超过 4 次 → MANUAL_REVIEW（SOURCE_ROLLBACK）                   │
│                                                                 │
│ SeckillOrderReconciliationTask（孤儿预留对账）                    │
│   -> 最近 7 天券：30 分钟阈值快扫 + 全量券分页兜底                │
│   -> orderId 反向索引重建事件 → 判 ROLLBACK / MANUAL_REVIEW       │
│                                                                 │
│ SeckillOrderDeadLetterConsumer（死信）                            │
│   -> 补充 x-death 证据 → 决策机构（不自动重放）                    │
└────────────────────────────────────────────────────────────────┘
```

---

## 九大可靠性设计点 + 硬指标

### ① Lua 预占不是只扣库存：写入可恢复账本

相关 Key 全部使用 `{voucherId}` Hash Tag，避免 Redis Cluster 的 CROSSSLOT。

| Key | 结构 | 作用 |
|---|---|---|
| `seckill:stock:{voucherId}` | String | Redis 可售库存 |
| `seckill:order:{voucherId}` | Set | 一人一单 |
| `seckill:reservation:{voucherId}` | Hash | `eventId -> orderId\|userId\|createdAt\|version`，可从 Redis 反向重建 MySQL 事件 |
| `seckill:reservation:user:{voucherId}` | Hash | `userId -> eventId` |
| `seckill:reservation:pending:{voucherId}` | ZSet | 对账入口 |
| `seckill:reservation:order:{voucherId}` | Hash | `orderId -> eventId`，状态查询反向定位 |
| `seckill:reservation:manual:{voucherId}` | ZSet | 退出自动对账的异常预留 |

### ② Outbox：把"应该发"存成待办，请求线程不直接连 MQ

`tb_seckill_order_event` 承担 Outbox 角色。请求线程只写 `PENDING` 就返回；真正发 MQ 由后台任务做。

### ③ 租约抢占 + 栅栏 token：多实例只一个能执行

- `claimLease()`：带状态 + 租约过期条件的 UPDATE（原子 CAS）。多实例并发抢，只有一条命 1 行
- `lease_token`：每次抢占自增，后续 `markConfirmed / markRolledBack` 都要带 token 做 fencing。过期执行者再回来 UPDATE 命中 0 行自动失效

### ④ 每次发送独立 attempt：eventId ≠ attemptId

- `eventId`：一次秒杀业务事件（Outbox 一行）
- `attemptId`：一次真实 `convertAndSend` 调用；`CorrelationData.id=attemptId`，能精确定位"第几次发送的确认"
- attempt 的 `confirm_status` 是一次性迁移：WAITING → ACK / NACK / UNKNOWN

### ⑤ Confirm / Return / 超时扫描 三入口落证据，互不覆盖

| 方法 | 条件 | 说明 |
|---|---|---|
| recordAck / recordNack | WHERE confirm_status=WAITING | CAS 挡板，谁先到谁赢 |
| recordUnknown（3处触发） | WHERE confirm_status=WAITING | 同步异常 / 30s 超时 / Future 异常 |
| recordReturned | 无 WAITING 限制 | ACK 帧和 Return 帧两独立异步，都能写 |
| recordLateConfirm | WHERE confirm_status IN (UNKNOWN, NACK) | 迟到结果不覆盖主状态，只在旁路留痕 |

### ⑥ 回调不阻塞发送：Spring SettableListenableFuture

- `addCallback` 不阻塞；Confirm 比回调注册先到也不丢（SettableFuture done=true 时 addCallback 立即执行）
- 发送线程瞬间返回，下一轮 Outbox 继续推进

### ⑦ 决策机构：只有它能决定"等待 / 重发 / 回滚 / 人工"

五大裁决优先级（**顺序不能乱**）：

```
1. MySQL 订单已存在        → MARK_CONSUMED（其实成功了）
2. 证据自相矛盾            → MANUAL_REVIEW
3. 存在"可能投递"的attempt  → 按 Trigger 分：
                              RETRY_SIGNALLED(超时/异常) → RETRY_PUBLISH
                              CONFIRM_COMPLETED(NACK)   → WAIT
4. 全部 7 次都是明确失败    → ROLLBACK（唯一允许回滚的情况）
5. 兜底                    → MANUAL_REVIEW
```

铁律：**只要有一次 attempt 是 ACK/WAITING/UNKNOWN（可能已投递），绝不自动回滚。**

### ⑧ 7 次发布自动尝试 + 90 秒终局窗口

```
首次发送 + 7 次补偿发布（第 8 次发完不再创建新 attempt）
退避序列：1s → 2s → 4s → 30s → 2m → 10m → 30m
                                                ↓（最后一次发送后）
                                              90s 终局窗口
                                                ↓（仍无进展）
                                          MANUAL_REVIEW
                                          + 事务写 SOURCE_PUBLISH
```

### ⑨ 4 次回滚尝试 + 双路径对账兜底

- 回滚退避序列：`5s → 30s → 300s → 1800s`（超过转 MANUAL_REVIEW / SOURCE_ROLLBACK）
- 对账任务：7 天快扫（超 30 分钟孤儿预留）+ 全券分页兜底（每小时遍历全部券）
- 消费异常：临时故障分类重试，永久错误 → 持久化失败单后拒绝消息 → DLQ
- 死信消费者：只补 `x-death` 证据，不自动重放或回滚（避免决策路径分叉）

---

## 秒杀决策机构（统一裁决）

### 触发入口（5 个调用方，2 个 Trigger）

```
evaluateAfterConfirm (Trigger=CONFIRM_COMPLETED)
  ├─ NACK 回调到达        SeckillPublishConfirmHandler:L83
  └─ ACK + Returned 回调  SeckillPublishConfirmHandler:L106

evaluateForRetry (Trigger=RETRY_SIGNALLED)
  ├─ 30s 超时扫描         SeckillPublishConfirmTimeoutTask:L120
  ├─ Future 回调异常      SeckillPublishConfirmHandler:L148
  └─ 发送同步异常         SeckillOrderPublishRetryTask:L261
```

### 裁决 → 动作映射

| Decision | 后续动作 |
|---|---|
| `MARK_CONSUMED` | 事件标记 CONSUMED，收敛结束 |
| `RETRY_PUBLISH` | schedulePublishRetry：status→PUBLISH_UNKNOWN，next_retry_time 按退避推迟 |
| `ROLLBACK` | markRollbackPending：status→ROLLBACK_PENDING，next_retry_time=NOW（立即进入回滚队列） |
| `MANUAL_REVIEW` | 事务内 status→MANUAL_REVIEW + INSERT 失败单（SOURCE_PUBLISH/ROLLBACK/CONSUME/ORPHAN），人工工作台可见 |
| `WAIT` | 不动作，等其他机制（Confirm 回调、消费落单、超时扫描）自行收敛 |

---

## 核心表结构

### 秒杀可靠性表（面试重点）

| 表 | 关键字段 | 作用 |
|---|---|---|
| `tb_seckill_order_event` | status, next_retry_time, retry_count, lease_owner/until/token, row_version | Outbox + 事件状态机 |
| `tb_seckill_publish_attempt` | confirm_status(WAITING/ACK/NACK/UNKNOWN), returned, late_confirm_*, attempt_no | 每次发送的完整证据链（主状态只迁一次 + 旁路字段） |
| `tb_seckill_failure_case` | source(PUBLISH/ROLLBACK/CONSUME/ORPHAN), error_code, event_id, voucher_id, order_id, user_id | 人工工作台工单，按来源分类 |
| `tb_seckill_failure_audit` | action_type, operator, event_id, snapshot_before/after | 人工处理审计 |

### 业务表（点评脚手架）

`tb_user / tb_shop / tb_blog / tb_follow / tb_voucher / tb_seckill_voucher / tb_voucher_order`（订单表 `(user_id, voucher_id)` 联合唯一索引兜底一人一单）

---

## 后台定时任务一览

| 任务类 | 触发频率 | 作用 |
|---|---|---|
| `SeckillOrderPublishRetryTask` | 每 1s（fixedDelay，可配） | 扫到期 Outbox → 抢租约 → 发消息 → 退避推迟 |
| `SeckillPublishConfirmTimeoutTask` | 每 5s（可配） | attempt 超 30s 仍 WAITING → 标 UNKNOWN → evaluateForRetry |
| `SeckillReservationRollbackTask` | 每 5s | 核对订单 + CAS 抢执行 → Lua 还库存 或 退避/转人工 |
| `SeckillOrderReconciliationTask` | 每分 + 每小时 | 7 天快扫 + 全券兜底，处理孤儿预留 |
| `SeckillStockInitScanTask` | 启动后 + 定时 | 检查并安全补齐缺失的秒杀库存 Key |

---

## 其他业务模块（概览一句话）

餐饮点评业务作为承载模块，结构是标准的 `Controller → Service → Mapper/Redis`，不展开：

| 模块 | 实现（一句话） |
|---|---|
| 登录与会话 | 手机验证码/密码、Redis Hash + 双拦截器续期 ThreadLocal、滑动 Token |
| 商铺 | 详情穿透防缓存（空值+短TTL）、GEO 附近商铺、更新删缓存 |
| 探店笔记 | 发布/评论/热门/点赞（ZSet Top5），HTML 转义防 XSS |
| Feed | 推模式粉丝收件箱（ZSet），`score + offset` 滚动分页 |
| 签到 | Redis BitMap 按月 + 位运算连续签到统计 |
| 文件上传 | 扩展名/大小/文件魔数校验；toRealPath + startsWith 防路径穿越 |

---

## 技术栈

| 技术 | 版本 | 用途 |
|---|---|---|
| Java | 8 | 开发语言 |
| Spring Boot | 2.3.12 | 容器、调度、配置管理 |
| Spring AMQP | 2.2.18 | RabbitMQ Publisher Confirm / Return / Consumer / DLQ |
| MyBatis-Plus | 3.4.3 | ORM / 条件更新 / 分页 / 唯一索引 |
| Spring Data Redis | Lettuce 连接池 | RedisTemplate + Lua（6 个脚本） |
| Hutool | 5.7.17 | JSON / 字符串工具 |
| Vue 2 + Element UI | 静态 | 前端展示（Nginx 提供） |
| MySQL | 5.6+ / Connector 5.1.47 | 业务库 + 事件 + 订单 |
| RabbitMQ | Broker 环境提供 | 主交换机 + 主队列 + DLX + DLQ |
| Redis | 5+ / Cluster 兼容（Hash Tag） | 会话 / 库存 / 预占账本 / Feed / GEO / 签到 |
| Nginx | 环境提供 | 静态资源 + `/api/*` 反向代理 |

---

## 快速开始

### 1. 环境

JDK 8+ / Maven 3.6+ / MySQL 5.6+ / Redis 5+ / RabbitMQ（跑秒杀时需要）

### 2. 初始化数据库

```bash
mysql -u <user> -p < src/main/resources/db/dish_review.sql
# 增量迁移（顺序执行）
for f in src/main/resources/db/migration/*.sql; do
  mysql -u <user> -p dish_review < "$f"
done
```

### 3. 环境变量配置（用环境变量覆盖，不要把密码写进仓库）

```bash
export MYSQL_HOST=127.0.0.1    MYSQL_PORT=3306    MYSQL_USER=dish_review    MYSQL_PASSWORD='xxx'
export REDIS_HOST=127.0.0.1    REDIS_PORT=6379    REDIS_PASSWORD='xxx'
export RABBITMQ_HOST=127.0.0.1 RABBITMQ_PORT=5672 RABBITMQ_USERNAME=dish_review \
       RABBITMQ_PASSWORD='xxx' RABBITMQ_VHOST=/dish-review
# 秒杀开关（默认关闭，防止测试环境误触发）
export SECKILL_RABBIT_CONSUMER_ENABLED=true
export SECKILL_TASKS_ENABLED=true
# 生产部署时再开启 prod profile（短信/管理边界按生产值生效）
# export SPRING_PROFILES_ACTIVE=prod
```

### 4. 构建启动

```bash
mvn clean package -DskipTests
java -jar target/dish-review-0.0.1-SNAPSHOT.jar  # 默认 8081
```

验证：`curl http://localhost:8081/shop-type/list`

---

## 核心秒杀 API

```
POST /voucher-order/seckill/{voucherId}
  返回 Result { orderId, status: PROCESSING }  → "已受理"
  不等于订单落库成功！继续调状态查询。

GET  /voucher-order/status/{voucherId}/{orderId}（推荐，带 voucherId 可反向查 Redis 预留）
  返回：PROCESSING / SUCCESS / FAILED / MANUAL_REVIEW / NOT_FOUND
```

所有请求统一返回 `Result`；需要登录的接口请求头带 `authorization: <token>`。

---

## 源码阅读顺序

**第一遍（面试主链，30 分钟）**：

```
1. VoucherOrderServiceImpl     为什么请求只受理、不直接发 MQ
2. SeckillOrderPublishRetryTask  事件怎么选、租约怎么抢
3. SeckillOrderPublisher / SeckillPublishConfirmHandler  send() 和 handleConfirm()
4. SeckillOrderFailureDecisionService  decide() 五大优先级
5. SeckillReservationRollbackTask  ROLLBACK 后的两阶段 + 退避
6. VoucherOrderHandler  消费事务如何幂等和防双扣
```

**第二遍（异常收敛，看失败处置）**：SeckillPublishConfirmTimeoutTask → RabbitMqPublisherCallback → SeckillOrderReconciliationTask → SeckillOrderDeadLetterConsumer → SeckillStockInitScanTask → RabbitMqConfig（拓扑/重试/DLQ）

---

## 测试与能力边界

运行：`./mvnw test`（200 个用例，JDK 8 通过，0 failure / 0 skipped）

覆盖范围：状态机、租约、attempt CAS、发布/回滚退避序列、决策分类、消费幂等、订单状态查询、回滚竞争、DLQ 证据、对账孤儿预留、文件安全/XSS 等。

### 能力声明（诚实版）

| 已经通过自动化 | 还需要真实环境验收 |
|---|---|
| 单元测试 / Spring 装配 | RabbitMQ 实机 Confirm / Return / NACK / Returned 故障注入 |
| 表结构条件更新 / Lua 逻辑 | 多实例并发 + 宕机恢复（租约过期 + 实例重启衔接） |
| 决策分类 + 退避序列 | 高并发压测与容量报告 |
| 回滚竞争 / 对账一致性 | 完整监控告警、备份恢复 |
| 安全逻辑（路径穿越/XSS/密码等） | 生产 RBAC、短信供应商、对象存储接入 |

**不宣称经过百万 QPS、零消息丢失、完整故障演练。**

---

## 常见问题

**Q：接口返回成功，订单一定存在吗？**
A：不一定。返回的是"Redis 预占成功、请求已受理"。订单由异步消费者落库，调 `/voucher-order/status/...` 查询。

**Q：Confirm 30 秒没返回，会阻塞发送线程吗？**
A：不会。发送线程用 `addCallback` 非阻塞注册，尝试停在 WAITING；30 秒后由超时任务改为 UNKNOWN，进入补偿轨道。

**Q：为什么 attempt 是 UNKNOWN 不能回滚库存？**
A：UNKNOWN 可能是"消息真的进了 Broker，只是 Confirm 在回程路上丢了"。立即回滚会导致"订单落库成功 + 库存又被加回"的双扣。

**Q：什么时候才允许自动回滚？**
A：只有决策机构第 4 优先级命中——**事件存在全部 7 次 attempt，且每一次都是明确 NACK 或 Returned（没有 ACK/WAITING/UNKNOWN），同时 MySQL 订单查询为空**。

**Q：重复消息 RabbitMQ 会拒绝吗？**
A：不会。靠消费者幂等（FOR UPDATE 行锁 + 订单存在性检查 + `UNIQUE(user_id, voucher_id)`）兜底。

**Q：消费者/回滚任务挂了怎么办？**
A：
- 发布侧事件仍在 PUBLISH_UNKNOWN + next_retry_time，下一轮 Outbox 还会捞
- 回滚侧仍在 ROLLBACK_PENDING，5 秒后下一轮回滚任务再捞
- 孤儿预留对账在 30 分钟后还会扫一次
- 超过重试阈值最终都转 MANUAL_REVIEW 交人工，不会无限卡住

**Q：可以直接部署到生产吗？**
A：不建议。适合学习、面试展示、二次开发基础；正式上线仍需要：密钥治理、RBAC、实机中间件验收、监控告警、备份恢复、压测和故障演练。

---

仓库当前未包含 `LICENSE`。在选定开源协议前，代码默认保留所有权利。
