# 秒杀对账修复复测记录

> 测试日期：2026-09-06  
> 修复提交：`9571209 fix: prevent seckill reconciliation starvation`  
> 结论范围：仅代表本次 AutoDL 规格、当前 JAR、SSH 隧道和隔离测试数据。

## 1. 修复内容

上轮 1,501 条全链路请求中有 697 条 Redis 预留长期未收敛。根因是对账任务按 `consumed_at DESC LIMIT 100` 重复读取最新事件，没有记录 Redis 预留完成状态。

本次修复增加 `reservation_completed_at`：

- 只查询 `CONSUMED` 且清理标记为空的事件；
- 按 `consumed_at、event_id` 升序分批处理；
- Redis 完成脚本返回 `0/1` 后写入完成标记；
- 单轮最多处理 20 批，避免 Redis 故障时占满调度线程；
- 新增迁移：`20260906_add_seckill_reservation_completion_marker.sql`。

## 2. 全链路复测：通过

配置：两个应用实例 `6006/6008`，独立 MySQL、Redis DB 6、RabbitMQ vhost；50 req/s × 30s，计划 1,500 次。

| 指标 | 结果 | 结论 |
| --- | ---: | --- |
| 实际完成请求 | 1,500 | PASS |
| HTTP 失败 | 0 | PASS |
| 业务受理率 | 100% | PASS |
| dropped iterations | 0 | PASS |
| p95 | 196.86 ms | 观察值，不作为容量 SLO |
| `CONSUMED` 事件 | 1,500 | PASS |
| 订单数 | 1,500 | PASS |
| 清理完成标记 | 1,500 | PASS |
| MySQL / Redis 库存 | 8,500 / 8,500 | PASS |
| 四类 Redis 预留结构 | 全部不存在 | PASS |
| OPEN 失败单 | 0 | PASS |
| RabbitMQ 主队列 / DLQ | 0 / 0 | PASS |
| 应用 ERROR | 0 | PASS |

这次已经证明：订单、事件、库存和 Redis 预留清理可以完整闭环，之前的 697 条残留问题已复现修复。

最终快照：[`fixed-fullchain-snapshot.txt`](../../results/pressure-fix-retest-20260906/fixed-fullchain-snapshot.txt)。

## 3. 入口容量复测：不纳入正式容量结论

入口实例使用独立数据库和 Redis DB 7，任务与消费者关闭，避免异步链路影响入口测量。

| 档位 | VU | 完成 | 完成率 | dropped | 业务受理 | p95 | 结论 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 2500/s × 45s | 300 | 81,078 | 1,795/s | 31,423 | 100% | 290.60 ms | 压测器 VU 不足，作废 |
| 2500/s × 30s | 800 | 74,909 | 2,484.8/s | 92 | 100% | 158.90 ms | 启动期仍有 dropped，作废 |
| 2500/s × 8s | 1000 | 20,001 | 2,454.4/s | 0 | 99.995% | 165.82 ms | 1 个业务失败，严格阈值不通过 |

这些轮次说明当前目标机和 SSH 隧道下，2500/s 的长稳测试需要更严格的压测器预热、独立 fresh voucher、足够的用户令牌以及多轮重复；不能据此更新“2500/s 稳定容量”结论。入口原有短时阶梯结果仍只能表述为短时入口观察值。

原始摘要和日志：[`pressure-fix-retest-20260906`](../../results/pressure-fix-retest-20260906/)。

## 4. 证据摘要

| 文件 | SHA-256 |
| --- | --- |
| `fixed-fullchain-50.json` | `5d40fb675008cb88b3908bc502d3848a6cf5f91190f40f227d40925749fa398c` |
| `fixed-entry-2500-30s-800vu.json` | `4e6ab766a9f7a0f93d9f82c8db62f0fbd9f0ce850750c9bb142d067f26bfa664` |
| `fixed-entry-2500-8s-1000vu.json` | `de4c814641c6d351800a52fc4747459ac81a416785ea0511b36f1e3c336e7fd3` |

## 5. 当前验收结论

- **异步全链路最终一致性：通过。**
- **入口容量正式验收：未通过/证据不足。**
- **生产容量、长时间稳定性、故障注入：仍未证明。**

下一轮入口测试应先把压测器预热和令牌分配单独做空载校验，再用 3 个独立 fresh voucher 重复 2500/s，每轮至少 10 分钟，并同步记录负载机与目标机 CPU、网络、FD、JVM GC 和连接池。
