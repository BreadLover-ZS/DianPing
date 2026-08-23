# DishReview 项目全面审查与面试向改进路线

> 审查日期：2026-08-23
>
> 审查基线：`main@a57d566`
>
> 目标：找出影响正确性、安全、可验证性和面试表达的问题，并给出可执行、可验收的改进顺序。

> 实施状态（2026-08-23）：已落地配置去远程默认值、管理员写权限、上传目录/所有权/魔数校验、BCrypt 渐进升级、验证码 Lua 原子校验、消息-事件事实核对、Outbox 租约窄窗口修复、迟到 Confirm 旁证、评论状态与内容处理、热门博客批量作者查询、缓存锁值校验和基础指标入口。已补 Maven Wrapper，并在 JDK 8 下完成 200 个自动化测试；Feed Outbox、Testcontainers、CI 和真实 Broker 故障矩阵仍待完成。

## 1. 结论先行

这个项目目前最有价值的部分是秒杀可靠性链路。Redis Lua 预留账本、MySQL Outbox、发布尝试证据、Confirm/Return、消费事务、DLQ、回滚和对账已经形成了完整的源码阅读主线。它比继续堆砌新中间件更值得在面试中讲深。

当前主要短板不在“功能数量”，而在以下四点：

1. 当前配置已移除远程地址和默认密码，但 Git 历史仍可能含旧凭据，必须在服务端轮换；
2. 基础 `USER/ADMIN` 和上传所有权已加入，但方法级授权、失败处置审批和审计字段仍未开放；
3. MQ 消费事实核对、Outbox 租约窄窗口和迟到 Confirm 旁证已补，真实 RabbitMQ 故障链路仍未验收；
4. Maven Wrapper 和本地自动化测试已补齐，但仍缺容器化集成测试与 CI。

因此，推荐策略是：**保留 MQ 主架构，先修 P0，再用真实中间件测试和指标把它证实，最后只选择一个业务一致性问题做深入改造。**

---

## 2. 审查范围与证据边界

本次检查覆盖：

- 108 个主代码 Java 文件、26 个测试 Java 文件；
- Controller、Service、Redis Lua、RabbitMQ、MySQL 表结构与迁移脚本；
- 登录鉴权、上传、缓存、Feed、关注、评论、秒杀订单；
- `pom.xml`、`application.yaml`、Nginx、测试配置、现有学习和交付文档；
- 当前 Git 状态与历史 Surefire 测试报告。

本次没有执行真实 MySQL、Redis、RabbitMQ 读写，也没有进行压测。使用 JDK 8 和 Maven 3.8.8 执行了当前自动化测试；Spring 上下文测试只验证 Bean 装配，没有建立真实中间件连接。

当前可确认的测试证据是：

| 证据 | 结论 |
| --- | --- |
| 2026-08-23 JDK 8 + Maven 3.8.8 | 26 个测试套件、200 个测试，0 failure、0 error、0 skipped |
| 当前测试源码 | 覆盖 MQ 状态机、重试、回滚、对账、DLQ、消息事实核对、租约旧快照、迟到 Confirm 和关键安全分支 |
| `DishReviewApplicationTests` / `FeatureIntegrationTests` | 验证 Spring 上下文和 Bean 装配；不证明外部中间件可用 |
| 真实 RabbitMQ、数据库事务、并发和宕机恢复 | 本次未执行；仓库仍没有 Testcontainers 或故障注入流水线 |

正确的面试边界是：**当前自动化测试覆盖了主要状态分支，但不能据此声称生产可用、零消息丢失或达到某个 QPS。**

---

## 3. 已经做得好的部分

### 3.1 秒杀链路有完整的问题意识

当前实现不是简单的“Lua 扣库存后直接发 MQ”，而是把跨存储失败拆开处理：

```text
Redis Lua 预留
  -> MySQL PENDING 事件
  -> Outbox 租约抢占
  -> 发布尝试记录
  -> RabbitMQ Confirm / Return / 超时证据
  -> 消费事务写订单
  -> DLQ / 回滚 / 对账 / 人工处置
```

这条链路适合回答以下面试问题：Outbox、最终一致性、发送结果未知、异步回调、消费幂等、租约与 fencing token、失败证据、死信和对账。

### 3.2 关键幂等边界基本存在

- Redis Lua 原子处理库存、一人一单和预留账本；
- `tb_voucher_order(user_id, voucher_id)` 有联合唯一索引；
- 同一事件消费前锁事件行，`CONSUMED` 重投直接返回；
- 失败记录使用幂等键；
- 回滚按 `eventId` 核对预留，避免误回滚同一用户的新预留；
- 租约释放携带 `lease_token`，旧任务不能清除新租约。

