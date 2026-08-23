# RabbitMQ 秒杀源码阅读手册：机制、调用链与必读模块

> 目标：不是把 MQ 代码全部背下来，而是能沿着一次秒杀请求，读懂消息为什么会被发送、如何确认、何时重试、何时回滚，以及并发任务怎样避免互相破坏。
>
> 本文只描述当前项目已经实现的机制。通用 RabbitMQ 理论只在解释源码时出现。

---

## 1. 先建立全局认识

### 1.1 这套 MQ 代码真正解决的是什么

秒杀请求先在 Redis 中扣减库存，订单最后落到 MySQL。两者之间没有一个能同时覆盖 Redis、MySQL 和 RabbitMQ 的本地事务，因此会出现这些窗口：

- Redis 已扣库存，MySQL 事件还没保存；
- 事件已保存，消息还没发送；
- 消息可能已到 Broker，但发送方没收到 Confirm；
- 消息已被消费，但消费结果或事件状态没及时收敛；
- 确认所有发送都失败后，需要把 Redis 预占退回；
- 多实例定时任务可能同时处理同一事件；
- 进程可能在任意两行代码之间崩溃。

项目没有假装这些窗口不存在，而是用以下机制逐步收敛：

| 机制 | 主要作用 | 解决的问题 |
|---|---|---|
| Redis 预占记录 | 保存这次扣库存对应的事件 | MySQL 事件写入失败后仍可对账重建 |
| 本地事件表（Outbox） | 把“应该发消息”持久化为待办 | 应用崩溃后仍能继续发送 |
| 发送尝试表 | 保存每次发送的独立证据 | 不能用“最后一次失败”覆盖早先可能成功的发送 |
| Confirm / Return / 超时回调 | 收集 Broker 接收、路由和未知结果 | 区分 ACK、NACK、退回、结果未知 |
| 决策器 | 汇总订单、事件和全部发送证据 | 决定等待、重试、回滚、标记消费或人工处理 |
| 租约 + token | 多实例抢占任务并隔离过期执行者 | 避免重复并发处理和旧任务释放新租约 |
| 消费事务 + 幂等 | 同一事务扣 MySQL 库存、建单、更新事件 | 重复消息不重复建单，业务数据保持一致 |
| 回滚状态机 | 安全退回 Redis 预占 | 防止回滚与迟到消费者同时成功 |
| 双向对账 | 扫描 Redis 与 MySQL 的残留差异 | 修复异步链路长期未收敛的问题 |

### 1.2 一条主调用链

```text
HTTP 秒杀请求
  -> VoucherOrderServiceImpl.seckillVoucher()
     -> SeckillVoucherLuaExecutor.reserve()       Redis 原子预占
     -> SeckillOrderEventService.createPending()  创建本地事件
     -> 立即返回“已受理”

定时发送任务
  -> SeckillOrderPublishRetryTask.publishDueEvents()
     -> claimLease()                              抢占事件租约
     -> SeckillPublishAttemptService.createNextAttempt()
     -> SeckillOrderPublisher.send()              唯一实际发送入口
        -> RabbitTemplate.convertAndSend()

发送结果
  -> SeckillPublishConfirmHandler                 Confirm
  -> RabbitMqPublisherCallback                    Return
  -> SeckillPublishConfirmTimeoutTask             长时间无结果
  -> SeckillOrderFailureDecisionService           汇总证据并决策

消费
  -> SeckillOrderConsumer.consume()
     -> VoucherOrderHandler.createOrder()
        -> 锁事件、检查状态、扣 MySQL 库存、写订单、标记 CONSUMED

失败收敛
  -> SeckillReservationRollbackTask               确认失败后回滚 Redis
  -> SeckillOrderReconciliationTask               Redis/MySQL 双向对账
  -> DLQ + failure case                           保存失败证据和人工处理入口
```

### 1.3 三个 ID 必须分清

| ID | 粒度 | 用途 |
|---|---|---|
| `eventId` | 一次秒杀业务事件 | 串起 Redis 预占、事件表、消息和最终处理结果 |
| `attemptId` | 一次 MQ 发送动作 | 串起该次发送的 Confirm、Return、超时证据 |
| `orderId` | 订单 | 业务幂等键之一，也是最终成功结果 |

同一个 `eventId` 可以对应多个 `attemptId`。这是理解决策器的关键：重试不是修改原发送记录，而是创建新的一次发送尝试。

---

## 2. 源码阅读分级

### P0：必须精读

按下面顺序读，先不要从配置类开始：

1. [`VoucherOrderServiceImpl`](../../src/main/java/com/dish/review/service/impl/VoucherOrderServiceImpl.java)：请求入口、Redis 预占、事件落库和未知结果边界。
2. [`SeckillOrderPublishRetryTask`](../../src/main/java/com/dish/review/mq/SeckillOrderPublishRetryTask.java)：谁负责挑选事件并触发发送。
3. [`SeckillOrderEventService`](../../src/main/java/com/dish/review/service/SeckillOrderEventService.java)：先理解扫描、基于状态的条件更新、租约和 token，后续才能看懂谁有权发送或改状态。
4. [`SeckillOrderEventStateMachine`](../../src/main/java/com/dish/review/service/SeckillOrderEventStateMachine.java)：允许哪些状态转换。
5. [`SeckillOrderPublisher`](../../src/main/java/com/dish/review/mq/SeckillOrderPublisher.java)：唯一实际调用 `RabbitTemplate` 的模块。
6. [`SeckillPublishConfirmHandler`](../../src/main/java/com/dish/review/mq/SeckillPublishConfirmHandler.java)：Confirm 如何落证据。
7. [`SeckillOrderFailureDecisionService`](../../src/main/java/com/dish/review/service/SeckillOrderFailureDecisionService.java)：何时等、重试、回滚或人工处理。
8. [`SeckillOrderConsumer`](../../src/main/java/com/dish/review/mq/SeckillOrderConsumer.java) 与 [`VoucherOrderHandler`](../../src/main/java/com/dish/review/service/VoucherOrderHandler.java)：消费事务和幂等。

