# RabbitMQ 秒杀可靠性闭环交付报告

> 对应开发规格：[`10-rabbitmq-seckill-reliability-development-spec.md`](10-rabbitmq-seckill-reliability-development-spec.md)
>
> 交付日期：2026-08-21（当日完成三轮验收问题修复，见第 8、9、10 节）
>
> 文档性质：交付报告。当前状态：**代码初版完成、验收问题已全部修复并通过 185 项单元测试**；真实 RabbitMQ 故障演练、跨存储崩溃窗口演练和并发压测仍未执行，复验前不建议部署或开启消费者。

## 1. 实施概览

按规格第 17 节分阶段完成了阶段 1-6 的全部代码实现和单元测试，阶段 7 的文档同步随本报告完成。规格第 18.4/18.5 节（RabbitMQ 故障注入、跨存储崩溃窗口）需要真实环境，未执行。

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| 阶段 1 | 数据模型和状态机（迁移 SQL、实体、Mapper、10 态状态机、CAS、租约、终态保护） | 完成 |
| 阶段 2 | Redis 预留账本（ID 前置、六 Key 同槽含 orderId 反向索引、预留/事件级回滚/预留完成/安全初始化四个 Lua） | 完成 |
| 阶段 3 | Outbox 和发布尝试（请求线程移除直接发布、租约抢占、attemptId 证据、禁用模板重试） | 完成 |
| 阶段 4 | Confirm/Return 和失败决策（回调只落证据、确认超时任务、统一决策服务） | 完成 |
| 阶段 5 | 消费、DLQ 和回滚（异常三分类、监听前 ErrorHandler、先落记录再拒绝的 Recoverer、DLQ 消费者、持久化回滚任务） | 完成 |
| 阶段 6 | 对账、查询和监控（三类对账、六态订单状态接口、失败处置 Service + 审计、库存缺失扫描、告警日志） | 完成 |
| 阶段 7 | 文档同步（README、09 流程文档、08 面试文档、本交付报告） | 完成 |
| 第一轮验收 | 2026-08-21 可靠性验收：5 个 P1 关键问题，验收不通过 | 已全部修复（见第 8 节） |
| 真实故障验收 | 规格 18.4/18.5 节测试矩阵 | 未执行（需真实环境） |

## 2. 修改文件清单

### 2.1 新增：数据模型与迁移

| 文件 | 说明 |
| --- | --- |
| `db/migration/20260821_seckill_reliability_upgrade.sql` | 事件表新增列（回滚计数、租约三列、row_version、错误码、三个时间戳）、`tb_seckill_publish_attempt`、`tb_seckill_failure_case`、任务扫描组合索引 |
| `db/migration/20260822_add_seckill_failure_audit.sql` | `tb_seckill_failure_audit` 失败处置审计表 |
| `entity/SeckillPublishAttempt.java` + Mapper | 发布尝试证据实体 |
| `entity/SeckillFailureCase.java` + Mapper | 失败记录实体（idempotency_key 防重） |
| `entity/SeckillFailureAudit.java` + Mapper | 人工处置审计实体 |

### 2.2 新增：Lua 脚本

| 文件 | 说明 |
| --- | --- |
| `seckill_reservation_complete.lua` | 预留完成：校验映射、删账本、保留一人一单集合、幂等 |
| `seckill_stock_init.lua` | 库存安全原子初始化：已存在幂等、有历史数据冲突、全无才写入 |

### 2.3 新增：服务与任务

