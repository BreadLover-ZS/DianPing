# RabbitMQ 秒杀链路：从消息流到可靠性面试

> 目标：理解 RabbitMQ 的消息生命周期，并能用本项目解释可靠发布、重复消费、失败重试、DLQ、回滚和最终一致性。
>
> 版本边界：项目使用 Java 8、Spring Boot 2.3.12 和 Spring AMQP 2.2.x。本文先解释通用原理，再说明当前源码；新版 API 或 RabbitMQ 4.x 能力不会写成项目已有能力。

## 0. 阅读方法

本文使用五种标记：

- **【必会】**：面试必须能独立回答。
- **【项目】**：当前代码已经实现。
- **【深入】**：用于应对连续追问。
- **【边界】**：代码、单测或真实环境尚未验证。
- **【自测】**：先回答，再看紧随其后的结论。

建议按三遍阅读：

1. 第一遍只读第 1～3 章，讲清正常消息流。
2. 第二遍读第 4～10 章，理解每个故障窗口如何收敛。
3. 第三遍读第 11～16 章，用故障推演和面试题检查理解。

### 0.1 一张图看懂项目

```text
HTTP 请求
  │
  ├─ 生成 eventId / orderId
  ├─ Redis Lua：扣可售库存 + 一人一单 + 写预留账本
  ├─ MySQL：尽力写 PENDING 事件
  └─ 返回“已受理”，请求线程不直接发 MQ
                         │
                         ▼
              Outbox 定时任务 CAS + 租约抢占
                         │
              创建 publish_attempt 证据
                         │
                         ▼
Producer ──routing key──> DirectExchange ──binding──> Main Queue
   ▲                                                    │
   │ Confirm / Return                                   ▼
   └────────────────────────────────────────────── Consumer
                                                        │
                                  MySQL 事务：锁事件行
                                  → 条件扣库存
                                  → 写订单
                                  → 标记 CONSUMED
                                                        │
                                                        ▼
                                          方法成功返回后由容器 ACK

失败分支：有限重试 → 先写 MySQL 失败记录 → Reject(requeue=false)
                                                │
                                                ▼
                                      DLX → Dead Letter Queue

收敛分支：确认超时扫描 / 发布补偿 / 持久化回滚 / Redis-MySQL 对账 / 人工审核
```

### 0.2 一分钟面试回答

项目把秒杀入口拆成“快速受理”和“异步落单”。请求先生成业务 ID，通过 Redis Lua 原子校验库存与一人一单，同时写预留账本；随后尽力写 MySQL PENDING 事件并返回受理。事件表作为 Outbox，定时任务通过 CAS 和租约抢占事件，记录独立发布尝试后发送持久化 RabbitMQ 消息。生产端用 Confirm、Return 和超时扫描保存发送证据，但结果未知时禁止直接回滚。消费端在同一数据库事务中锁定事件、条件扣库存、写订单并标记 CONSUMED，事务成功后才由 Spring 容器 ACK；重复投递由事件状态、订单查询和数据库唯一索引兜底。重试耗尽时先持久化失败记录，再拒绝消息进入 DLQ。Redis 预留、Outbox、回滚任务和双向对账分别处理跨 Redis、RabbitMQ、MySQL 的崩溃窗口。

不能补上一句“因此绝不丢消息”。真实 RabbitMQ 故障注入、跨存储崩溃演练和并发压测尚未完成。

## 1. MQ 解决什么，又带来什么

### 1.1 不使用 MQ

如果 HTTP 请求同步完成所有工作，请求线程需要依次访问 Redis、MySQL，并等待订单写入：

```text
请求 → 校验 → Redis 扣库存 → MySQL 扣库存 → 写订单 → 返回
```

问题不是“同步一定错误”，而是秒杀峰值直接传给数据库：

- 请求耗时受最慢依赖控制。
- 数据库瞬时并发和库存行竞争增大。
- 任一依赖抖动都会占住请求线程。
- 扩展通知、积分等后续动作会继续拉长链路。

### 1.2 引入 MQ

项目在 Redis 预留成功后先返回受理，订单写入由消费者异步完成：

- **异步**：用户不等待 MySQL 订单事务完成。
- **削峰**：Queue 暂存生产速度超过消费速度的消息。
- **解耦**：请求入口不直接调用订单事务处理器。

代价同样明确：

- 用户拿到的是“已受理”，不是“订单已成功”。
- 消息可能重复、延迟、积压或结果未知。
- Redis、RabbitMQ、MySQL 之间没有一个本地事务。
- 系统必须补充状态查询、幂等、重试、回滚、对账和监控。

**【必会】MQ 不是一致性解决方案。** 它提供异步传输和缓冲能力，一致性仍由业务协议完成。

### 1.3 Redis Lua 和 RabbitMQ 为什么不能互相替代

Redis Lua 解决入口原子性：库存检查、扣减、一人一单和预留账本写入要么一起成功，要么一起失败。

RabbitMQ 解决异步传输：把已经受理的订单意图交给消费者处理，并在短时消费故障时保留消息。

Lua 不负责可靠地把事件送到消费者；RabbitMQ 也不能原子地修改 Redis 库存。

## 2. RabbitMQ 最小模型

### 2.1 七个对象