### P1：带着问题读关键方法

- [`SeckillPublishAttemptService`](../../src/main/java/com/dish/review/service/SeckillPublishAttemptService.java)：一次发送尝试如何创建和更新。
- [`RabbitMqPublisherCallback`](../../src/main/java/com/dish/review/mq/RabbitMqPublisherCallback.java)：Return 如何记录。
- [`SeckillPublishConfirmTimeoutTask`](../../src/main/java/com/dish/review/mq/SeckillPublishConfirmTimeoutTask.java)：回调丢失时如何转为 UNKNOWN。
- [`SeckillReservationRollbackTask`](../../src/main/java/com/dish/review/mq/SeckillReservationRollbackTask.java)：Redis 预占怎样安全回滚。
- [`SeckillOrderReconciliationTask`](../../src/main/java/com/dish/review/mq/SeckillOrderReconciliationTask.java)：残留不一致怎样被发现和修复。
- [`RabbitMqConfig`](../../src/main/java/com/dish/review/config/RabbitMqConfig.java)：队列、重试、异常分类和死信配置。
- [`SeckillRabbitListenerErrorHandler`](../../src/main/java/com/dish/review/mq/SeckillRabbitListenerErrorHandler.java) 与 [`SeckillOrderDeadLetterConsumer`](../../src/main/java/com/dish/review/mq/SeckillOrderDeadLetterConsumer.java)：消费失败证据。

### P2：知道职责即可

实体、Mapper、常量、管理接口、失败工单的增删改查不需要逐行读。第 14 节给出一行职责表。

---

## 3. 请求入口：先预占，再记录“待发送事件”

必读：`VoucherOrderServiceImpl.seckillVoucher()`。

### 3.1 实际流程

```text
校验用户与优惠券
  -> 预先生成 eventId、orderId
  -> Redis Lua 原子判断库存/重复下单并预占
  -> 构造 SeckillOrderMessage
  -> MySQL 插入 PENDING 事件
  -> 返回 orderId、voucherId，表示请求已受理
```

这里没有直接发送 MQ。请求线程只负责拿到 Redis 资格，并把后续工作写成可恢复的 MySQL 待办。

### 3.2 Redis 五个预占账本 Key

`seckill.lua` 不只是扣一个库存数字。预占成功时，它会同时维护一组可以发现、定位、重建和回滚事件的账本。

| Key（均拼接 `{voucherId}`） | 类型与内容 | 主要用途 |
|---|---|---|
| `seckill:reservation:{voucherId}` | Hash：`eventId -> orderId\|userId\|createdAt\|messageVersion` | 预留详情；MySQL 事件缺失时，对账任务靠它重建完整消息 |
| `seckill:reservation:user:{voucherId}` | Hash：`userId -> eventId` | 标识用户当前属于哪个预留；回滚/完成脚本用它校验事件归属，防止删错预留 |
| `seckill:reservation:pending:{voucherId}` | ZSet：`eventId -> reservedAt` | 自动对账的发现入口；按时间分数找出长期未收敛的预留 |
| `seckill:reservation:order:{voucherId}` | Hash：`orderId -> eventId` | 订单状态查询的反向索引，不必遍历券或预留 |
| `seckill:reservation:manual:{voucherId}` | ZSet：`eventId -> transferredAt` | 信息损坏的预留移出自动队列后，保留人工处理入口 |

要分清三种职责：

- `pending` ZSet 只负责**发现候选事件**，不是业务真相；
- 预留详情 Hash 提供**重建事件所需的数据**；
- 用户事件映射提供回滚/完成时的**归属校验**。映射不存在表示已经收敛，映射指向其他事件则返回冲突，禁止加回库存或删除别人的预留。

因此不能笼统地说“某一个 Key 是回滚和对账的唯一凭据”。当前源码是多个索引各司其职，以 `eventId` 串联。

正常预占 Lua 实际接收六个 Key：除了上表前四个账本 Key，还包括 `seckill:stock:{voucherId}` 库存和 `seckill:order:{voucherId}` 已下单用户集合；`manual` ZSet 只在异常预留移交人工时使用。

所有相关 Key 都带相同的 `{voucherId}` Hash Tag。Redis Cluster 只对 `{}` 内的内容计算槽位，所以同一张券的 Key 落在同一槽中，Lua 才不会因为跨槽触发 `CROSSSLOT`，并能原子地完成扣库存、写一人一单和写预占账本。

### 3.3 Outbox 在本项目里是什么意思

Outbox 不是 RabbitMQ 的某个组件，而是一种本地消息表模式。本项目中的 `seckill_order_event` 就承担 Outbox 角色：

> 业务线程不要求“现在必须把消息发成功”，只要求把“这个事件以后必须被处理”可靠地写进 MySQL；后台任务再扫描并发送。

因此：

- `createPending()` 成功：事件进入可扫描状态，后台会发送；
- 应用在返回后崩溃：事件仍在 MySQL，重启后可继续；
- RabbitMQ 暂时不可用：请求线程不进行长时间重试；
- MQ 恢复后：发送任务继续处理到期事件。