| 文件 | 说明 |
| --- | --- |
| `service/SeckillOrderEventStateMachine.java` | 10 态合法迁移表 + 终态保护 |
| `service/SeckillPublishAttemptService.java` | 发布尝试落库与查询 |
| `service/SeckillFailureCaseService.java` | 失败记录幂等落库、关闭、标记重放 |
| `service/SeckillOrderFailureDecisionService.java` | 统一失败决策（纯决策函数 + 执行层） |
| `service/SeckillOrderFailureAdminService.java` | 人工重放/回滚/关闭 + 审计（无 Controller，待 RBAC） |
| `service/SeckillPublishRetryPolicy.java` | 发布退避（1/2/4 秒快速 + 30s/2m/10m/30m 慢速 + 上限） |
| `service/SeckillRollbackRetryPolicy.java` | 回滚退避与上限 |
| `mq/SeckillPublishConfirmHandler.java` | Confirm Future + ReturnedMessage 统一结果处理 |
| `mq/SeckillPublishConfirmTimeoutTask.java` | WAITING 尝试确认超时扫描 |
| `mq/SeckillReservationRollbackTask.java` | 持久化回滚任务（CAS 抢占、事件级回滚 Lua、退避、上限转人工） |
| `mq/SeckillOrderReconciliationTask.java` | 双向对账（Redis 预留↔MySQL 事件 + 库存账面一致性） |
| `mq/SeckillStockInitScanTask.java` | 缺失库存扫描 + 安全初始化 + 一致性指标 |
| `mq/SeckillOrderDeadLetterConsumer.java` | DLQ 消费者（x-death 补充证据、幂等关闭） |
| `mq/SeckillRabbitListenerErrorHandler.java` | 监听前转换失败的容器 ErrorHandler（先落记录再拒绝） |
| `mq/SeckillFailureEvidence.java` | 失败证据提取（受限长度摘要） |
| `exception/`（三个异常类） | `SeckillRetryableException`、`SeckillPermanentMessageException`、`SeckillConsistencyException` |

### 2.4 修改：既有代码重写

| 文件 | 变更 |
| --- | --- |
| `VoucherOrderServiceImpl` | ID 前置到 Lua 前；写预留账本；移除直接发布和直接回滚；新增 `queryOrderStatus` 多源裁决 |
| `VoucherOrderController` | 新增 `GET /voucher-order/status/{voucherId}/{orderId}`（推荐）与兼容接口 `GET /voucher-order/status/{orderId}` |
| `SeckillVoucherLuaExecutor` | 六 Key 同槽；预留/事件级回滚/完成/安全初始化接口；`findReservationEventId(voucherId, orderId)`；Java 8 类型兼容 |
| `seckill.lua` | 一次原子完成预扣 + 四份账本写入（含 orderId 反向索引 Hash） |
| `seckill_rollback.lua` | 按 eventId + userId 校验；冲突返回 -2；幂等返回 0 |
| `SeckillOrderEvent` / `SeckillOrderEventService` / Mapper | 新状态、CAS（applyCasUpdate + row_version）、租约、终态保护、`markReplayedForManualRetry` |
| `SeckillOrderPublisher` | 每次只发送一次，使用 attemptId |
| `SeckillOrderCorrelationData` | 关联 attemptId 和 eventId |
| `RabbitMqPublisherCallback` | 只落发送证据，移除直接回滚 |
| `SeckillOrderPublishRetryTask` | 改造为唯一 Outbox 发布器（CAS + 租约 + 尝试记录） |
| `SeckillOrderConsumer` / `VoucherOrderHandler` | 异常三分类；事务内 CAS 锁定事件状态；ROLLBACK_PENDING 取消回滚；DuplicateKey 幂等 |
| `RabbitMqConfig` | 禁用生产者模板重试；装配 ErrorHandler 和分类 Recoverer |
| `VoucherServiceImpl` | Lua 安全原子初始化；冲突写失败记录转人工 |
| `RabbitMqConstants` / `RedisConstants` | 预留账本 Key 常量（同 hash tag） |
| `application.yaml` | `dish-review.seckill.*` 全部任务参数（批次、租约、超时、退避、阈值、重放上限），均支持 `SECKILL_*` 环境变量 |

### 2.5 新增：单元测试