| 对象 | 职责 | 当前项目 |
| --- | --- | --- |
| Producer | 发布消息 | `SeckillOrderPublisher` |
| Broker | RabbitMQ 服务节点 | 由连接配置指向的 RabbitMQ |
| Exchange | 根据规则路由消息，本身不是业务消息仓库 | `dianping.seckill.direct` |
| Routing Key | 发布者附带的路由字符串 | `seckill.order.create` |
| Binding | Exchange 到 Queue 的路由关系 | 主交换机与主队列的精确绑定 |
| Queue | 保存等待投递的消息 | `dianping.seckill.order.queue` |
| Consumer | 接收并处理消息 | `SeckillOrderConsumer` |

消息不是“生产者直接塞进某个消费者”。生产者把消息发布到 Exchange；Exchange 根据 Binding 和 Routing Key 决定进入哪个 Queue；Broker 再把 Queue 中的消息投递给 Consumer。

### 2.2 为什么使用 DirectExchange

当前只有“创建秒杀订单”这一类明确事件，Routing Key 固定为 `seckill.order.create`。DirectExchange 按完全匹配路由，语义最直接。

| 类型 | 路由方式 | 典型用途 |
| --- | --- | --- |
| Direct | Routing Key 完全匹配 | 明确命令或单类业务事件 |
| Fanout | 忽略 Routing Key，广播给全部绑定队列 | 同一事件通知多个独立系统 |
| Topic | 使用 `*`、`#` 匹配分段 Key | 多类、分层事件订阅 |
| Headers | 根据 Header 条件匹配 | 少见，适合不便用字符串路由的场景 |

选择 Exchange 不是性能背诵题，先看业务路由关系。当前没有广播和通配订阅需求，Direct 足够。

### 2.3 Connection、Channel 和线程

- Connection 是应用到 Broker 的 TCP 连接，建立成本较高。
- Channel 是复用 Connection 的 AMQP 虚拟会话，发布、消费和 ACK 都在 Channel 上进行。
- Delivery Tag 只在接收它的 Channel 内有效，不能拿到另一个 Channel 上 ACK。

Spring 的 `CachingConnectionFactory` 负责连接和 Channel 缓存。业务代码通常使用 `RabbitTemplate` 和监听容器，不手工为每条消息创建 TCP 连接。

**【深入】Channel 不是“业务线程池”。** 它是协议会话；并发消费线程、数据库连接池和 Channel 数量是相关但不同的资源。

### 2.4 当前拓扑

```text
dianping.seckill.direct
  └─ seckill.order.create
       └─ dianping.seckill.order.queue
            ├─ Consumer
            └─ reject/nack, requeue=false
                 └─ dianping.seckill.dlx
                      └─ seckill.order.dead
                           └─ dianping.seckill.order.dlq
```

主交换机、主队列、DLX 和 DLQ 都声明为 durable。主队列通过 `x-dead-letter-exchange` 和 `x-dead-letter-routing-key` 指向死信拓扑。

## 3. 正常消息流

### 3.1 请求受理

入口是 `VoucherOrderServiceImpl.seckillVoucher()`：

1. 校验登录、券信息和活动时间。
2. 在 Lua 执行前生成 `eventId` 和 `orderId`。
3. 执行 Redis Lua。
4. Lua 成功后尽力创建 MySQL PENDING 事件。
5. 即使事件写入抛技术异常，也保留 Redis 预留，由对账任务补建事件。
6. 返回 `orderId + voucherId`，供前端查询最终状态。

这里的返回值表示“系统已接受预留”，不是 MySQL 订单已经存在。

### 3.2 Redis 六 Key 预留账本

Lua 同时操作六个带 `{voucherId}` Hash Tag 的 Key：

| Key | 作用 |
| --- | --- |
| 库存 String | Redis 可售库存 |
| 用户 Set | 快速拦截一人一单 |
| 预留详情 Hash | `eventId → orderId/userId/时间/版本` |
| 用户事件 Hash | `userId → eventId` |
| 待对账 ZSet | 按预留时间扫描孤儿预留 |
| 订单反向索引 Hash | `orderId → eventId`，支持状态查询 |

六个 Key 同槽是 Redis Cluster 下执行多 Key Lua 的前提。预留账本的关键价值是：Redis 成功而 MySQL 事件失败时，系统仍有足够信息恢复事件。

### 3.3 Outbox 发布

`SeckillOrderPublishRetryTask` 是唯一生产者入口：

1. 扫描 `next_retry_time` 已到期的可发布事件。
2. 使用 CAS、租约持有者和 fencing token 抢占事件。
3. 在数据库事务中创建 `tb_seckill_publish_attempt` 记录。
4. 组装 `SeckillOrderMessage`。
5. 调用一次 `convertAndSend()`。
6. 释放租约；进程崩溃则等待租约过期后由其他实例接手。

请求线程不直接发消息，避免“HTTP 线程发送一半崩溃后，没有持久化任务可追踪”的窗口。

### 3.4 消息内容

```text
eventId    业务事件 ID，也是 messageId
orderId    订单主键，入口提前生成
userId     下单用户
voucherId  秒杀券
createdAt  创建时间，Unix 毫秒
version    消息结构版本，当前为 1
```

`attemptId` 不属于业务消息体，它标识一次实际发送，放在 CorrelationData 和 Header 中。

为什么同时需要两个 ID：

- `eventId`：同一业务意图，多次发布仍是同一个事件。
- `attemptId`：每次发送分别记录 Confirm、Return、异常和时间。

### 3.5 消费事务