### 3.4 为什么先生成 `eventId`

Redis 预占时就写入 `eventId`，之后 MySQL 事件和 MQ 消息也使用它。即使 Redis 已扣库存、`createPending()` 却失败，对账任务仍能从 Redis 预占信息重建事件，而不是面对一笔没有身份的库存差额。

### 3.5 为什么事件写入失败时不能立即回滚

此时不能武断地认为整条链路完全失败：调用结果可能未知，或后续对账仍能恢复事件。入口选择保留 Redis 预占，让预占账本和对账机制判断，而不是请求线程立即做可能错误的补偿。

阅读时重点观察：

- `reserve()` 的成功、明确失败和调用异常分别怎样处理；
- `createPending()` 失败后为什么没有直接调用回滚 Lua；
- 状态查询为何按“MySQL 订单 → 事件 → Redis 预占”的顺序判断；
- 依赖不可用时为何返回 `UNAVAILABLE`，而不是误报 `NOT_FOUND`。

---

## 4. 发送模块：谁挑任务，谁真正发消息

这两个职责被刻意拆开：

| 模块 | 职责 | 不负责什么 |
|---|---|---|
| `SeckillOrderPublishRetryTask` | 扫描、抢租约、创建 attempt、安排下次时间、触发发送 | 不理解 RabbitTemplate 回调细节 |
| `SeckillOrderPublisher` | 组装消息属性并执行一次 `convertAndSend` | 不扫描、不自行重试、不直接改变事件状态 |

### 4.1 发送调度器：`SeckillOrderPublishRetryTask`

核心调用链：

```text
publishDueEvents()
  -> findDueForPublish()
  -> publishOneEvent(event)
     -> claimLease(eventId, owner, leaseSeconds)
     -> 检查自动发送次数上限
     -> createNextAttempt(eventId)
     -> publisher.send(attempt, message)
     -> 延后 next_retry_time
     -> finally releaseLease(eventId, leaseToken)
```

`createNextAttempt()` 在同一个 MySQL 事务中：

1. 增加事件的 `retry_count`；
2. 插入一条状态为 `WAITING` 的发送尝试；
3. 为这次尝试生成独立 `attemptId` 和递增的 `attemptNo`。

如果 `send()` 同步抛异常，任务把该 attempt 记录为 `UNKNOWN`，再触发决策器。因为客户端抛异常只能证明本地没有拿到确定结果，不能普遍证明 Broker 一定没收到。

### 4.2 八次发送、七个退避与 90 秒终局窗口

当前发送上限是 **8 次**，准确关系是：

```text
首次发送
  -> 等 1 秒，第 2 次发送
  -> 等 2 秒，第 3 次发送
  -> 等 4 秒，第 4 次发送
  -> 等 30 秒，第 5 次发送
  -> 等 120 秒，第 6 次发送
  -> 等 600 秒，第 7 次发送
  -> 等 1800 秒，第 8 次发送
  -> 退避表耗尽，nextDelaySeconds(8) 返回 -1
  -> 不立刻转人工，改等 90 秒终局窗口
```

所以是“**首次发送 + 7 次重发 = 8 次发送**”，七个数字是相邻两次发送之间的等待，不是总发送次数。

耗尽判断分两步完成：

1. 每次正常发送后，`deferNextRetry()` 根据 `attemptNo` 计算下一次时间。第 8 次发送后得到 `-1`，任务不会再安排第 9 次，而是把 `next_retry_time` 推迟 `FINAL_DECISION_WAIT_SECONDS=90` 秒。
2. 90 秒后，Outbox 扫描再次选中事件。`publishOneEvent()` 抢到租约后、创建新 attempt 前检查 `completedAttempts >= maxAutomaticAttempts()`；如果事件仍处于可发布状态，才调用 `recordManualReviewEscalation()`。

这 90 秒在等三类迟到结果：第 8 次发送的 Confirm/Return、30 秒确认超时任务的 UNKNOWN 处理，以及消费者可能已经完成的订单事务。90 秒必须大于默认 30 秒确认超时，否则 Outbox 可能在超时任务落证据之前就停止自动流程。

最终升级在同一 MySQL 事务中完成两件事：

- 事件转为 `MANUAL_REVIEW`，停止自动发送；
- 写入来源为 `SOURCE_PUBLISH`、错误码为 `publish_retry_exhausted` 的失败单，保证人工处置有入口。

注意：耗尽检查属于 Outbox 扫描任务，但准确位置是 `publishOneEvent()` 抢到租约之后、创建第 9 个 attempt 之前，不是在 `send()` 回调中执行。

### 4.3 唯一实际发送入口：`SeckillOrderPublisher.send()`

它做四件事：

1. 校验 attempt 和 message；
2. 创建包含 `attemptId/eventId/orderId` 的 `SeckillOrderCorrelationData`；
3. 设置消息头、`messageId=eventId` 和持久化投递模式；
4. 调用 `RabbitTemplate.convertAndSend(exchange, routingKey, message, ..., correlationData)`，并把 Confirm Future 交给处理器。

消息头中的 `attemptId` 让 Return 回调可以定位具体发送；`eventId` 让所有模块定位同一业务事件；消息持久化只能提高 Broker 重启后的保留能力，不能替代 Confirm、Outbox 或消费幂等。

### 4.4 为什么实际发送点必须尽量唯一

如果入口服务、重试任务、管理接口各自调用 `RabbitTemplate`，就容易出现：

- 有的发送没有 attempt 记录；
- 有的发送没有绑定 Confirm；
- 回调无法定位发送来源；
- 重试次数和真实发送次数不一致。