| 测试类 | 覆盖 |
| --- | --- |
| `SeckillOrderEventServiceTests` | 状态机全部允许/禁止迁移、CAS、租约、迟到回调不覆盖终态 |
| `VoucherOrderHandlerTests` | 消费事务状态保护、异常分类、DuplicateKey 幂等、回滚并发取消 |
| `SeckillOrderFailureAdminServiceTests` | 重放/回滚/关闭 + 审计 + 重放上限 + 订单存在幂等关闭 |
| `VoucherOrderServiceImplTests` | 订单状态查询六态、用户隔离、依赖故障返回 UNAVAILABLE |
| `VoucherServiceImplTests` | 库存安全初始化幂等/冲突/成功 |
| `SeckillReservationRollbackTaskTests` | 回滚任务扫描、CAS 抢占、Lua 结果分支、订单存在收敛 CONSUMED |
| `SeckillOrderReconciliationTaskTests` | 孤儿预留补建、事件→预留收敛、库存账面一致性 |
| `SeckillStockInitScanTaskTests` | 缺失库存扫描、初始化补偿、一致性失败记录 |
| `SeckillOrderDeadLetterConsumerTests` | x-death 补充、幂等关闭、前置记录缺失补建 |
| `SeckillRabbitListenerErrorHandlerTests` | 监听前转换失败先落记录、持久化失败重新入队 |
| `SeckillFailureEvidenceTests` | 证据提取与受限长度 |
| `SeckillPublishRetryPolicyTests` | 退避下标（首退避 1 秒）、耗尽停止、终局等待窗口（第 8 节验收修复） |
| `SeckillOrderEventStateMachineTests` | PENDING/PUBLISH_UNKNOWN → DLQ 新增迁移、非法迁移拒绝（第 8 节验收修复） |
| `SeckillOrderPublishRetryTaskTests` | 耗尽检查在扫描阶段执行、终局窗口未到不转人工、事务性升级（第 8 节验收修复） |
| `SeckillFailureCaseServiceTests` | 人工升级幂等落库、REPLAYED/CLOSED 再次失败重新开启（第 8 节验收修复） |

### 2.6 文档同步

| 文件 | 变更 |
| --- | --- |
| `README.md` | 项目简介、核心功能、技术栈、数据库表、迁移脚本、API（状态查询）、设计亮点、测试、FAQ |
| `docs/learning/09-rabbitmq-seckill-flow.md` | 全文重写为可靠性闭环版（18 节） |
| `docs/learning/08-interview-and-secondary-development-guide.md` | 调用链、表约束、Q5/Q33/Q44/Q48、6.1 节新增 R10-R18、简历模板、P1 清单、源码地图、更新记录 |
| `docs/development/11-...-delivery-report.md` | 本报告 |

## 3. 状态、表结构与接口变化

### 3.1 事件主状态（10 态）

`PENDING / CONFIRMED / CONSUMED / FAILED(遗留) / PUBLISH_UNKNOWN / ROLLBACK_PENDING / ROLLBACK_EXECUTING / ROLLED_BACK / DLQ / MANUAL_REVIEW`

自动终态：CONSUMED、ROLLED_BACK。人工终态：MANUAL_REVIEW（允许审核后重放或回滚）。

### 3.2 新表

- `tb_seckill_publish_attempt`：每次实际发送一行，`uk(event_id, attempt_no)`；Confirm 状态、是否 Return、同步异常、时间戳。
- `tb_seckill_failure_case`：`idempotency_key` 唯一；source（PUBLISH/CONSUMER_DLQ/ROLLBACK/RECONCILE）、status、payload、x_death、replay_count。
- `tb_seckill_failure_audit`：操作者、动作、原因、时间。

### 3.3 新接口

`GET /voucher-order/status/{voucherId}/{orderId}`（推荐，券维度反向索引直达）与兼容接口 `GET /voucher-order/status/{orderId}` → `PROCESSING / SUCCESS / FAILED / MANUAL_REVIEW / NOT_FOUND / UNAVAILABLE`（仅本人）。

### 3.4 Redis Key（同 hash tag `{voucherId}`）

库存 String、用户 Set、预留详情 Hash、用户事件映射 Hash、待对账 ZSet、orderId 反向索引 Hash（field=orderId，value=eventId）。