### 3.3 文档已经主动声明能力边界

README 和 MQ 文档没有把单元测试包装成生产验收，也明确说明了真实 RabbitMQ、并发、故障恢复和监控告警尚未完成。这一点应继续保持。

---

## 4. 优先级定义

| 级别 | 含义 |
| --- | --- |
| P0 | 面试前或继续公开仓库前必须修；涉及凭据、越权、核心数据错误或无法复现 |
| P1 | 最能提升项目深度和可信度；完成后可以形成高质量技术故事 |
| P2 | 工程完善与代码清理；不应挤占 P0/P1 时间 |

优先级总览：

| ID | 主题 | 主要证据位置 |
| --- | --- | --- |
| P0-1 | 轮换并移除默认凭据 | `application.yaml`、Git 历史 |
| P0-2 | RBAC 与资源所有权 | `MvcConfig`、Shop/Voucher/Upload Controller |
| P0-3 | 消息与事件事实核对 | `VoucherOrderHandler#createOrder` |
| P0-4 | 密码、验证码与短信边界 | `PasswordEncoder`、`UserServiceImpl` |
| P0-5 | 上传与评论内容安全 | `UploadController`、`BlogCommentsServiceImpl` |
| P0-6 | 可复现构建与测试 | `pom.xml`、`application-test.yaml`、测试源码 |
| P1-1～P1-5 | MQ 窄窗口、真实测试、观测、迁移 | Outbox/attempt/confirm/task、SQL migration |
| P1-6～P1-14 | Feed、缓存、API、数据模型、索引与运维 | Blog/Follow/Shop Service、实体、SQL、任务配置 |

---

## 5. P0：必须先修

### P0-1 立即轮换并移除仓库中的默认凭据

**实施状态**：当前配置已切换为 localhost/空密码占位符，并新增 `application-prod.yaml`、`.env.example`；Git 历史清理和服务端密码轮换不能在本地代码中替代完成。

**源码证据**

- `src/main/resources/application.yaml` 默认连接 `115.29.220.133`；
- MySQL 和 Redis 都包含默认明文密码；
- Git 历史中已经出现这些地址和凭据，仅修改当前文件不能使旧凭据重新安全。

**问题**

任何克隆仓库的人都可能直接连接远程服务。即使端口已通过防火墙限制，凭据也应按已泄露处理。

**修改建议**

1. 先在服务端轮换 MySQL、Redis 及相关账号密码，并限制来源 IP 和最小权限；
2. `application.yaml` 只保留无秘密的占位符，不提供真实地址、用户名和密码默认值；
3. 建立 `application-local.yaml.example` 或 `.env.example`，只写变量名；
4. 将本地、测试、部署配置分开，生产缺少必需变量时启动失败；
5. 评估是否需要清理 Git 历史，但不能用清理历史代替凭据轮换。

**验收标准**

- `rg` 扫描仓库和 Git 历史不再发现仍有效凭据；
- 未设置环境变量时不能连接远程环境；
- 数据库与 Redis 账号均不是高权限默认账号；
- README 不再给出真实服务器地址。

**面试价值**

可以讲配置分层、密钥轮换、最小权限和“删除秘密不等于秘密未泄露”。

### P0-2 建立管理员权限与资源所有权校验

**实施状态**：本轮已加入 `USER/ADMIN` 角色字段、管理写接口拦截器、上传目录所有权校验；失败处置 Controller 仍保持不开放。

**历史源码问题**

- `MvcConfig#addInterceptors` 只区分“登录/未登录”，没有用户角色；
- 任意登录用户都可以调用 `POST/PUT /shop`、`POST /voucher`、`POST /voucher/seckill`；
- `UploadController#deleteBlogImg` 只校验路径是否安全，不验证文件属于谁；
- 秒杀失败处置 Service 因没有 RBAC 而没有开放 Controller，这个边界是正确的。

**问题**

认证只证明“你是谁”，没有解决“你能做什么”。当前普通用户可以修改管理资源；只要知道图片路径，也可以删除其他用户上传的图片。

**修改建议**

1. 引入明确的 `USER/ADMIN` 权限模型；
2. 同时做请求级和方法级授权，管理写接口只允许 ADMIN；
3. 增加上传文件元数据表，记录 `fileId/storageKey/ownerId/status`；删除时按 `ownerId` 或 ADMIN 校验；
4. 失败处置 Controller 只有在 RBAC、审计和幂等验收完成后再开放；
5. 增加普通用户、管理员、资源所有者和非所有者四组授权测试。