当前项目把“执行一次发送”收口在 Publisher，把“是否应该再发送”交给任务和决策器。

---

## 5. 回调机制：只保存证据，不擅自做业务补偿

必须先记住：生产者 Confirm 与消费者 ACK 是两套独立机制。

- Confirm：Broker 告诉生产者，这次发布是否被 Broker 接受；
- Return：消息到达交换机后无法路由到队列；
- Consumer ACK：消费者是否成功处理投递，与生产者 Confirm 不是一回事。

项目中有三条“发送结果”入口：

| 入口 | 触发条件 | attempt 证据 | 后续动作 |
|---|---|---|---|
| `SeckillPublishConfirmHandler` | Confirm Future 完成 | ACK / NACK / UNKNOWN | 调用决策器 |
| `RabbitMqPublisherCallback` | mandatory 消息无法路由 | `returned=true`，保存 reply 信息 | 只补充 Return 证据 |
| `SeckillPublishConfirmTimeoutTask` | WAITING 超过阈值 | UNKNOWN | 调用重试决策 |

### 5.1 Confirm：`SeckillPublishConfirmHandler`

`attach()` 给本次发送的 `CorrelationData` Future 注册处理逻辑。结果分支：

```text
ACK 且没有 ReturnedMessage
  -> attempt 记 ACK
  -> event 尝试转 CONFIRMED

ACK 但已观察到 ReturnedMessage
  -> attempt 同时保留 ACK 与 returned 证据
  -> 交给决策器，不当成可消费成功

NACK 或 confirm 为空
  -> attempt 记 NACK
  -> 交给决策器

Future 异常
  -> attempt 记 UNKNOWN
  -> 触发重试方向的决策
```

为什么 ACK 后还检查 Return？因为“交换机接收发布”不等于“成功路由进目标队列”。项目需要同时保存两类证据。

### 5.2 Return：`RabbitMqPublisherCallback`

该类注册为当前 Spring AMQP 版本使用的 `RabbitTemplate.ReturnCallback`。它从消息头取出 `attemptId/eventId`，记录退回码、退回原因、交换机和 routing key。

它故意不直接把事件改成回滚：

- Confirm 与 Return 是异步到达的；
- 同一个事件可能已有其他 attempt；
- 另一条发送可能已进入队列或已经建单；
- 是否回滚必须看全局证据，而不是一条回调。

### 5.3 回调超时：`SeckillPublishConfirmTimeoutTask`

定时任务扫描长时间处于 `WAITING` 的 attempt，默认约 30 秒后记为 `UNKNOWN`，再调用决策器。

它还覆盖一个容易忽略的崩溃窗口：`createNextAttempt()` 已提交，但进程在真正发送前崩溃。此时不会有 Confirm，也不会有同步异常，只有超时扫描能让记录继续流转。

### 5.4 阅读边界

当前项目使用 Spring AMQP 2.2 风格的 `CorrelationData` Future 与 `ReturnCallback`。阅读新版本文章时，不要直接把 `ReturnsCallback` 等新 API 套进当前源码。真实 Broker 环境还应验证 Confirm、Return 的时序和迟到回调是否最终收敛。

---

## 6. 决策机制：所有证据汇总后才能决定

这是整套代码最应该精读的类：`SeckillOrderFailureDecisionService`。

### 6.1 为什么需要独立决策器

错误做法是：某次发送 NACK，就立即回滚 Redis。

反例：第一次发送其实已经进入队列，但 Confirm 丢失；第二次重试收到 NACK。如果只看最后一次结果并回滚，消费者仍可能建立订单，于是“订单成功 + 库存又退回”。

因此决策器读取一个事件的完整快照：

- MySQL 订单是否已经存在；
- 当前事件状态；
- 所有发送 attempt 的 ACK/NACK/UNKNOWN/WAITING；
- 每次 attempt 是否被 Return；
- 本次触发来自 Confirm 完成还是重试信号。

### 6.2 五种决策

| 决策 | 含义 |
|---|---|
| `MARK_CONSUMED` | 订单已经存在，事件应收敛到已消费 |
| `WAIT` | 仍有可能投递，暂时不能回滚 |
| `RETRY_PUBLISH` | 保留 Redis 预占，安排再次发送 |
| `ROLLBACK` | 所有已知发送都明确失败，可进入回滚流程 |
| `MANUAL_REVIEW` | 证据矛盾或超出自动处理能力 |

### 6.3 当前判断优先级

按源码顺序理解，不要打乱：

1. **订单已存在**：返回 `MARK_CONSUMED`。真实业务结果优先于消息回调。
2. **事件已是 CONSUMED，但订单不存在**：状态与事实矛盾，进入 `MANUAL_REVIEW`。
3. **存在“可能已投递”的 attempt**：不能回滚。重试信号触发时返回 `RETRY_PUBLISH`，Confirm 完成触发时通常先 `WAIT`。
4. **还没有 attempt**：重试信号触发时安排发布，否则等待。
5. **所有 attempt 都是确定失败**：返回 `ROLLBACK`。
6. **无法安全归类**：返回 `MANUAL_REVIEW`。

“可能已投递”主要指：attempt 没有被 Return，且状态是 `ACK`、`WAITING` 或 `UNKNOWN`。“确定失败”需要证据表明每次发送都不可消费，例如被 Return，或明确 NACK。

### 6.4 用四个例子掌握