## 4. 编译与测试证据

```bash
# 编译（IntelliJ 内置 Maven + JDK 8）
mvn clean compile                                    # PASS

# 全量测试（第三轮验收修复后复验，2026-08-21）
mvn clean test                                       # 185 tests, 185 passed
```

| 项目 | 结果 |
| --- | --- |
| Java 8 主源码编译 | PASS |
| Java 8 测试源码编译 | PASS |
| 单元测试（最终代码复验） | 185 执行 / 185 通过 / 0 失败 |
| 测试环境隔离 | test profile 禁用全部秒杀定时任务，测试不再触碰远程 MySQL/Redis |
| 迁移 SQL | 静态检查通过；已于 2026-08-21 获授权在远程环境（`dish_review` @ 115.29.220.133，MySQL 8.0.46）在线执行并验证 |

> 说明：第一轮验收时 `SecurityFixTests.testPathTraversalDetection` 因 Windows `..\` 分隔符在 macOS 不被识别而失败；本轮已在 `UploadController` 中统一归一化反斜杠后修复，现为 10/10 通过。

## 5. 仍然存在的故障窗口与未执行项

1. **真实 RabbitMQ 故障注入未执行**（规格 18.4）：交换机不存在、routing key 错误、Confirm 丢失、DLX 目标不可用、失败记录库不可用时的 Recoverer 重入等场景。
2. **跨存储崩溃窗口未真实演练**（规格 18.5）：Lua 成功后进程终止、回滚 Lua 后进程终止等；当前仅单元测试覆盖逻辑分支。
3. **并发压测未执行**：多实例 CAS 竞争、重复投递下的唯一索引拦截、消费吞吐。
4. **上线迁移已执行（规格 17.1 步骤 1-2，2026-08-21）**：已按顺序完成备份事件表（`tb_seckill_order_event_bak_20260821`）→ 执行两个新迁移 → 验证表结构（9 个新列、组合索引 `idx_seckill_order_event_task`、三张新表 `tb_seckill_publish_attempt` / `tb_seckill_failure_case` / `tb_seckill_failure_audit` 全部就位）。事件表当前 0 行，无存量 FAILED 事件需转 MANUAL_REVIEW，步骤 3-8（暂停入口、存量核对、Redis 用户集合补录、兼容部署、小流量验证）随真实流量上线前逐步执行。
5. **失败处置无 Controller**：项目没有 RBAC，重放/回滚/关闭接口禁止暴露公网；Service 层已就绪并通过测试。
6. **部署可靠性前提未逐项确认**（规格 19.1）：Redis 持久化策略、RabbitMQ 集群/quorum queue、心跳配置、磁盘告警。
7. **消费者默认关闭**：`SECKILL_RABBIT_CONSUMER_ENABLED=false`，需真实连通性验收后启用。

## 6. 需要人工执行的 SQL 与环境配置

```bash
# 1. 执行迁移 —— ✅ 已于 2026-08-21 在远程环境（115.29.220.133/dish_review）执行并验证：
#    - 迁移前已备份：tb_seckill_order_event → tb_seckill_order_event_bak_20260821（0 行）
#    - 事件表新增 9 列 + 组合索引 idx_seckill_order_event_task 已确认
#    - 三张新表 tb_seckill_publish_attempt / tb_seckill_failure_case / tb_seckill_failure_audit 已确认
#    其他环境首次部署仍需执行：
mysql -u root -p dish_review < src/main/resources/db/migration/20260821_seckill_reliability_upgrade.sql
mysql -u root -p dish_review < src/main/resources/db/migration/20260822_add_seckill_failure_audit.sql

# 2. 上线前按规格 17.1 节顺序处理存量数据（旧 FAILED → MANUAL_REVIEW 等）
#    —— 远程环境事件表为空，当前无存量数据需处理；产生流量后若有存量 FAILED 事件再按顺序执行