`SeckillOrderConsumer` 校验消息后调用 `VoucherOrderHandler.createOrder()`。后者在一个 MySQL 事务中：

1. `SELECT ... FOR UPDATE` 锁定事件行。
2. 根据事件状态判断幂等、迟到消息或回滚竞争。
3. 查询同一用户是否已有该券订单。
4. 使用 `stock > 0` 条件更新 MySQL 库存。
5. 插入订单。
6. 把事件标记为 CONSUMED。
7. 事务提交后，监听方法正常返回，Spring 容器才 ACK。

订单、库存和事件状态同库同事务；其中一步失败，三者一起回滚。

## 4. Confirm、Return 与 Consumer ACK

### 4.1 三者回答不同问题

| 机制 | 方向 | 回答的问题 | 不证明什么 |
| --- | --- | --- | --- |
| Publisher Confirm | Broker → Producer | Broker 是否接受本次发布 | 不证明消费者已完成业务 |
| Return | Exchange → Producer | 消息是否无法路由到任何 Queue | 不证明消费成功或失败 |
| Consumer ACK | Consumer → Broker | 本次投递是否可从 Queue 删除 | 不证明生产者收到 Confirm |

Publisher Confirm 与 Consumer ACK 完全正交。把两者都叫“ACK”容易混淆，面试时必须说清方向。

### 4.2 四个典型场景

| 场景 | Confirm | Return | Consumer ACK |
| --- | --- | --- | --- |
| 正常入队并消费 | ACK | 无 | 业务成功后 ACK |
| Exchange 不存在 | NACK、Channel 异常或发送异常 | 通常不是 Return 场景 | 无 |
| Exchange 存在，Routing Key 无绑定 | 通常 ACK | `mandatory=true` 时 Return | 无 |
| 消费事务成功，ACK 前连接断开 | 生产侧早已结束 | 无 | Broker 未收到，可能重投 |

### 4.3 当前生产端配置

```yaml
publisher-confirm-type: correlated
publisher-returns: true
template:
  mandatory: true
  retry:
    enabled: false
```

- correlated Confirm 用 `attemptId` 关联一次发送。
- `mandatory=true` 使不可路由消息返回生产者。
- 模板重试关闭，发送次数统一由 Outbox 控制。

### 4.4 为什么“结果未知”不能当失败

假设 Producer 已把消息写入网络，Broker 也已入队，但连接在 Confirm 返回前断开。Producer 只看到超时，无法判断消息到底有没有到达。

如果此时回滚 Redis，原消息随后仍可能被 Consumer 创建订单，造成“库存已恢复但订单成功”。因此项目把同步异常和 Confirm 超时记为 UNKNOWN，允许补偿发布，但禁止仅凭未知结果回滚。

### 4.5 Spring AMQP 2.2 的回调边界

当前代码同时使用：

- `CorrelationData` Future 处理 Confirm。
- `RabbitTemplate.ReturnCallback` 记录不可路由证据。

Spring AMQP 2.2 文档与后续版本在 ReturnedMessage 和 Confirm Future 的顺序保证上存在版本差异。不能只凭新版示例断言“Return 一定先于 Confirm Future 可见”。

**【边界】** 当前实现会把 Return 单独写入发布尝试表，但迟到 Return 与事件状态的最终收敛仍应通过真实 Broker 故障测试验证，不能只靠单元测试推断。

## 5. Outbox 与发布尝试证据

### 5.1 Outbox 解决的窗口

错误设计：

```text
写业务状态 → 直接发 MQ
```

进程可能在两步之间崩溃；本地数据库事务不能覆盖 RabbitMQ。

Outbox 设计：

```text
先把“待发送事件”写进 MySQL
→ 独立任务扫描
→ 发送
→ 根据证据推进状态
```

只要事件仍在数据库，发布任务就能在进程恢复后继续处理。

### 5.2 为什么需要 CAS 和租约

多个应用实例可能同时扫描到同一事件：

- CAS 保证只有满足当前状态和版本的更新能成功。
- 租约记录 `lease_owner`、`lease_until` 和 `lease_token`。
- 实例宕机后租约自动过期，任务可被重新领取。
- fencing token 防止旧持有者在租约过期后继续覆盖新结果。

租约不是永久锁；它把“实例失联”转换成“等待到期后重试”。

### 5.3 为什么不能只在事件表记录最后结果

同一个 event 可能发送多次：

```text
attempt-1：UNKNOWN
attempt-2：ACK，未 Return
attempt-3：NACK
```

如果只保留“最后一次 NACK”，会错误推断消息从未到达。发布尝试表保留每次发送证据，失败决策必须查看全部尝试。

当前决策顺序：

1. MySQL 订单已存在：标记 CONSUMED，绝不回滚。
2. 任一尝试可能已投递：等待或继续补偿，禁止回滚。
3. 所有尝试都明确 NACK 或 Return，且无订单：允许进入 ROLLBACK_PENDING。
4. 证据矛盾：MANUAL_REVIEW。

### 5.4 当前发布退避

自动发送最多 8 次：首次发送，加 7 个退避轮次。

```text
第 1 次后：1 秒
第 2 次后：2 秒
第 3 次后：4 秒
第 4 次后：30 秒
第 5 次后：2 分钟
第 6 次后：10 分钟
第 7 次后：30 分钟
第 8 次后：等待 90 秒终局窗口，再转人工
```