Spring Security 官方建议同时考虑请求级和方法级授权；方法级 `@PreAuthorize` 比只靠 URL 拦截更适合保护 Service 边界。

**验收标准**

- 普通用户调用管理接口返回 403；
- 用户不能删除他人的文件；
- 管理员操作记录 operator、reason、requestId 和时间；
- 直接调用受保护 Service 也不能绕过授权。

### P0-3 消费前核对“消息体 = 事件表事实”

**实施状态**：本轮已在锁定事件后逐项核对 `eventId/orderId/userId/voucherId/createdAt/version`，并在已有订单分支核对三项订单身份；不一致直接抛一致性异常。

**源码证据**

- `VoucherOrderHandler#createOrder` 只用 `message.eventId` 锁定事件行；
- 后续查订单、扣库存和写订单继续使用消息体中的 `orderId/userId/voucherId`；
- 没有比较消息的 `orderId/userId/voucherId/createdAt/version` 与事件行；
- 已有订单只按 `(userId, voucherId)` 判断，存在后直接把当前事件标记为 `CONSUMED`，没有核对已有订单 ID。

**风险**

错误消息只要携带一个真实 `eventId`，就可能用另一组业务字段扣库存和写订单，并把真实事件标记完成。正常生产者不会主动构造这种消息，但消息损坏、错误重放、手工运维或 Broker 权限失守都可能触发这个窗口。

**修改建议**

1. 锁定事件行后，逐项比较消息与事件；事件表是业务身份的唯一可信来源；
2. 字段不一致时抛 `SeckillConsistencyException`，持久化失败证据并转人工，禁止扣库存；
3. 已有订单应查询出订单实体，核对 `orderId/userId/voucherId`；
4. 重复键异常只有在查到的订单身份完全一致时才能视为幂等成功；
5. 为每个字段的错配增加参数化测试。

**验收标准**

- 任意一个身份字段不一致都不会更新库存、订单和 `CONSUMED`；
- 真正的相同消息重投仍然幂等成功；
- 同用户同券但不同 orderId 的冲突进入人工处置，而不是伪装成功。

**面试价值**

这能说明“幂等不是看到重复就返回成功，而是确认重复请求与原业务事实完全相同”。

### P0-4 修复登录与验证码安全边界

**实施状态**：本轮已完成 BCrypt 渐进升级、验证码 Lua 原子校验、手机号脱敏、随机昵称和生产短信通道 fail-closed；账号/IP 维度限流与真实短信供应商仍待补。

**源码证据**

- `PasswordEncoder` 历史上使用随机盐加 MD5；本轮已改为 BCrypt，并保留成功登录后的渐进升级；
- `loginByCode` 历史上通过 `GET` 后 `SET attempts + 1` 更新错误次数；本轮已改为 Redis Lua 原子校验、计数和一次性删除；
- 密码登录没有账号/IP 维度的失败限流；
- `sms-code-mode=prod` 历史上只记录“已发送”日志却返回成功；本轮无真实通道时改为失败闭环；
- 新用户昵称历史上带完整手机号；本轮改为随机后缀，公开用户 DTO 也清除角色字段；
- 日志历史上记录完整手机号和验证码；本轮仅保留脱敏手机号，不记录验证码。

**修改建议**

1. 新密码使用 Argon2id；如果当前技术栈不便接入，可先用 BCrypt，并在登录成功后迁移旧 MD5 密文；
2. 验证码失败次数使用 `INCR + EXPIRE` Lua 或等价原子方案；验证码校验成功也要用 compare-and-delete Lua 原子消费，保证一次性；
3. 增加账号和 IP 双维度限流，登录错误统一返回模糊提示；
4. 抽象 `SmsSender`，没有真实实现时 `prod` 配置必须启动失败，不能假成功；
5. 昵称使用随机后缀，手机号和验证码日志脱敏；
6. 测试模式只能在 local/test profile 开启。

OWASP 建议使用 Argon2id、bcrypt 或 PBKDF2 等现代自适应密码哈希，而不是快速摘要算法。

**验收标准**

- 新密码不再生成 MD5；旧密码可以一次性平滑升级；
- 并发错误验证码不能绕过最大次数；
- 生产配置没有短信实现时直接失败；
- API 和日志不暴露完整手机号或验证码。

### P0-5 补齐上传和用户内容安全

**源码证据**

- 历史实现把 `SystemConstants.IMAGE_UPLOAD_DIR` 写死为本地路径；本轮已改为配置项；
- 历史实现只检查扩展名和大小；本轮已增加图片魔数校验（真实图片解码和恶意内容扫描仍待补）；
- 文件删除历史上缺资源所有权；本轮已按用户目录/ADMIN 校验；
- 评论查询历史上未过滤状态且直接保存内容；本轮已转义、限制长度、过滤非正常状态并增加 100 条上限。