| 证据 | 决策 | 原因 |
|---|---|---|
| 订单存在，最后一次 NACK | `MARK_CONSUMED` | 业务已完成，不能回滚 |
| attempt=ACK，未 Return，订单暂不存在 | `WAIT` 或后续重试 | 消息可能在队列或消费中 |
| attempt=UNKNOWN，未 Return | `RETRY_PUBLISH` | 可重发，但必须保留预占并依赖消费幂等 |
| 所有 attempt 均 NACK/Return，订单不存在 | `ROLLBACK` | 已没有已投递成功的证据 |

### 6.5 决策与执行分离

`decide(snapshot)` 尽量是纯判断；`applyDecision()` 才调用事件服务执行 CAS 状态转换或创建人工工单。这样做的价值是：

- 决策规则可以用输入快照单测；
- 回调、超时任务可以复用同一套规则；
- 决策器不直接碰 Redis，补偿仍由专门任务执行；
- 状态竞争失败时可以重新读取，而不是覆盖其他线程结果。

---

## 7. 事件状态机与条件更新：状态不是随便 update 的

### 7.1 主要状态

| 状态 | 含义 |
|---|---|
| `PENDING` | 已记录，等待或允许发布 |
| `CONFIRMED` | 已得到可接受的发布确认 |
| `PUBLISH_UNKNOWN` | 发送结果不确定，仍禁止直接回滚 |
| `CONSUMED` | MySQL 订单已落库 |
| `ROLLBACK_PENDING` | 已决定回滚，等待补偿任务 |
| `ROLLBACK_EXECUTING` | 某个带 token 的执行者正在回滚 |
| `ROLLED_BACK` | Redis 预占已退回或确认无需再退 |
| `DLQ` | 消费失败并进入死信处理链路 |
| `MANUAL_REVIEW` | 自动流程停止，等待人工判断 |
| `FAILED` | 遗留兼容状态，不应把它理解成当前唯一失败终态 |

正常成功主线：

```text
PENDING -> CONFIRMED -> CONSUMED
```

发布失败补偿主线：

```text
PENDING/PUBLISH_UNKNOWN
  -> ROLLBACK_PENDING
  -> ROLLBACK_EXECUTING
  -> ROLLED_BACK
```

异常主线最终可能进入 `DLQ` 或 `MANUAL_REVIEW`。

### 7.2 基于当前状态的条件更新

`SeckillOrderEventService.applyCasUpdate()` 不做“无条件把状态设成 X”，而是：

```sql
UPDATE ...
SET status = 目标状态, row_version = row_version + 1, ...
WHERE event_id = ?
  AND status IN (状态机允许的来源状态)
```

具体 SQL 可能按方法拆分，但阅读重点是：允许来源状态、影响行数，以及更新未命中后是否已处于目标状态。

它解决的是并发覆盖：消费者刚把事件改为 `CONSUMED`，迟到的 NACK 回调不能再把它改成回滚状态。更新失败不代表数据库坏了，通常代表其他线程已先完成合法转换，需要重新读取结果。

这里要特别准确：当前普通状态推进以 `status IN (...)` 作为比较条件，同时递增 `row_version`；它没有在 `WHERE` 中比较旧的 `row_version`，因此不要把它讲成“按版本号实现的乐观锁”。租约与回滚流程另有 `lease_token` 条件，用来隔离过期执行者。

### 7.3 状态机类的价值

`SeckillOrderEventStateMachine` 集中描述合法边。业务类先问状态机“允许从哪些来源转到目标”，事件服务再做基于状态的条件更新。新增状态时应该先审查状态图，而不是在各任务里散落 `if`。

---

## 8. 租约机制：多实例怎样抢任务而不互相踩

租约不是业务锁，它是“在一段时间内允许某个执行者处理该事件”的临时所有权。

### 8.1 发布租约

`claimLease(eventId, owner, leaseSeconds)` 的条件包括：

- 状态仍允许发布；
- 现有租约为空或已过期；
- 使用数据库当前时间判断有效期。

抢占成功后写入：

- `lease_owner`：谁抢到；
- `lease_until`：何时自动失效；
- `lease_token`：每次抢占递增；
- `row_version`：事件版本递增。

任务处理结束时执行 `releaseLease(eventId, leaseToken)`，只有 token 仍匹配才能释放。

### 8.2 为什么有 owner 还需要 token

考虑时间线：

```text
T1  worker-A 抢到 token=7，随后长时间暂停
T2  租约过期，另一次任务抢到 token=8
T3  旧 worker-A 恢复，执行 finally 释放租约
```

如果只按 `owner` 释放，尤其同一实例再次抢占时，旧任务可能清掉新租约。token 是栅栏版本：旧执行者拿着 7，无法修改已经属于 8 的租约。

### 8.3 为什么租约必须会过期

进程可能在抢占后崩溃，永久锁会让事件永远无法再处理。租约到期后其他实例可以接管。项目使用 MySQL 时间，减少不同应用实例时钟漂移带来的判断分歧。

### 8.4 回滚租约比发布租约更严格

`claimForRollback()` 不只是加租约，还把状态从 `ROLLBACK_PENDING` 改成 `ROLLBACK_EXECUTING` 并返回 token。最终 `markRolledBack()` 必须同时满足：

- 状态仍是 `ROLLBACK_EXECUTING`；
- token 与本次执行者一致。

这是为了保护“Lua 已恢复库存，但 MySQL 尚未写成 ROLLED_BACK”的危险窗口。

---

## 9. 消费机制：真正的业务一致性在 MySQL 事务里

### 9.1 Listener 只做入口控制

`SeckillOrderConsumer.consume()`：