Confirm 默认 30 秒超时，每 5 秒扫描 WAITING 尝试。最后的 90 秒必须大于 Confirm 超时，避免最后一次刚发出就转人工。

## 6. Broker 持久性与高可用

### 6.1 三个“持久化”条件

要让普通 AMQP 0-9-1 消息在 Broker 重启后具备恢复条件，至少需要：

1. Exchange durable。
2. Queue durable。
3. Message delivery mode 为 persistent。

当前项目三项都配置了。

但这不等于“任何故障都不丢”：

- 单节点磁盘损坏不由 durable 自动解决。
- 发布者必须等待 Confirm 才知道 Broker 是否承担该次发布。
- Consumer 必须在业务成功后 ACK。
- DLX 转发本身也是一次发布，也可能失败。

### 6.2 当前是 Classic Queue，不是 Quorum Queue

`RabbitMqConfig` 没有设置 `x-queue-type=quorum`，因此不能把项目描述成 Quorum Queue 方案。

| 队列 | 重点 |
| --- | --- |
| Classic Queue | 通用队列；现代 RabbitMQ 中默认不代表跨节点复制 |
| Quorum Queue | 基于多数派复制，面向数据安全和高可用；需要多数副本在线 |

Quorum Queue 在确认、多副本和故障恢复上提供更强语义，但会增加磁盘、网络和延迟成本。是否迁移必须结合 Broker 版本、节点数、磁盘和容量测试决定。

**【边界】** 项目未确认 RabbitMQ 集群、Quorum Queue、磁盘告警和节点故障恢复，不能声称 Broker 层高可用已经完成。

### 6.3 DLX 也不是绝对可靠

经典队列将死信重新发布到 DLX 时，目标 Exchange 或 Queue 不可用可能导致死信丢失。项目因此把 MySQL `tb_seckill_failure_case` 作为失败事实，把 DLQ 当作运维副本。

这是“先落失败记录，再拒绝进 DLQ”的原因，不是多写一张表的形式主义。

## 7. Consumer ACK、重试与 Prefetch

### 7.1 当前 `acknowledge-mode: auto` 的准确含义

Spring AMQP 的 AUTO 不是 RabbitMQ 协议层“消息一发出就自动确认”。在当前监听容器中：

- Listener 正常返回：容器发送 ACK。
- Listener 抛异常：由重试、Recoverer 和 requeue 策略决定后续动作。

因此消费事务必须在方法返回前提交。若先返回再异步写库，容器可能已经 ACK，之后失败无法重投。

### 7.2 ACK、NACK、Reject

| 操作 | 可批量 | 可选择 requeue | 作用 |
| --- | --- | --- | --- |
| ACK | 是 | 否 | 成功处理，可删除投递 |
| NACK | 是 | 是 | 否定确认，可重入队或死信 |
| Reject | 否 | 是 | 否定单条投递 |

`requeue=true` 会让消息回主队列；若异常不会自行恢复，可能形成高频热循环。

### 7.3 当前消费重试

```text
最多 4 次尝试 = 初次消费 + 3 次重试
间隔约 1 秒、2 秒、4 秒
耗尽后交给 MessageRecoverer
```

异常分三类：

- 临时故障：数据库超时、连接故障、死锁等，有限重试。
- 永久消息错误：字段缺失、非法 ID、版本不支持，不重试。
- 一致性冲突：订单事实和事件事实矛盾，不做相同业务重试，转人工。

### 7.4 Prefetch 与并发

当前配置：

```text
concurrency = 3
max-concurrency = 10
prefetch = 10
```

Prefetch 限制每个 Consumer 可持有的未确认消息数。粗略上限不是全局 10，而与活跃 Consumer 数相关；例如 3 个 Consumer 各自最多预取 10 条，可能同时存在约 30 条未确认消息。

调大 Prefetch：

- 减少等待网络往返，提高吞吐。
- 增加单个消费者持有的未确认消息。
- 慢消息会让分配不均更明显。
- 应用和 Broker 内存压力上升。

增加 Consumer 并发也不是免费扩容：最终瓶颈可能转移到数据库连接池、同一库存行和磁盘。

## 8. 重复投递与幂等

### 8.1 为什么会重复

最常见时间线：

```text
Consumer 完成 MySQL 事务
→ 准备 ACK
→ 连接断开
→ Broker 没收到 ACK
→ 消息重新投递
```

RabbitMQ 无法知道业务事务已经提交，只能按未确认处理。可靠消费的常见目标是 at-least-once，再由业务幂等吸收重复。

### 8.2 项目四层防线

1. Redis 用户 Set：入口快速拦截重复预留。
2. 事件状态：CONSUMED 直接幂等返回。
3. 消费事务查询已有订单。
4. 数据库 `(user_id, voucher_id)` 唯一索引：并发竞态下的最终底线。

“先查询再插入”不能替代唯一索引：两个事务可能同时查询不到，然后同时插入。

### 8.3 DuplicateKey 不能一律当成功

捕获 `DuplicateKeyException` 后，项目会再次确认目标订单是否存在。只有业务订单确实存在，才把事件收敛为 CONSUMED。

其他唯一约束、数据错误或错误主键冲突不能伪装成幂等成功。

### 8.4 为什么不说 Exactly-once

Broker 投递与 MySQL 提交跨越两个系统，没有一个原子提交点。即使 RabbitMQ 只投递一次，Consumer 在外部系统的副作用也可能因超时重试而重复。

项目提供的是：