**修改建议**

1. 把存储目录改为配置项，并通过 `StorageService` 隔离本地文件和对象存储实现；
2. 校验声明 MIME、文件魔数和图片解码结果，重新编码后再保存；
3. 评论使用请求 DTO，限制长度并统一转义或净化；
4. 查询只返回正常状态，使用游标或分页；
5. 文件名不再由前端直接充当删除权限凭据。

**验收标准**

- Linux、Windows 和容器环境不改代码即可配置存储；
- 伪装成 `.jpg` 的非图片被拒绝；
- 评论中的脚本载荷不会以可执行 HTML 返回；
- 被禁止查看的评论不会出现在普通查询中。

### P0-6 建立可复现的构建和测试入口

**实施状态**：已提交 Maven Wrapper，并在 JDK 8 下完成 200 个测试；Testcontainers、Compose 和 CI 仍作为后续独立阶段。

**源码证据**

- 仓库已有固定 Maven 3.8.8 的 `mvnw` / `mvnw.cmd`；
- `application-test.yaml` 显式关闭所有秒杀定时任务，主配置也默认关闭任务和消费者；
- Spring 上下文测试只验证装配，不把外部中间件行为包装成测试结论；
- 尚无 Docker Compose、Testcontainers 和 CI，干净机器的真实中间件复验仍未闭环。

**修改建议**

1. 保持 Maven Wrapper 版本固定；
2. 用 Testcontainers 提供 MySQL、Redis 和 RabbitMQ 集成测试；
3. 增加最小本地 Compose，仅用于人工运行应用，不用它替代 Testcontainers 测试隔离；
4. GitHub Actions 至少执行编译、单元测试、集成测试和 `git diff --check`；
5. CI 输出 Surefire/JUnit 报告并保留失败日志。

Testcontainers 官方提供 MySQL 和 RabbitMQ 模块，可以让每次测试从已知状态的临时实例开始。

**验收标准**

```bash
./mvnw clean test
```

当前已满足 Wrapper 与本地测试入口；加入 Testcontainers 后，再验收“一台只有 JDK 和 Docker 的新机器可完整执行，且不连接共享业务环境”。

---

## 6. P1：最值得形成面试故事的改进

### P1-1 收紧 Outbox 租约抢占窗口

**实施状态**：本轮已在 `claimLease` 加入 `next_retry_time` 到期条件，并在抢租约后按 `owner + lease_token` 重新读取事件再决定发送；已补旧快照单元测试，真实双实例数据库竞争测试仍待 Testcontainers 阶段执行。

**源码证据**

- `findDueForPublish` 筛选 `next_retry_time <= CURRENT_TIMESTAMP`；
- `claimLease` 只检查状态和租约过期，没有再次检查 `next_retry_time`；
- 抢到租约后，耗尽判断和消息构造继续使用扫描阶段的旧 `event` 快照。

**问题时序**

```text
实例 A 扫到事件到期
实例 B 抢租约、发送、把 next_retry_time 推到未来、释放租约
实例 A 再抢租约，因为 claimLease 不检查 next_retry_time，仍可能立即再发一次
```

消费端幂等可以避免重复订单，但这会制造额外发送、回调竞争和难解释的尝试次数。

**建议**

- `claimLease` 原子更新时同时检查 `next_retry_time` 到期；
- 抢占成功后返回完整的新事件快照，或按 `lease_owner + lease_token` 重新读取；
- 耗尽判断、消息构造和后续状态更新只使用新快照；
- 增加双实例并发抢占和“扫描后被推迟”的测试。

### P1-2 修正 `row_version` 的语义和文档

**源码证据**

代码多次执行 `row_version = row_version + 1`，但没有任何更新条件使用 `.eq("row_version", oldVersion)`。当前真正承担 CAS 的是状态、租约到期时间、owner 和 `lease_token`。

**建议**

二选一：

1. 真正把 `row_version` 加入需要乐观并发控制的 `WHERE` 条件；
2. 将它明确命名或描述为“状态变更计数”，不要在面试文档中称为 CAS 行版本。

当前 `08-interview-and-secondary-development-guide.md` 表格把它写成 `row_version CAS`，应同步修正。这个问题不会直接破坏当前状态机，但会导致面试解释与源码不一致。

### P1-3 补真实 Broker 故障矩阵

**实施状态**：本轮已为迟到 Confirm 增加旁证字段，仍未执行真实 Broker、DLQ、宕机恢复和并发故障演练。