1. 校验消息和版本；
2. 调用 `VoucherOrderHandler.createOrder()`；
3. 只有确认是同一订单已存在时，才把 `DuplicateKeyException` 当成幂等成功；
4. 其他异常继续抛出，交给容器重试/死信机制。

不要把所有唯一键冲突都吞掉。否则数据模型中的其他冲突也会被伪装成“重复消费成功”。

### 9.2 `VoucherOrderHandler.createOrder()` 必读顺序

该方法在一个 MySQL 事务内完成：

```text
SELECT event FOR UPDATE
  -> event 不存在：一致性异常
  -> 已 CONSUMED：幂等返回
  -> 已 ROLLED_BACK：拒绝迟到消息
  -> ROLLBACK_EXECUTING：抛可重试异常，等待回滚结束
  -> ROLLBACK_PENDING：先 CAS 取消回滚，抢消费权
  -> 查询订单是否已存在
  -> MySQL 条件扣库存（stock > 0）
  -> 插入订单
  -> 事件改为 CONSUMED
  -> 提交事务
```

这里同时使用了三层保护：

- 事件行锁与状态机：协调消费和回滚；
- 订单唯一约束/存在性检查：处理重复消息；
- `stock > 0` 条件更新：MySQL 最终写入时不超卖。

### 9.3 为什么 Redis 扣过一次，MySQL 还要扣库存

Redis 是高并发资格层，MySQL 是最终业务事实。异步链路、缓存重建、人工操作都可能引入差异，因此落单时仍由 MySQL 做条件扣减，不能把 Redis 当成永久唯一真相。

### 9.4 `acknowledge-mode: auto` 不等于 RabbitMQ 协议 autoAck

在 Spring Listener 容器语义下，方法正常返回后容器才 ACK；方法抛异常则进入重试或拒绝流程。它不是“消息一送到客户端就无条件确认”的 fire-and-forget。

当前主要消费参数可在配置中看到：并发消费者、最大并发、prefetch、是否默认重新入队，以及约 `1/2/4` 秒的本地重试。理解参数作用即可，不必背数字。

---

## 10. 消费失败、重试与 DLQ

### 10.1 `RabbitMqConfig` 只精读这些位置

不需要逐行背 Bean，重点找：

1. 主交换机、主队列、DLX、DLQ 的 durable 声明与绑定；
2. JSON 消息转换器；
3. 主 Listener Factory 的重试 Advice；
4. 哪些异常被判定为永久失败，哪些允许重试；
5. `MessageRecoverer` 如何做到“先保存失败证据，再拒绝进入 DLQ”；
6. DLQ Listener Factory 为什么移除业务重试 Advice。

### 10.2 异常分类

永久消息错误、一致性冲突、转换失败等不适合原地反复重试；临时数据库或网络异常默认允许短重试。重试耗尽后：

```text
构造 failure evidence
  -> 持久化 failure case / 更新事件
  -> 持久化成功：RejectAndDontRequeue，进入 DLQ
  -> 持久化失败：ImmediateRequeue，避免消息丢失且没有审计记录
```

设计重点是顺序：不能先把消息扔进 DLQ，再发现失败原因没有保存下来。

### 10.3 两个辅助 Listener

- `SeckillRabbitListenerErrorHandler`：处理消息还没进入业务 Listener 就发生的转换/参数错误；也是先保存证据，再决定拒绝或重新入队。
- `SeckillOrderDeadLetterConsumer`：补充 `x-death` 和 DLQ 到达证据；若订单后来已经成功，可关闭失败工单。它不负责直接回滚，也不是自动重放器。

DLQ 的含义是“需要隔离和调查”，不是“消息自然失败后就安全结束”。

---

## 11. 回滚机制：只有确定安全时才退 Redis

`SeckillReservationRollbackTask` 处理 `ROLLBACK_PENDING` 事件。

核心流程：

```text
扫描待回滚事件
  -> 再查一次 MySQL 订单
     -> 已有订单：markConsumed，禁止回滚
  -> claimForRollback()，进入 EXECUTING 并取得 token
  -> seckill_rollback.lua 按 eventId 回滚 Redis 预占
     -> 已恢复或本来就不存在：markRolledBack(token)
     -> 库存键缺失/数据冲突/调用异常：记录失败并按策略重试
  -> 超过自动能力：MANUAL_REVIEW
```

### 为什么需要 `ROLLBACK_EXECUTING`

危险窗口是：Lua 已把库存加回，但数据库还没写 `ROLLED_BACK`。如果消费者此时照常建单，会造成订单成功而库存也被恢复。

消费者读到 `ROLLBACK_EXECUTING` 时会等待/重试；读到 `ROLLED_BACK` 时拒绝迟到消息。若还是 `ROLLBACK_PENDING`，消费者必须先用 CAS 取消回滚，谁先完成状态转换谁获得执行权。

### 为什么 Lua 要按 `eventId` 幂等

回滚任务也可能重复执行。Lua 根据预占记录判断：同一事件已经回滚时不能再次加库存。返回“已恢复”和“无需恢复”都可视为补偿完成；数据冲突则不能自作主张，必须记录并升级。

---

## 12. 对账机制：异步系统最后一道收敛网

`SeckillOrderReconciliationTask` 不在正常请求主链上，但用于修复长期残留。只需理解两条方向。

### 12.1 Redis → MySQL

“扫描 Redis 预占”不是遍历所有 Redis Key，而是分两层找到候选券，再读取每张券的 `pending` ZSet：