```text
至少一次投递倾向 + 幂等业务处理 + 数据库唯一约束 + 对账收敛
```

面试时说“业务效果最终只生效一次”比“RabbitMQ 保证 exactly-once”准确。

## 9. DLQ 与失败事实

### 9.1 什么消息会死信

RabbitMQ 常见死信原因：

- Consumer Reject/NACK 且 `requeue=false`。
- 消息 TTL 到期。
- Queue 超过长度限制。
- Quorum Queue 超过 delivery limit。

当前业务主要使用第一种：消费重试耗尽后拒绝进入 DLQ。

### 9.2 正确顺序

```text
消费重试耗尽
→ 独立事务写 failure_case
→ 推进事件到 DLQ 或 MANUAL_REVIEW
→ 事务提交
→ RejectAndDontRequeue
→ Broker 尝试死信转发
```

若失败记录落库失败，`ImmediateRequeueAmqpException` 强制消息回主队列，避免既没有数据库证据又丢掉原消息。

失败记录和 Reject 不能放在一个随后必然回滚的事务中，否则刚写入的记录会随异常一起回滚。

### 9.3 Listener 前的转换失败

JSON 反序列化可能发生在业务 Listener 调用前，业务方法无法捕获。项目使用容器级 `SeckillRabbitListenerErrorHandler`：

- 从原始 AMQP Message 提取 messageId 和受限 Header。
- 保存受限长度的消息摘要。
- 先写失败记录，再拒绝进入 DLQ。

原始不可信消息不能无限完整写日志或数据库，否则可能造成敏感信息泄露和存储放大。

### 9.4 DLQ Consumer 不做什么

`SeckillOrderDeadLetterConsumer` 只补充 `x-death` 和到达证据：

- 不直接恢复 Redis 库存。
- 不自动重发业务消息。
- 不把“进入 DLQ”视为业务必然失败。

原因是消息进入 DLQ 时，另一条重复消息可能已经成功创建订单。恢复库存前必须重新核对订单、事件和全部发布证据。

## 10. Redis、RabbitMQ、MySQL 一致性

### 10.1 三个系统保存什么

| 系统 | 当前事实 |
| --- | --- |
| Redis | 可售库存、一人一单集合、未收敛预留 |
| RabbitMQ | 等待消费或未确认的传输副本 |
| MySQL | 事件、发布尝试、订单、失败记录和审计事实 |

没有一个本地事务可以同时提交三者，因此要逐个识别窗口。

### 10.2 六个核心故障窗口

#### 窗口 A：Redis 预留成功，PENDING 写入失败

不能直接回滚，因为数据库错误不代表后续一定无法恢复。Redis 预留账本保留重建信息，对账任务扫描 ZSet 后补建事件。

#### 窗口 B：PENDING 成功，发布前进程崩溃

事件仍在 Outbox。租约到期后，发布任务重新扫描并发送。

#### 窗口 C：消息可能到达，Confirm 丢失

发布尝试标记 UNKNOWN，按退避补偿发送。存在可能投递证据时禁止自动回滚。

#### 窗口 D：订单事务成功，ACK 丢失

Broker 重投。事件已是 CONSUMED，Consumer 幂等返回；唯一索引继续兜底。

#### 窗口 E：失败决策准备回滚，迟到消息开始消费

事件为 ROLLBACK_PENDING 时，Consumer 必须先 CAS 取消回滚；回滚任务已抢占为 ROLLBACK_EXECUTING 时，Consumer 重试等待，避免一边恢复库存一边创建订单。

#### 窗口 F：回滚 Lua 成功，数据库状态尚未更新时进程崩溃

事件停在 ROLLBACK_EXECUTING。对账任务根据 Redis 预留是否仍存在判断：预留已消失则收敛 ROLLED_BACK；仍存在则恢复待回滚或转人工。

### 10.3 回滚为什么按 eventId 校验

用户可能经历：事件 A 预留 → A 回滚 → 事件 B 再次预留。若迟到的 A 只按 userId 回滚，会误删 B 并错误增加库存。

回滚 Lua 必须确认 `userId → eventId` 映射仍指向当前事件：

- 映射不存在：已经处理，幂等成功。
- 映射指向其他事件：冲突，禁止修改库存。
- 映射一致：删除当前预留，并只在确实移除用户时恢复库存。

### 10.4 对账为什么是双向的

- Redis → MySQL：发现孤儿预留，补建事件或核对订单。
- MySQL → Redis：CONSUMED 清理预留；回滚卡住时收敛；UNKNOWN 超时转人工。

库存不能简单设置为 MySQL 剩余库存。安全公式是：

```text
Redis 可售库存 = MySQL 剩余库存 - 尚未生成订单的有效 Redis 预留数
```

直接覆盖会把仍在途的预留重新卖出。

## 11. 状态机

### 11.1 先按业务分组

| 分组 | 状态 | 含义 |
| --- | --- | --- |
| 等待发布 | PENDING | 等待首次或补偿发布 |
| 结果未知 | PUBLISH_UNKNOWN | 至少一次发送无法判定结果 |
| 已到 Broker | CONFIRMED | ACK 且当前未发现 Return |
| 成功终态 | CONSUMED | 订单事务完成 |
| 等待回滚 | ROLLBACK_PENDING | 已允许回滚，等待任务执行 |
| 回滚执行 | ROLLBACK_EXECUTING | Lua 执行窗口，阻止并发消费 |
| 失败终态 | ROLLED_BACK | Redis 预留已恢复 |
| 消费隔离 | DLQ | 失败记录已保存，消息进入死信流程 |
| 人工处理 | MANUAL_REVIEW | 自动证据不足或重试耗尽 |
| 历史兼容 | FAILED | 旧状态，新代码禁止写入 |