至少覆盖：

| 场景 | 必须观察的结果 |
| --- | --- |
| 正常 ACK | attempt=ACK，事件最终 `CONSUMED` |
| routing key 错误 | ACK + Return，不能判定投递成功 |
| exchange 不存在 | NACK 或发送异常，进入可解释的 UNKNOWN/失败决策 |
| Confirm 超过 30 秒 | WAITING 由超时任务改 UNKNOWN，不阻塞业务线程 |
| UNKNOWN 后迟到 ACK | 事件可以单调收敛，不能覆盖业务终态；attempt 需要保留迟到证据 |
| 消费事务提交后 ACK 丢失 | 重投不重复扣库存、不重复写订单 |
| Listener 转换失败 | 失败记录先落库，再进入 DLQ |
| MySQL 暂时不可用 | 有限重试，不能无限 requeue |
| 发布/消费/回滚各阶段进程崩溃 | 租约、对账或重投最终收敛 |

当前 `recordAck` 只允许 `WAITING -> ACK`。超时先改成 UNKNOWN 后，迟到 ACK 可以推进事件，但尝试记录仍保持 UNKNOWN；本轮已增加 `late_confirm_at/late_confirm_result/late_confirm_reason` 旁证字段，避免覆盖历史事实。

### P1-4 为 MQ 建立可观测性和告警

**实施状态**：本轮已加入 Actuator、健康探针、metrics 端点和 Outbox/发布基础计数；完整消费、DLQ、回滚、对账指标与告警规则仍待补齐。

历史实现主要依靠日志，基础 Actuator/Micrometer 已加入，但完整业务指标与告警尚未覆盖。

建议最少增加：

- `seckill.outbox.pending.count`、最老待发布事件年龄；
- `publish.attempt` 按 ACK/NACK/UNKNOWN/RETURN 计数；
- Confirm 延迟分布；
- 消费成功、重试、DLQ、人工处置计数；
- `ROLLBACK_PENDING/EXECUTING/MANUAL_REVIEW` 数量和最长停留时间；
- 对账发现孤儿预留、补建事件、冲突数量；
- Redis/MySQL/RabbitMQ 健康和线程池队列长度；
- HTTP requestId/eventId/orderId 的结构化关联日志。

Spring Boot Actuator 会自动集成 Micrometer，并提供 JVM、系统、HTTP 和自定义指标基础。指标标签必须控制基数，`eventId/orderId/userId` 应放日志或 Trace，不应作为指标标签。

### P1-5 把手工 SQL 迁移变成可追踪迁移

**实施状态**：本轮继续沿用项目现有手工迁移约定，补充了角色、迟到 Confirm 和查询索引迁移，并同步 README 顺序；Flyway/Liquibase 迁移框架留待测试基础设施阶段。

**源码证据**

- 迁移脚本依赖人工按日期顺序执行；
- 基础 `dish_review.sql` 会 `DROP TABLE`，只适合新建演示库；
- 当前没有 Flyway/Liquibase；
- 同一张事件表的最终结构由基础脚本加多份迁移共同组成。

**建议**

- 引入 Flyway 版本化迁移和 schema history；
- 已执行脚本建立 baseline，之后只新增迁移，不修改已发布脚本；
- 把 destructive 的全量演示脚本与 schema migration、seed data 分开；
- CI 在空库和从 baseline 升级两条路径验证迁移；
- 对索引脚本增加重复数据和已存在索引的前置判断。

### P1-6 改造 Feed 发布的一致性和大 V 扩散

**实施状态**：本轮暂不改 Feed 写入模型，保留为 MQ 主线完成测试后的第二个深入改造；当前博客发布仍是 MySQL 写入后同步扩散 Redis。

**源码证据**

- `BlogServiceImpl#saveBlog` 先写 MySQL，再同步遍历所有粉丝逐个 `ZADD`；
- Redis 中途失败时，博客已经存在，但一部分粉丝收件箱缺消息；
- 粉丝越多，请求时间越长；
- 没有重试、补偿或对账。

**建议**

把“博客已发布”和“需要扩散 Feed”写入同一 MySQL 事务的 Outbox。后台任务分批或 pipeline 写收件箱，记录游标与重试状态。普通用户使用推模式；大 V 可以改为推拉结合，避免一次写入海量粉丝收件箱。

**验收标准**

- Redis 暂时失败不影响博客发布事实，恢复后能继续扩散；
- 重复执行不会产生错误 Feed；
- 关注数增大时接口耗时不线性增长；
- 能展示扩散积压和失败指标。

这是除 MQ 秒杀之外，最适合作为第二个深入面试故事的改造。