# 3. 启用消费者（真实连通性验收后）
export SECKILL_RABBIT_CONSUMER_ENABLED=true
```

## 7. 结论

规格阶段 1-7 的代码实现、单元测试和文档同步完成；三轮可靠性验收共 13 项关键问题（第一轮 6 项见第 8 节，第二轮 4 个 P1 见第 9 节，第三轮 3 个 P1 见第 10 节）及最终 Lua 移交顺序问题已全部修复，185 个单元测试全部通过。按规格第 20 节纪律，在真实故障验收完成前，不得宣称"不丢消息""高并发零超卖"或"生产可用"。

面试/简历准确表述：

> 秒杀链路采用 Redis Lua 原子预留（六 Key 同槽预留账本，含 orderId 反向索引 Hash）、MySQL 事件 Outbox、RabbitMQ 至少一次投递、数据库幂等消费、持久化回滚和双层定时对账（7 天快速扫描 + 全量分页兜底）实现最终一致性；对结果未知的发送不直接恢复库存，而是依据订单、发送尝试和预留记录完成状态判定。

## 8. 第一轮验收问题修复记录（2026-08-21）

验收结论：5 个 P1 关键问题 + 1 项转人工失败记录缺失，验收不通过。以下为逐项修复内容。

### 8.1 测试环境隔离（P1）

- 新增 `src/test/resources/application-test.yaml`：`dish-review.seckill.tasks-enabled=false`。
- 所有加载完整 Spring 上下文的测试类标注 `@ActiveProfiles("test")`，Outbox 发布、确认超时、持久化回滚、双向对账、库存扫描任务在测试中全部关闭，`mvn test` 不再访问或修改远程 MySQL/Redis 业务状态。
- 主配置 `application.yaml` 保持 `matchIfMissing=true`，生产默认开启不变。

### 8.2 发布次数与终局决策窗口（P1）

- `SeckillPublishRetryPolicy.nextDelaySeconds()` 改用 `completedAttempts - 1` 作下标：首次发送后退避 1 秒（此前误为 2 秒），与文档声明一致。
- 新增 `FINAL_DECISION_WAIT_SECONDS = 90`（> 确认超时 30 秒）：最后一次发送后事件仍可被扫描到，说明 Confirm/消费/失败决策均未推进状态，等待窗口过后才允许转人工。
- `SeckillOrderPublishRetryTask` 把耗尽检查从"发送前"移到"扫描阶段抢占租约后"：最后一次发送不再被立即转人工，异步 Confirm 仍有机会把状态推进为 CONFIRMED/CONSUMED。

### 8.3 转人工路径事务性创建失败记录（P1）

- `SeckillFailureCaseService.recordManualReviewEscalation()`：同一事务内 `markManualReview` + 写入 `SOURCE_PUBLISH` 失败记录，幂等键 `source:MANUAL:eventId`。
- 发布重试耗尽（8.2）与对账无法收敛两条转人工路径均接入该入口，人工工作台必然存在处置入口。

### 8.4 消费先于 Confirm 的 DLQ 迁移（P1）

- `SeckillOrderEventStateMachine` 新增 `PENDING → DLQ`、`PUBLISH_UNKNOWN → DLQ` 自动迁移："消费者已经收到消息"本身即可靠投递证据，消费重试耗尽时事件不再卡在发布侧状态而被 Outbox 反复重发。

### 8.5 已结束优惠券的孤儿预留对账（P1）

- 对账任务 `reconcilePendingReservations()` 的券范围由"活动中的券"改为"结束时间回看窗口"（`reservation-voucher-lookback-days`，默认 7 天）：已结束活动的未收敛预留同样被扫描补建或回滚。
- 新增 orderId 反向索引 `seckill:reservation:order:{orderId}`（预留 Lua 原子写入，值为 `voucherId:eventId`；回滚/完成 Lua 原子删除）。【注：该初版设计使用 `{orderId}` Hash Tag，会导致预留 Lua 操作跨槽 Key，Redis Cluster 下报 `CROSSSLOT`；已在第 9.1 节重构为券维度同槽 Hash。】
- 订单状态查询 `hasActiveReservation()` 改走反向索引定位预留，不再依赖活动券范围，消除活动结束后误报 NOT_FOUND。

### 8.6 人工重放再次失败的记录重新开启（P1）

- `SeckillFailureCaseService.recordFailure()` 幂等命中旧记录时：`REPLAYED`/`CLOSED` 状态重新置为 `OPEN`，并更新最新错误码、错误消息、payload 与 x-death 证据，`next_action_time` 重置为当前时间。
- 重放后再次失败不会从人工工作台消失。

### 8.7 存量测试失败修复

- `UploadController.deleteBlogImg()` 对文件名统一归一化反斜杠为 `/` 后再做 canonical path 比较，`SecurityFixTests.testPathTraversalDetection` 在 macOS 上通过（Windows 部署下的 `..\` 穿越同样被拦截）。

### 8.8 修复后验证

- Java 8 全量重新编译：通过。
- 全量测试：168 执行 / 168 通过 / 0 失败（含新增 4 个测试类 21 项用例覆盖上述场景）。
- 真实 RabbitMQ 故障注入、跨存储崩溃演练、并发压测、迁移 SQL 执行仍待人工分阶段验收（见第 5 节）。

## 9. 第二轮验收问题修复记录（2026-08-21）

验收结论：第一轮修复引入的 orderId 反向索引使用 `{orderId}` Hash Tag，与预留 Lua 的五个 `{voucherId}` Key 跨槽，Redis Cluster 下报 `CROSSSLOT`；另有对账兜底缺位、回滚退避下标偏移、文件路径公共前缀绕过四个 P1 问题。核心修复原则：不能为了订单状态查询，破坏秒杀 Lua 的原子性和 Redis Cluster 同槽要求。

### 9.1 orderId 反向索引重构为券维度同槽 Hash（P1）

- 反向索引由 `seckill:reservation:order:{orderId}`（String，值 `voucherId:eventId`）重构为 `seckill:reservation:order:{voucherId}`（Hash，field=orderId，value=eventId）。
- `seckill.lua` 预留时 `HSET KEYS[6] ARGV[3] ARGV[2]`；`seckill_rollback.lua` 与 `seckill_reservation_complete.lua` 完成回滚时 `HDEL KEYS[6] ARGV[3]`。六个 Key 全部使用 `{voucherId}` Hash Tag，单个 Lua 原子操作不再跨槽。
- `SeckillVoucherLuaExecutor` 新增 `findReservationEventId(voucherId, orderId)` 按 Hash field 查询。
- 订单状态查询新增推荐接口 `GET /voucher-order/status/{voucherId}/{orderId}`；旧接口 `/status/{orderId}` 兼容保留，先查 MySQL 订单与事件，无法定位 Redis 预留时返回 `UNAVAILABLE`（不误报 `NOT_FOUND`）；秒杀受理响应同时返回 `orderId` 与 `voucherId`。
- 有意不采用固定 `{seckill}` 标签：那会把全部优惠券集中到同一槽形成热点。

### 9.2 对账改为"快速扫描 + 永久兜底扫描"（P1）

- 快速任务：每分钟扫描最近 7 天（`reservation-voucher-lookback-days`）内开始/结束的优惠券，负责时效。
- 安全任务：`reconcileAllVouchersSafely()` 按 `safety-scan-cron`（默认每小时 `0 0 * * * *`）以游标分页（`WHERE voucher_id > ? ORDER BY voucher_id LIMIT N`，`safety-scan-page-size` 默认 100）扫描全部 `tb_seckill_voucher`，负责最终一致性——故障持续超过 7 天的孤儿预留仍有发现入口。
- 两层任务均只读取 `seckill:reservation:pending:{voucherId}`，继续用 `reservation-threshold-minutes` 过滤未到期预留；不使用 Redis `KEYS`/全量 `SCAN` 作为核心兜底。

### 9.3 回滚退避下标修复（P1）

- `SeckillRollbackRetryPolicy.nextDelaySeconds()` 与发布策略统一为 `index = completedAttempts - 1`：第 1～4 次失败依次退避 5/30/300/1800 秒，第 5 次失败停止自动重试转 MANUAL_REVIEW；`maxAutomaticAttempts() = BACKOFF_SECONDS.length + 1 = 5`。
- 新增 `SeckillRollbackRetryPolicyTests` 逐项断言 `1→5、2→30、3→300、4→1800、5→STOP`。

### 9.4 文件路径公共前缀漏洞修复（P1）

- `UploadController` 放弃 `file.getPath().startsWith(uploadDir.getPath())` 字符串比较，改用 `Path.startsWith()` 组件级比较：`resolve(normalizedName).toAbsolutePath().normalize()` 后校验 `targetPath` 不等于且不逃逸 `uploadPath`。
- 文件已存在时追加 `toRealPath()` 双向核验，拦截目录符号链接逃逸。
- 删除 GET 删除接口，只保留 `DELETE /upload/blog/delete`；删除结果核验文件不存在或删除失败时不返回成功。
- `SecurityFixTests` 路径穿越用例矩阵：`normal.jpg`、`sub/a.jpg` 放行；`../upload_backup/a.jpg`、`..\\upload_backup\\a.jpg`、`../../etc/passwd`、绝对路径、目录本身、符号链接逃逸全部拒绝。

### 9.5 文档同步

- README：测试数量更新为 182；API 表新增 `/status/{voucherId}/{orderId}` 并标注旧接口兼容；设计亮点更新为六 Key 同槽、对账双层扫描、回滚退避 5/30/300/1800 秒。
- `08-interview-and-secondary-development-guide.md`：简历推荐写法与变更记录补充第二轮修复（六 Key 同槽含 orderId 反向索引、双层对账、退避下标、路径穿越）。
- `09-rabbitmq-seckill-flow.md`：Key 设计改为六个同槽 Key 并说明反向索引结构与 `CROSSSLOT` 规避；对账章节写清双层扫描；订单状态查询章节说明新旧接口；验收表更新为 182/182。
- 真实 RabbitMQ 故障演练未完成前，文档中不出现"生产可用"或"不丢消息"表述。

### 9.6 修复后验证

- Java 8 `mvn clean test`：182 执行 / 182 通过 / 0 失败 / 0 跳过（含 `SeckillRollbackRetryPolicyTests`、兜底扫描、路径穿越矩阵等新增用例）。【第三轮修复后为 184，见第 10.5 节】
- 代码级验收标准逐项核对：六个 Lua Key 的 Hash Tag 全部等于 `{voucherId}`；回滚退避依次 5/30/300/1800 秒；任意历史优惠券的孤儿预留均存在兜底发现入口；公共路径前缀与 Windows 路径穿越均被拦截；文档测试数量与实际一致。
- 真实环境验收（正常下单、重复投递、Confirm 丢失、消费者停止恢复、DLQ、回滚、孤儿预留补建）仍待分阶段执行（见第 5 节）。

## 10. 第三轮验收问题修复记录（2026-08-21）

验收结论：暂不通过。上一轮四项核心修复已落地（182 个测试全部通过、六 Key 同槽、双层对账、退避 5/30/300/1800 秒、路径穿越拦截均确认），但代码联动检查发现 3 个 P1 问题：前端秒杀响应显示异常、前端图片删除调用已移除的 GET 接口、异常预留可能永久阻塞后续对账。以下为逐项修复内容。

### 10.1 前端秒杀响应显示 [object Object]（P1）

- 后端受理响应已改为 `{orderId, voucherId}` 对象，但 `shop-detail.html` 仍直接拼接 `data`，抢购成功提示显示 `[object Object]`。
- 修复：改为 `data.orderId`；注释说明 `voucherId` 即当前券 id，用于调用新版订单状态接口 `GET /voucher-order/status/{voucherId}/{orderId}`。

### 10.2 前端图片删除调用已移除的 GET 接口（P1）

- 后端只保留 `DELETE /upload/blog/delete`，`blog-edit.html` 仍用 `axios.get`，编辑博客时删除图片必然失败。
- 修复：改为 `axios.delete("/upload/blog/delete", {params: {name}})`；同时剥离展示 URL 的 `/imgs` 前缀——`fileList` 存储的是 `/imgs/blogs/1/2/x.jpg` 展示地址，而后端 `resolveSafeTarget` 期望上传目录内相对路径（`blogs/1/2/x.jpg`），绝对路径会被组件级校验拒绝。
- 文档同步：第 9.4 节误写的 `DELETE /manage/blog/delete` 已更正为 `DELETE /upload/blog/delete`。

### 10.3 异常预留永久阻塞后续对账（P1）

- 问题：对账每轮固定读取待对账 ZSet 最早的 `reservationBatchSize` 条记录；信息不完整的记录只写人工失败单却仍留在原 ZSet。当前排记录持续异常时，排在后面的正常记录永远无法被扫描。
- 修复：新增人工处理集合 `seckill:reservation:manual:{voucherId}`（ZSet，score 为移交时间）与 `seckill_reservation_manual.lua`（`ZREM` 待对账 ZSet + `ZADD` 人工集合，两个 Key 同 `{voucherId}` 槽原子执行）。
- `recordIncompleteReservation` 顺序：先写失败单（幂等键 `RECONCILE:eventId`），成功后调用 `SeckillVoucherLuaExecutor.moveReservationToManual()` 原子移交。先写单再移交保证移交失败时记录仍在待对账 ZSet，下一轮幂等重写失败单并重试移交，不失去发现入口；移交 Lua 返回 0 表示已不在待对账集合（幂等）。
- 效果：异常预留离开自动对账队列，排在其后的正常预留下一轮即可被扫描；异常记录由人工依据失败单处置收敛。

### 10.4 新增联动测试

- `incompleteReservationsDoNotBlockFollowingNormalReservations`：排头异常（详情缺失）+ 后面正常记录，验证异常记录写失败单并移交人工集合，同轮正常记录仍被补建。
- `manualMoveFailureDoesNotBlockFollowingReservations`：移交时 Redis 异常，验证失败单已落库且同轮后续记录仍正常收敛（下轮重试移交）。
- 现有 `incompleteReservationGoesToManualReview`、`malformedReservationDetailGoesToManualReview` 补充 `moveReservationToManual` 调用断言。

### 10.5 修复后验证

- Java 8 `mvn clean test`：184 执行 / 184 通过 / 0 失败 / 0 跳过。
- 验收标准逐项核对：秒杀成功提示显示 `data.orderId`；图片删除走 `DELETE /upload/blog/delete` 且 name 剥离 `/imgs` 前缀；异常预留写失败单成功后原子移出自动对账集合并转入人工集合，排头异常不再阻塞后续记录。
- 真实环境验收（正常下单、重复投递、Confirm 丢失、消费者停止恢复、DLQ、回滚、孤儿预留补建）仍待分阶段执行（见第 5 节）；大量改动待最终验收通过后提交。

## 11. 人工移交 Lua 运行时错误安全修复

- 问题：旧脚本先 `ZREM` 待对账入口，再 `ZADD` 人工集合。Redis Lua 只保证脚本执行期间不被其他命令穿插，不会回滚运行时错误前已经完成的写操作；若人工集合发生 `WRONGTYPE` 等错误，待对账入口已经被删除。
- 修复：先 `ZSCORE` 确认源成员存在，再 `ZADD` 人工集合，最后 `ZREM` 待对账入口。目标写入失败时源入口仍保留；源 Key 类型也已在删除前验证。
- 测试：新增 `SeckillReservationManualLuaTests`，锁定 `ZSCORE → ZADD → ZREM` 的安全顺序；最终 Java 8 `mvn clean test` 为 185 执行、185 通过、0 失败、0 跳过。