### 11.2 主路径

```text
PENDING → CONFIRMED → CONSUMED
```

Confirm 可能晚于消费，因此也允许：

```text
PENDING → CONSUMED
PUBLISH_UNKNOWN → CONSUMED
```

### 11.3 失败路径

```text
PENDING / PUBLISH_UNKNOWN
  → ROLLBACK_PENDING
  → ROLLBACK_EXECUTING
  → ROLLED_BACK
```

消费失败路径：

```text
PENDING / PUBLISH_UNKNOWN / CONFIRMED
  → DLQ
  → 人工重放到 PENDING，或核对后回滚，或关闭
```

### 11.4 状态机的三个保护

1. 每次自动迁移先经过纯状态机校验。
2. 数据库 UPDATE 带来源状态和版本条件，避免并发覆盖。
3. CONSUMED、ROLLED_BACK 终态禁止被迟到 Confirm、Return 或任务覆盖。

MANUAL_REVIEW 的出边只允许人工处置 Service 使用，自动任务不能擅自恢复。

## 12. 顺序、积压与流量控制

### 12.1 RabbitMQ 是否保证顺序

Queue 入队具有顺序，但业务观察到的完成顺序会受以下因素影响：

- 多 Producer 并发发布。
- 多 Consumer 并发处理。
- 某条消息失败后重新入队。
- 不同消息处理耗时不同。
- 消费事务和 ACK 完成时间不同。

当前主队列最多扩展到 10 个 Consumer，不提供严格全局顺序。秒杀订单依赖每个事件独立幂等，而不是依赖所有订单串行。

若业务需要同一聚合内顺序，常见选择是按业务 Key 分区到固定 Queue、单活 Consumer，或在业务层用版本号拒绝乱序；代价是吞吐和复杂度。

### 12.2 消息积压怎么判断

先看两类数量：

- Ready：仍在 Queue 等待投递。
- Unacked：已经投给 Consumer，但尚未确认。

典型判断：

| 现象 | 优先怀疑 |
| --- | --- |
| Ready 持续上涨，Unacked 不高 | Consumer 数量不足、未启动或整体吞吐不足 |
| Unacked 很高 | Consumer 处理慢、Prefetch 过大或下游阻塞 |
| 两者都低但业务未完成 | Producer/路由/Outbox 或业务状态查询问题 |

扩容 Consumer 前必须检查 MySQL 连接池、库存热点行和 Redis 延迟，否则只是把积压从 Queue 转移到数据库。

### 12.3 容量估算

最低需要三个量：

```text
生产速率 λp（条/秒）
单 Consumer 平均消费速率 λc（条/秒）
Consumer 数 N
```

若 `λp > N × λc` 持续存在，积压必然增长。估算还要加入失败重试放大、消息大小、数据库竞争和峰值持续时间。

项目尚未完成真实压测，所以配置中的 3～10 个 Consumer 不是吞吐承诺。

## 13. 可观测性

### 13.1 Broker 指标

- 主队列 Ready、Unacked、消费速率、ACK 速率。
- Publisher Confirm ACK/NACK 数和延迟。
- Return 数量。
- DLQ Ready、Unacked 和增长速率。
- Connection、Channel、Consumer 数。
- 内存、磁盘、水位告警和节点状态。

### 13.2 业务指标

- PENDING、PUBLISH_UNKNOWN、ROLLBACK_PENDING、DLQ、MANUAL_REVIEW 数量和最老年龄。
- WAITING 发布尝试超时数。
- 发布重试次数和耗尽数。
- 消费重试、失败记录和人工处置数量。
- Redis 有效预留数与 MySQL 订单差值。
- 请求受理到 CONSUMED 的 P50/P95/P99 延迟。

只监控 Queue 长度不够。消息可能已经离开 Queue，但事件卡在数据库状态或 Redis 预留中。

### 13.3 一个排障顺序

用户反馈“已抢到但一直没有订单”时：

1. 用 `orderId` 查 MySQL 订单。
2. 查事件状态和最后更新时间。
3. 查全部 publish_attempt。
4. 查主 Queue 和 DLQ。
5. 查 Redis 预留反向索引。
6. 查 failure_case 和审计记录。
7. 根据事实决定等待、重放、回滚或人工关闭。

禁止只看一条日志就恢复库存。

## 14. 纸面故障推演

每题先回答四个问题：消息可能在哪里、事件是什么状态、能否回滚、谁负责收敛。

### 14.1 Exchange 名称错误

**结论：** 可能出现 NACK、Channel 异常或同步发送异常。同步异常仍可能存在网络结果不确定性，先记录尝试证据；只有全部尝试明确失败且无订单时，失败决策才允许回滚。

### 14.2 Routing Key 无绑定

**结论：** Exchange 通常 Confirm ACK；`mandatory=true` 触发 Return。该次尝试明确没有进入目标 Queue，但仍要检查其他尝试是否可能成功。

### 14.3 Confirm 30 秒未返回

**结论：** 超时扫描把 WAITING 改为 UNKNOWN，安排补偿发布；不能据此回滚。

### 14.4 Consumer 事务提交后、ACK 前宕机