### P1-7 修复博客查询的确定性和 N+1

原问题已在本轮部分修复：`queryHotBlog` 已下沉 Service 并批量查询作者，`queryBlogLikes` 已按 ZSet ID 顺序组装且清除角色字段；查询索引已加入迁移脚本。仍需用真实数据量和 `EXPLAIN` 验证索引收益。

建议把 Controller 逻辑下沉 Service，批量查询作者并按原 ID 顺序组装；Top5 点赞用户同样恢复 ZSet 顺序。索引应先用真实 SQL 和 `EXPLAIN` 验证，候选包括 `(user_id, id)`、`(liked, id)`。

### P1-8 统一缓存实现并修正已知缺口

**明确问题**

- `CacheClient#queryWithPassThrough` 原先缓存空值时沿用正常数据 TTL；本轮已拆出独立 `nullTtl` 并由商铺查询传入 `CACHE_NULL_TTL`；
- `CacheClient` 与 `ShopServiceImpl` 各保留一套互斥锁/逻辑过期实现；
- 两处缓存重建线程池仍是静态 `Executors.newFixedThreadPool(10)`，没有 Spring 生命周期、队列上限和监控；
- 本轮已将两处锁改为唯一 token + Lua compare-and-delete，避免锁过期后误删别人持有的锁；
- `queryWithMutex` 本轮已改为有界循环重试并恢复中断标记；
- 更新商铺只删除详情缓存，没有同步 GEO 坐标；
- GEO 首次加载没有初始化锁，多请求可能同时全量回源。

**建议**

1. `queryWithPassThrough` 单独接收 `nullTtl`（已完成）；
2. 只保留一套经过测试的缓存组件；
3. 使用 Spring 管理的有界线程池；
4. 锁使用唯一 owner 和 Lua compare-and-delete（已完成）；
5. 商铺更新后同步删除/更新详情缓存与 GEO；
6. 为缓存重建、更新并发和 GEO 初始化增加测试。

### P1-9 明确 MySQL 与 Redis 的事实源

**关注关系**

- 关注先写 MySQL 再写 Redis；Redis 失败会留下缺失缓存；
- 取消关注本轮已改为无论 DB 删除行数如何都幂等移除 Redis 成员；长期一致性仍需要重建/对账任务。

建议把 MySQL 作为事实源，Redis Set 视为可重建缓存；取消关注应幂等删除 Redis 成员，并增加重建或对账。

**点赞关系**

- MySQL 计数与 Redis ZSet 分两步更新；
- Redis 失败后只做尽力反向更新，补偿也可能失败；
- 没有持久化重试或对账。

建议明确 ZSet 成员关系与 MySQL计数谁是事实源，再通过事件或定时校正保持一致。不要在面试中把“catch 后补一次”称为强一致。

### P1-10 使用请求 DTO、Bean Validation 和正确 HTTP 语义

**源码证据**

- 多个写接口直接接收 `Shop/Voucher/Blog/BlogComments` 实体；
- `LoginFormDTO` 没有字段约束；
- `WebExceptionAdvice` 把所有 RuntimeException 返回为 `Result.fail("服务器异常")`，HTTP 状态通常仍是 200；
- `Result` 没有稳定业务错误码和 requestId；
- 页码和业务字段缺少统一边界验证。

**建议**

- 为每个写接口建立最小请求 DTO，使用 `@Valid`；
- 管理端更新使用白名单字段，避免 over-posting；
- 区分 400、401、403、404、409、429、500、503；
- 返回稳定错误码和 requestId，内部异常不暴露给客户端；
- 对分页参数设置上限。

### P1-11 修复实体类型与数据库状态不一致

| 位置 | 当前类型 | 数据库语义 | 建议 |
| --- | --- | --- | --- |
| `BlogComments.status` | `Boolean` | 0 正常、1 被举报、2 禁止查看 | 改为 Integer 或枚举 |
| `UserInfo.level` | `Boolean` | 0～9 级 | 改为 Integer |

MySQL `tinyint(1)` 经驱动映射为 Boolean 时，多个非零状态会丢失区别。修改后要增加 0/1/2 和 0～9 的读写映射测试。

### P1-12 补查询索引并用 EXPLAIN 证明

当前明显候选：

- `tb_blog_comments(blog_id, status, create_time, id)`：评论按 blog 查询、过滤状态、按时间排序；
- `tb_blog(user_id, id)`：用户主页博客；
- `tb_blog(liked, id)`：热门博客稳定排序；
- 根据失败记录、事件扫描和 retention SQL 复核现有组合索引是否覆盖排序与范围条件。