```text
快速入口（每 60 秒）
  -> MySQL 查询 end_time >= now - 7 天的券
     （包含未结束的券，也包含最近 7 天已经结束的券；不限制 begin_time）
  -> 对每张券读取 pending ZSet 中 score <= now - 30 分钟的 eventId
  -> 每张券单批最多处理配置数量

安全兜底（默认每小时整点）
  -> 按 voucherId 游标分页遍历全部 tb_seckill_voucher
  -> 对每张券执行同一个“预留超过 30 分钟”过滤
```

对应 Redis 操作在 Spring Data Redis 中是 `rangeByScore(pendingKey, 0, reservedBeforeMillis, 0, limit)`，等价于按时间分数读取 `0 ~ (now-30min)` 的最早一批预留。

为什么等 30 分钟：正常事件发布、Confirm、消费和预留清理都需要时间，刚写入的预留不能马上被当成孤儿；超过阈值仍留在 `pending`，才值得进入修复检查。这个阈值是“开始检查”，不是“直接回滚”——任务还会继续查询订单和事件状态。

为什么需要两个入口：7 天回看让每分钟任务保持可控；如果故障持续超过 7 天，历史券会退出快速候选范围，所以每小时游标分页扫描全表负责最终兜底。源码明确禁止用 Redis `KEYS` 或全量 `SCAN` 代替。

找到候选预留后：

- 订单存在：必要时补事件为 `CONSUMED`，并完成/清理预占；
- 订单不存在、事件也不存在：根据有效预占信息重建 `PENDING` 事件；
- 预占内容损坏：保存失败证据并进入人工处理；
- 事件已存在：交回正常状态机继续处理。

这条链修复“Redis 已扣，但 MySQL 事件没有成功创建”的窗口。

### 12.2 MySQL → Redis

扫描事件：

- `CONSUMED`：确保 Redis 预占被完成；
- `ROLLED_BACK`：确认预占已移除；
- 长时间卡在 `ROLLBACK_EXECUTING`：结合预占是否仍存在决定收敛到已回滚或重新待回滚；
- 长期 `PUBLISH_UNKNOWN`：证据不足时升级人工处理。

对账不是用来替代正常事务和回调，而是处理“所有实时机制都可能在某个窗口失败”的现实。

---

## 13. 两条完整时间线

### 13.1 正常成功

```text
1. 请求生成 eventId/orderId
2. Redis Lua 扣库存并写预占
3. MySQL 创建 PENDING 事件
4. 请求返回已受理
5. 发布任务抢租约，创建 attempt#1(WAITING)
6. Publisher 发送消息
7. Broker Confirm ACK，事件可转 CONFIRMED
8. Consumer 锁事件、扣 MySQL 库存、插订单、改 CONSUMED
9. Listener 正常返回，容器 ACK
10. 对账/完成脚本清理 Redis 预占
```

### 13.2 Confirm 丢失后重发

```text
1. attempt#1 可能已经进入队列，但没有收到 Confirm
2. 超时任务把 attempt#1 记 UNKNOWN
3. 决策器发现“可能已投递”，保留 Redis 预占并允许重发
4. 创建 attempt#2，再次发送同一个 eventId/orderId
5. 两条消息都到消费者也没关系：订单唯一约束 + 事件状态保证幂等
6. 任意一条先建单，事件进入 CONSUMED
7. 后续 NACK、Return 或重试任务看到订单存在，都应收敛到 MARK_CONSUMED
```

这就是“至少一次投递 + 业务幂等”，不是“消息绝不重复”。

---

## 14. 不必逐行读的文件

| 文件/模块 | 知道这一点即可 |
|---|---|
| `SeckillOrderMessage` | MQ 业务载荷及版本字段 |
| `SeckillOrderCorrelationData` | 把 attempt/event/order 与 Confirm Future 关联 |
| `RabbitMqConstants` | 交换机、队列、routing key、消息头名称的集中定义 |
| `SeckillOrderEvent` | Outbox 事件数据模型与状态常量 |
| `SeckillPublishAttempt` | 单次发送的 ACK/NACK/UNKNOWN/Return 证据 |
| Event/Attempt Mapper | 条件更新、扫描和 `FOR UPDATE` 的 SQL 入口；遇到状态问题再下钻 |
| `SeckillFailureEvidence` | 从异常和消息头构造可审计证据 |
| `SeckillFailureCaseService` | 失败工单、重试安排、人工升级与关闭 |
| `SeckillOrderFailureAdminService` | 人工处理失败工单的管理入口 |
| `SeckillPublishRetryPolicy` | 发布次数、退避和最终等待窗口 |
| `SeckillRollbackRetryPolicy` | 回滚失败后的退避和上限 |
| `SeckillStockInitScanTask` | Redis 秒杀库存初始化/修复扫描，不属于消息可靠投递主线 |
| `VoucherOrderController` | HTTP 入口，MQ 机制很少 |

Lua 文件只需按需要读：

- [`seckill.lua`](../../src/main/resources/seckill.lua)：资格判断、扣库存、写预占；
- [`seckill_rollback.lua`](../../src/main/resources/seckill_rollback.lua)：按事件幂等回滚；
- [`seckill_reservation_complete.lua`](../../src/main/resources/seckill_reservation_complete.lua)：订单完成后收尾预占；
- 其他初始化/人工脚本不属于第一次阅读主线。

---

## 15. 推荐的源码阅读法

不要一次打开所有 MQ 类。按 6 轮读，每轮只回答一个问题。

### 第 1 轮：消息从哪里来

读：`VoucherOrderServiceImpl` → `SeckillOrderPublishRetryTask` → `SeckillOrderEventService` 的扫描/租约/推迟时间方法 → `SeckillOrderPublisher`。