**结论：** Broker 重投；事件 CONSUMED 或唯一索引使重复消息幂等成功，然后重新 ACK。

### 14.5 MySQL 临时超时

**结论：** Listener 抛临时异常，按 1/2/4 秒有限重试。耗尽后先写失败记录，再拒绝进入 DLQ。

### 14.6 Redis 预留后应用立即宕机

**结论：** 若 PENDING 未写入，Redis 待对账 ZSet 仍保存预留；对账任务补建事件。不能因为 MySQL 暂时查不到就判定用户没有下单。

### 14.7 回滚任务和迟到 Consumer 同时运行

**结论：** 两者通过事件状态 CAS 竞争。Consumer 只有成功取消 ROLLBACK_PENDING 才能继续；任务已进入 ROLLBACK_EXECUTING 时 Consumer 必须等待。

### 14.8 失败记录数据库不可用

**结论：** Recoverer 抛 `ImmediateRequeueAmqpException`，保留原消息。不能 ACK，也不能只依赖 DLQ。

### 14.9 DLX 目标不可用

**结论：** 经典队列的死信转发可能失败；MySQL failure_case 仍是主要失败事实。需要告警和真实故障演练，不能声称 DLQ 永不丢。

### 14.10 同一 event 的一次发送 ACK、另一次 NACK

**结论：** ACK 且未 Return 表示存在可能投递，NACK 不能覆盖它。必须等待消费或人工核对，禁止按“最后结果 NACK”回滚。

## 15. 源码阅读路线

### 第一遍：只看正常链路

1. `VoucherOrderController`
2. `VoucherOrderServiceImpl.seckillVoucher()`
3. `seckill.lua`
4. `SeckillOrderEventService.createPending()`
5. `SeckillOrderPublishRetryTask`
6. `SeckillOrderPublisher`
7. `SeckillOrderConsumer`
8. `VoucherOrderHandler.createOrder()`

目标：不看失败分支，独立画出第 0 章的主链路。

### 第二遍：生产可靠性

1. `application.yaml` 的 Confirm、Return、mandatory 和模板重试。
2. `SeckillPublishAttempt`。
3. `SeckillPublishConfirmHandler`。
4. `RabbitMqPublisherCallback`。
5. `SeckillPublishConfirmTimeoutTask`。
6. `SeckillOrderFailureDecisionService`。

目标：解释一次 event 为什么可能有多个 attempt，以及为什么 UNKNOWN 禁止回滚。

### 第三遍：消费失败

1. `RabbitMqConfig` 的 Listener 重试分类与 MessageRecoverer。
2. `SeckillRabbitListenerErrorHandler`。
3. `SeckillOrderDeadLetterConsumer`。
4. `SeckillFailureCaseService`。

目标：解释“先写失败记录，再进入 DLQ”的顺序。

### 第四遍：跨存储收敛

1. `SeckillReservationRollbackTask`。
2. `seckill_rollback.lua`。
3. `SeckillOrderReconciliationTask`。
4. `SeckillStockInitScanTask`。
5. `VoucherOrderServiceImpl.queryOrderStatus()`。

目标：逐个对应第 10 章的六个故障窗口。

### 第五遍：数据库约束

阅读迁移目录，重点核对：

- `(user_id, voucher_id)` 唯一索引。
- 事件任务扫描组合索引。
- 事件 `row_version`、租约和终态时间。
- 发布尝试 `(event_id, attempt_no)` 唯一约束。
- failure_case 幂等键。
- 人工处置审计表。

## 16. 高频面试题

### 16.1 为什么项目使用 RabbitMQ

**答题骨架：** 请求线程快速受理；Queue 缓冲峰值；Consumer 异步写订单；代价是必须处理重复、积压、结果未知和跨存储一致性。

### 16.2 Confirm、Return、ACK 有什么区别

**答题骨架：** Confirm 是 Producer 到 Broker；Return 是 Exchange 无法路由；Consumer ACK 是业务处理完成后通知 Broker 删除投递，三者互不替代。

### 16.3 如何保证消息不丢

**答题骨架：** 不承诺绝对不丢。Producer 用 Outbox、持久化消息、Confirm/Return、尝试证据和超时补偿；Broker 需要 durable Queue 和部署层高可用；Consumer 在事务成功后 ACK；失败记录和对账负责兜底。当前真实 Broker 故障演练仍未完成。

### 16.4 为什么不用 RabbitMQ 事务

**答题骨架：** Broker 事务开销大，也不能把 Redis 和 MySQL 一起纳入原子事务。项目使用 Outbox、Confirm、幂等和补偿，把失败转成可重试、可查询的状态。

### 16.5 什么是 Outbox

**答题骨架：** 先在本地数据库记录待发送事件，再由独立任务发布。它关闭“业务状态已保存但消息还未发送时进程崩溃”的窗口。项目事件表就是 Outbox，发布任务是唯一 Producer 入口。

### 16.6 Confirm 超时为什么不能回滚

**答题骨架：** 超时只表示生产者不知道结果，消息可能已入队。直接回滚会与迟到消费并发，造成库存恢复后仍创建订单。

### 16.7 如何解决重复消费

**答题骨架：** 接受 at-least-once，用 Redis 一人一单、事件状态、订单查询和数据库唯一索引四层幂等。事务提交后 ACK 丢失时，重投不会重复创建订单。

### 16.8 为什么唯一索引不可缺少