不要只写“加了索引”。验收材料应包括数据量、SQL、`EXPLAIN`、扫描行数、是否回表和改造前后耗时；数据库版本支持时再补 `EXPLAIN ANALYZE`。

### P1-13 控制定时任务的多实例与运行边界

所有秒杀定时任务由同一 `tasks-enabled` 开关控制，当前默认显式关闭（`matchIfMissing=false`）。Outbox 和回滚有事件级租约，但库存扫描、快速对账和每小时全量扫描会在每个实例重复运行。

建议：

- local/test 默认关闭任务，部署时显式开启；
- Outbox、回滚继续使用业务租约；
- 全局扫描使用 ShedLock、数据库租约、leader election 或明确分片；
- 记录每轮扫描耗时、游标、处理量和失败量；
- 不要给所有任务盲目加同一把全局锁，先区分可并行任务和单例任务。

### P1-14 增加数据保留与归档策略

事件、发布尝试、失败记录、审计记录以及 Redis 预留/人工集合都会持续增长。目前没有明确清理策略。

建议按状态和年龄定义保留期：

- 非终态和 OPEN/MANUAL 数据禁止自动删除；
- 终态事件和 attempt 先归档再分批清理；
- Redis 清理前必须核对 MySQL 终态；
- 清理任务限速、可暂停、可审计；
- 用“最老未完成事件年龄”判断是否错误清理。

---

## 7. P2：工程完善与清理

### P2-1 技术栈升级放在可靠性测试之后

当前是 Java 8、Spring Boot 2.3.12、MySQL Connector 5.1.47。当前 Spring Boot 主线要求至少 Java 17，因此升级会涉及 `javax -> jakarta`、依赖兼容和 RabbitMQ API 差异，不适合与核心可靠性修改混在一个提交里。

推荐单独分支执行：

1. 先把 Maven Wrapper、集成测试和 CI 建好；
2. 升级到 Java 17 与仍受维护的 Spring Boot 版本；
3. 同步 MyBatis-Plus、MySQL 驱动和 Hutool；
4. 用同一套测试证明行为没有变化；
5. 运行依赖漏洞扫描。

面试中，“有测试保护的渐进升级”比只报一个新版本号更有价值。

### P2-2 修正 RedisIdWorker 的时间与 Key 生命周期

`RedisIdWorker` 使用本地 `LocalDateTime`，却按 UTC offset 计算秒数；日期序列 Key 也没有 TTL。建议统一使用 `Instant` 和 UTC 日期，并给每日计数 Key 设置足够长的 TTL。还应验证时钟回拨和序列溢出边界。

### P2-3 降低 Token 滑动续期写放大

`RefreshTokenInterceptor` 每个已登录请求都会刷新 Redis TTL。可以只在 TTL 低于阈值时续期，或使用访问令牌/刷新令牌拆分。先测真实访问量，不必为了理论优化引入复杂认证系统。

### P2-4 给分类缓存增加 TTL 或版本

`ShopTypeServiceImpl` 的 Redis List 没有 TTL 和失效机制。分类数据变化不频繁，但一旦更新会永久陈旧。增加 TTL、版本 Key 或管理端更新后的显式失效即可。

### P2-5 完成部署与仓库卫生

- 增加 LICENSE；
- 增加只包含必要服务的本地 Compose；
- Nginx 生产配置启用 HTTPS、HSTS 和合适的 CSP，移除已经过时或无效的安全头依赖；
- 增加健康检查和优雅停机；
- 前端仍有硬编码评论和外部图片，明确它只是演示界面；
- 清理重复 Controller/Service 逻辑和未启用的教程参考实现；
- 如需要展示 API，再增加 OpenAPI，但不要让生成文档代替测试。

---

## 8. 推荐实施顺序

### 阶段 A：先让仓库安全、可克隆

1. 轮换并移除凭据；
2. 增加 Maven Wrapper、local/test 配置和 CI；
3. 增加 RBAC、上传所有权和登录安全；
4. 修正 README 中对应配置与测试说明。

**完成标志：** 新机器可以在不访问远程环境的前提下执行测试；普通用户不能调用管理能力。

### 阶段 B：把 MQ 从“代码完整”提升到“证据完整”

1. 消息与事件逐字段核对；
2. 修正租约 due-time 与新快照问题；
3. 修正 `row_version` 语义；
4. Testcontainers 覆盖 RabbitMQ/MySQL/Redis；
5. 执行 Confirm、Return、重复投递、宕机和 DLQ 故障矩阵；
6. 增加指标和告警。

**完成标志：** 每一个关键失败窗口都有自动化用例、状态证据和可观察指标。