回答：请求线程为什么不发送？谁扫描 Outbox？哪里真正调用 RabbitTemplate？

### 第 2 轮：发送结果怎样记录

读：`SeckillPublishAttemptService` → `SeckillPublishConfirmHandler` → `RabbitMqPublisherCallback` → `SeckillPublishConfirmTimeoutTask`。

回答：ACK、NACK、Return、UNKNOWN 分别写到哪里？为什么一个事件有多条 attempt？

### 第 3 轮：为什么不能随便回滚

精读：`SeckillOrderFailureDecisionService`。

回答：订单存在时谁优先？什么叫可能已投递？什么条件才能回滚？

### 第 4 轮：并发怎样控制

读：`SeckillOrderEventStateMachine` → `SeckillOrderEventService` 中的条件更新、`claimLease/releaseLease/claimForRollback`。

回答：基于来源状态的条件更新为什么能挡住迟到回调？token 防住了哪种旧执行者？

### 第 5 轮：消费怎样落单

读：`SeckillOrderConsumer` → `VoucherOrderHandler`。

回答：重复消息怎样幂等？消费和回滚怎样争夺执行权？哪些动作在同一事务？

### 第 6 轮：异常怎样最终收敛

选择性读：`RabbitMqConfig` → `SeckillReservationRollbackTask` → `SeckillOrderReconciliationTask` → DLQ 两个类。

回答：短重试、重新发布、回滚、对账、人工处理各负责哪一层？

---

## 16. 用测试代替猜测

读完一个机制，立刻读对应测试；测试比注释更接近可执行行为规范。

P0 测试：

- [`VoucherOrderServiceImplTests`](../../src/test/java/com/dish/review/service/VoucherOrderServiceImplTests.java)：入口异常和 Redis/MySQL 窗口；
- [`SeckillOrderPublishRetryTaskTests`](../../src/test/java/com/dish/review/mq/SeckillOrderPublishRetryTaskTests.java)：租约、发送尝试和重试；
- [`SeckillOrderEventServiceTests`](../../src/test/java/com/dish/review/service/SeckillOrderEventServiceTests.java)：基于状态的条件更新、扫描与租约；
- [`SeckillOrderEventStateMachineTests`](../../src/test/java/com/dish/review/service/SeckillOrderEventStateMachineTests.java)：合法状态边；
- [`VoucherOrderHandlerTests`](../../src/test/java/com/dish/review/service/VoucherOrderHandlerTests.java)：消费幂等和回滚竞争。

P1 测试：

- `SeckillReservationRollbackTaskTests`：补偿执行窗口；
- `SeckillOrderReconciliationTaskTests`：双向对账；
- `SeckillRabbitListenerErrorHandlerTests`、`SeckillOrderDeadLetterConsumerTests`：错误证据与 DLQ；
- 两个 RetryPolicy 测试：次数和退避边界。

单元测试能证明分支逻辑符合预期，但不能替代真实 RabbitMQ 集成验证。尤其需要在真实 Broker 下验证：mandatory Return、Confirm 时序、消费者重试、DLQ 路由、应用重启和迟到回调。

---

## 17. 读完后应该能回答

1. 为什么 Redis 扣库存后不直接在请求线程发送 MQ？
2. 本项目的 Outbox 是哪张数据模型，谁扫描它？
3. 哪个类是唯一实际发送入口？`eventId` 与 `attemptId` 有什么区别？
4. Confirm ACK、Return、Consumer ACK 各证明了什么，不能证明什么？
5. Confirm 丢失为什么记 UNKNOWN，而不是直接判失败？
6. 为什么某次 NACK 不能立即回滚 Redis？
7. 决策器在什么条件下选择 WAIT、RETRY、ROLLBACK、MARK_CONSUMED？
8. 基于来源状态的条件更新如何阻止迟到回调覆盖消费成功？
9. 租约过期解决什么问题？`lease_token` 又解决什么问题？
10. 消费者如何协调 `ROLLBACK_PENDING`、`ROLLBACK_EXECUTING` 和 `ROLLED_BACK`？
11. 为什么 MySQL 仍要做 `stock > 0` 的条件扣减？
12. DLQ 消费者为什么不直接回滚或自动重放？
13. Redis 已预占但 MySQL 没事件时，哪个模块负责修复？
14. 哪些结论来自单元测试，哪些仍需要真实 RabbitMQ 环境验证？
15. 第 8 次发送后事件为什么不立刻转人工？90 秒终局窗口在等什么？
16. 对账任务如何发现孤儿预留？为什么既要等 30 分钟，又要有 7 天快速回看和每小时全量分页兜底？
17. Redis 五个预占账本 Key 各做什么？哪个负责发现、哪个提供重建详情、哪个校验回滚归属？

如果这 17 个问题能结合具体类和方法回答，你已经抓住了项目 MQ 代码的主干；其余 Mapper、实体和管理代码可以在排查具体问题时再读。

---

## 18. 官方语义参考

只在源码行为有疑问时查，不建议先通读：

- [RabbitMQ — Consumer Acknowledgements and Publisher Confirms](https://www.rabbitmq.com/docs/confirms)
- [RabbitMQ — Reliability Guide](https://www.rabbitmq.com/docs/reliability)
- [Spring AMQP 2.2 Reference](https://docs.spring.io/spring-amqp/docs/2.2.x/reference/html/)

阅读官方文档时始终区分：Broker 发布确认、无法路由退回、消费者处理确认，以及项目自己的业务状态机。这四层不能互相替代。