**答题骨架：** 查询后插入存在并发窗口，Redis 和应用锁也可能失效；唯一索引位于最终数据写入层，是防止重复订单落库的底线。

### 16.9 重试和 DLQ 分别解决什么

**答题骨架：** 重试处理短暂故障；DLQ 隔离主流程无法继续处理的消息。DLQ 不是自动补偿，也不是唯一失败事实。

### 16.10 为什么先写失败记录再进 DLQ

**答题骨架：** 经典队列的死信转发也可能失败。MySQL 失败记录必须先提交，DLQ 只保存运维副本；失败记录写不进去就重新入队。

### 16.11 Prefetch 越大越好吗

**答题骨架：** 不是。增大可提高流水线吞吐，但会增加未确认消息、内存占用和分配不均。要结合处理耗时、并发数、数据库容量和故障恢复时间调整。

### 16.12 RabbitMQ 能保证顺序吗

**答题骨架：** Queue 有入队顺序，但多 Producer、多 Consumer、重试和处理耗时会改变业务完成顺序。当前项目不承诺全局顺序，而依赖事件级幂等和状态机。

### 16.13 消息积压怎么办

**答题骨架：** 先区分 Ready 和 Unacked，再检查生产速率、消费耗时和下游数据库。不能盲目加 Consumer；可能把 Broker 积压转成数据库雪崩。

### 16.14 Classic 与 Quorum Queue 怎么选

**答题骨架：** Classic 适合一般队列；Quorum 基于多数派复制，适合强调数据安全和高可用的场景，但成本更高。当前项目没有声明 Quorum Queue，不能把它说成已实现能力。

### 16.15 这套方案还有什么缺口

**答题骨架：** 代码和单测已覆盖状态机、Outbox、失败决策、DLQ 记录、回滚与对账；数据库迁移步骤 1～2 已执行。真实 RabbitMQ 连通性与故障注入、跨存储崩溃演练、并发压测、集群和 Quorum Queue、部署监控、迁移步骤 3～8、RBAC 管理入口仍未完成。

## 17. 最终背诵页

### 17.1 三个确认

```text
Confirm：Producer → Broker
Return：Exchange → Queue 路由失败
ACK：Consumer → Broker，业务成功后删除投递
```

### 17.2 四个可靠性支点

```text
Producer：Outbox + Confirm/Return + attempt 证据
Broker：durable 拓扑 + persistent 消息 + 部署高可用
Consumer：事务后 ACK + 幂等 + 唯一索引
业务：回滚 + DLQ 失败记录 + 对账 + 人工处置
```

### 17.3 四个不能说

- 不能说 Confirm ACK 代表消费成功。
- 不能说 durable Queue 保证任何情况下消息不丢。
- 不能说 RabbitMQ 天然 exactly-once。
- 不能把单元测试通过说成真实 Broker 和高并发验收完成。

### 17.4 一条判断原则

```text
先查订单事实
→ 再查事件状态
→ 再查全部发送尝试
→ 有任何可能投递证据就禁止自动回滚
→ 只有全部明确失败且无订单才允许回滚
```

## 18. 当前证据边界与参考资料

### 18.1 当前证据

- Java 8 全量源码编译通过。
- 185 个单元测试通过，覆盖主要状态机、决策、任务和安全修复。
- 上线迁移步骤 1～2 已于 2026-08-21 在远程环境执行并验证；事件表当时为空。
- Consumer 默认关闭：`SECKILL_RABBIT_CONSUMER_ENABLED=false`。

尚未完成：

- 真实 RabbitMQ Confirm、Return、DLX 目标不可用等故障注入。
- Lua 成功后宕机、回滚 Lua 后宕机等跨存储崩溃演练。
- 重复投递和多实例并发压测。
- RabbitMQ 集群、Quorum Queue、磁盘和告警验证。
- 上线迁移步骤 3～8 及小流量验证。

### 18.2 项目事实来源

- 开发规格：[`10-rabbitmq-seckill-reliability-development-spec.md`](../development/10-rabbitmq-seckill-reliability-development-spec.md)
- 交付报告：[`11-rabbitmq-seckill-reliability-delivery-report.md`](../development/11-rabbitmq-seckill-reliability-delivery-report.md)
- MQ 配置：[`RabbitMqConfig.java`](../../src/main/java/com/dish/review/config/RabbitMqConfig.java)
- 运行参数：[`application.yaml`](../../src/main/resources/application.yaml)
- 事件状态机：[`SeckillOrderEventStateMachine.java`](../../src/main/java/com/dish/review/service/SeckillOrderEventStateMachine.java)

### 18.3 官方资料

- [RabbitMQ Consumer Acknowledgements and Publisher Confirms](https://www.rabbitmq.com/docs/confirms)
- [RabbitMQ Reliability Guide](https://www.rabbitmq.com/docs/reliability)
- [RabbitMQ Queues](https://www.rabbitmq.com/docs/queues)
- [RabbitMQ Dead Letter Exchanges](https://www.rabbitmq.com/docs/dlx)
- [RabbitMQ Quorum Queues](https://www.rabbitmq.com/docs/quorum-queues)
- [Spring AMQP 2.2 Reference](https://docs.spring.io/spring-amqp/docs/2.2.x/reference/pdf/index.pdf)

阅读官方文档时始终先确认 RabbitMQ Server、Java Client 和 Spring AMQP 版本，再把语义映射回当前代码。