### 阶段 C：选择一个业务一致性主题深入

优先选择 Feed Outbox，其次是点赞/关注对账。不要同时重构所有业务模块。

**完成标志：** 能讲清故障窗口、事实源、幂等键、补偿方式、监控指标和测试结果。

### 阶段 D：再做版本升级与展示优化

技术栈升级、OpenAPI、前端展示和部署包装放在最后，以免遮蔽真正的系统设计能力。

---

## 9. 面试表达建议

### 9.1 当前可以说

> 我在教程型点评项目上重点重构了秒杀可靠性链路。入口使用 Redis Lua 原子预留并记录可对账账本，请求只落 MySQL 事件；后台 Outbox 通过租约发送，发布结果按 attemptId 记录 Confirm、Return 和超时证据；消费端在事务内锁事件、条件扣库存、写订单并推进状态，重复消息由事件状态、订单查询和唯一索引共同兜底；异常通过有限重试、失败记录、DLQ、持久化回滚和双层对账收敛。当前 200 个自动化测试覆盖了主要状态分支和 Spring 装配，但真实 Broker、数据库事务、故障演练和压测还不能写成已验收。

### 9.2 完成 P0/P1 后可以增加

> 我还补了消息与事件事实核对、多实例租约竞争测试、真实 MySQL/Redis/RabbitMQ 容器化测试，并为 Outbox 积压、Confirm 延迟、DLQ 和人工处置建立了指标。所有测试可以从干净环境在 CI 重复执行。

### 9.3 不能说

- “生产可用”或“零消息丢失”；
- “支持百万 QPS”，除非有可复核的环境、数据和报告；
- “生产短信已接入”；
- “row_version 实现了乐观锁 CAS”，除非代码真正把旧版本放进更新条件；
- “Redis 和 MySQL 强一致”；
- “登录接口已经有完善权限体系”。

---

## 10. 不建议为了面试做的改造

| 不建议项 | 原因 |
| --- | --- |
| 直接拆微服务 | 当前单体边界足够，拆分会新增调用、事务、部署和排障成本，却没有真实业务规模依据 |
| 同时加入 Gateway、Nacos、Sentinel、Seata | 组件数量不能替代正确性、测试和观测证据 |
| 为所有跨存储操作上分布式事务 | 当前 Outbox、幂等和对账更符合项目规模；先证明现有故障模型 |
| 再造一套 MQ 抽象 | 当前问题是窄窗口和验证不足，不是缺少更多封装 |
| 先做 Elasticsearch | 项目当前没有足够搜索需求和数据量证据，面试收益低于安全、MQ 和 Feed 一致性 |
| 只做前端美化 | 不会提升 Java 后端项目的核心可信度 |

---

## 11. 最小验收清单

完成改造时，至少保留以下证据：

- [ ] 仓库和历史中出现过的有效凭据已轮换；
- [ ] `./mvnw clean test` 在干净机器和 CI 通过；
- [ ] 测试不连接共享远程环境；
- [ ] 普通用户无法调用管理接口或删除他人文件；
- [ ] 消息字段与事件不一致时不会扣库存或写订单；
- [ ] 两实例竞争不会绕过 `next_retry_time` 立即重复发送；
- [ ] Confirm/Return/UNKNOWN/迟到回调都有真实 Broker 测试；
- [ ] 重复消费不重复扣 MySQL 库存、不重复创建订单；
- [ ] DLQ 前失败记录落库失败时消息不会被静默丢弃；
- [ ] Outbox 积压、最老事件、UNKNOWN、DLQ、回滚和人工处置可监控；
- [ ] 数据库迁移可以从空库和 baseline 两种路径重复执行；
- [ ] 所有性能结论附带环境、数据量、脚本和原始结果；
- [ ] README、MQ 文档、面试文档与最终代码事实一致。

---

## 12. 官方参考

- [Spring Boot System Requirements](https://docs.spring.io/spring-boot/system-requirements.html)
- [Spring Boot Actuator Metrics](https://docs.spring.io/spring-boot/reference/actuator/metrics.html)
- [Spring Security Authorization](https://docs.spring.io/spring-security/reference/servlet/authorization/index.html)
- [Spring Security Method Security](https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html)
- [OWASP Password Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)
- [Testcontainers for Java](https://java.testcontainers.org/)
- [Testcontainers MySQL Module](https://java.testcontainers.org/modules/databases/mysql/)
- [Testcontainers RabbitMQ Module](https://java.testcontainers.org/modules/rabbitmq/)
- [Flyway Versioned Migrations](https://documentation.red-gate.com/fd/versioned-migrations-273973333.html)
