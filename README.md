# DishReview · 餐饮点评平台

> 一个功能完整的餐饮探店点评系统，涵盖商铺浏览、探店笔记、关注 Feed 流、点赞评论、优惠券与秒杀等核心业务，深度实践 Redis 在缓存、分布式锁、排行榜、地理位置、签到等场景的典型应用。

## 目录

- [项目简介](#项目简介)
- [核心功能](#核心功能)
- [技术栈](#技术栈)
- [系统架构](#系统架构)
- [目录结构](#目录结构)
- [数据库设计](#数据库设计)
- [环境要求](#环境要求)
- [快速开始](#快速开始)
- [使用说明](#使用说明)
- [API 文档](#api-文档)
- [核心设计亮点](#核心设计亮点)
- [安全设计](#安全设计)
- [测试](#测试)
- [贡献指南](#贡献指南)
- [许可证](#许可证)
- [常见问题 FAQ](#常见问题-faq)

---

## 项目简介

DishReview 是一款基于 **Java 8 + Spring Boot 2.3 + MyBatis-Plus + MySQL + Redis + RabbitMQ** 构建的餐饮点评平台。秒杀链路已完成成体系的可靠性闭环改造：Redis Lua 原子预留账本、MySQL 事件 Outbox、RabbitMQ 至少一次投递、幂等消费、持久化回滚任务、定时对账与 DLQ 失败处置闭环；消费者默认由环境变量控制，真实 RabbitMQ 故障演练和并发验收仍需单独完成。

项目将典型的互联网业务场景与 Redis 高级数据结构深度结合，是学习和实践 **缓存设计、分布式锁、Feed 流、基于 GEO 的 LBS 查询、BitMap 签到、全局唯一 ID 生成** 等技术的完整参考实现。

---

## 核心功能

| 模块 | 功能说明 |
| --- | --- |
| 用户认证 | 手机号验证码登录（60s 发送频率限制、5 次错误上限）、密码登录、登出、Token 自动续期（30 分钟滑动过期） |
| 用户签到 | 基于 Redis BitMap 按月签到，统计本月连续签到天数 |
| 商铺浏览 | 商铺详情缓存（防穿透/防击穿）、按类型/名称分页查询、基于 Redis GEO 的附近 5km 商铺查询 |
| 商铺分类 | 店铺类型列表，Redis List 缓存 |
| 探店笔记 | 热门笔记排行、笔记详情、发布笔记（XSS 转义 + Feed 推送）、我的笔记 |
| 点赞 | 基于 Redis ZSet 实现「一人一赞」，点赞 Top5 用户展示，点赞时间排序 |
| 关注 | 关注/取关、是否关注、共同关注（Redis Set 交集） |
| Feed 流 | 关注的人发布笔记后推送到粉丝收件箱（ZSet 推模式），滚动分页查询（score + offset） |
| 评论 | 笔记评论列表、新增评论并同步评论计数 |
| 优惠券 | 普通券/秒杀券管理，店铺优惠券查询 |
| 秒杀 | 时间窗校验、Redis Lua 原子预扣 + 预留账本、MySQL 事件 Outbox、RabbitMQ 异步消费、幂等消费、持久化回滚、定时对账、DLQ 失败处置闭环、订单状态查询 |
| 文件上传 | 博客图片上传（类型白名单 + 5MB 大小限制）、删除（路径穿越防护） |

---

## 技术栈

### 后端

| 技术 | 版本 | 用途 |
| --- | --- | --- |
| Java | 1.8 | 开发语言 |
| Spring Boot | 2.3.12.RELEASE | 应用框架 |
| MyBatis-Plus | 3.4.3 | ORM / 分页插件 |
| MySQL | 5.6+（mysql-connector-java 5.1.47） | 关系型数据库 |
| Spring Data Redis (Lettuce + commons-pool2) | Spring Boot 内置 | Redis 客户端 |
| Spring AMQP / RabbitMQ | Spring Boot 内置 / 3.13 | 秒杀订单 Outbox 至少一次投递、分类重试、死信与失败记录闭环 |
| Hutool | 5.7.17 | 工具库（JSON、加密、随机数等） |
| Lombok | 内置 | 简化实体代码 |
| AspectJ | 内置 | 提供 Spring AOP 支持；当前秒杀事务位于独立的 `VoucherOrderHandler` Bean |

### 前端与部署

| 技术 | 用途 |
| --- | --- |
| Vue.js 2 + Element UI + Axios（原生 HTML/CSS/JS） | 静态前端页面（`login`、`index`、`shop-list`、`shop-detail`、`blog-detail`、`blog-edit`、`info` 等） |
| Nginx 1.18 | 托管前端静态资源，`/api` 反向代理到后端 `127.0.0.1:8081` |

---

## 系统架构

```text
┌────────────────────────────────────────────────────────────────┐
│                          浏览器 (Vue + Element UI)              │
└───────────────────────────────┬────────────────────────────────┘
                                │ /api/*
                                ▼
┌────────────────────────────────────────────────────────────────┐
│                      Nginx (端口 8080)                           │
│  · 静态资源: / → html/dishreview                                 │
│  · 反向代理: /api → http://127.0.0.1:8081                       │
└───────────────────────────────┬────────────────────────────────┘
                                ▼
┌────────────────────────────────────────────────────────────────┐
│                 Spring Boot (端口 8081)                          │
│  Controller → Service → Mapper (MyBatis-Plus)                   │
│  拦截器: RefreshTokenInterceptor → LoginInterceptor              │
│  全局异常: WebExceptionAdvice → Result                           │
└───────────────┬──────────────────────────────┬──────────────────┘
                ▼                              ▼
        ┌───────────────┐             ┌──────────────────┐
        │   MySQL 5.6+  │             │     Redis 5+     │
        │   dish_review │             │  缓存/会话/锁/    │
        │   11 张业务表  │             │  Feed/签到/GEO    │
        └───────────────┘             └──────────────────┘
```

**分层职责**

- **Controller**：接收 HTTP 请求、参数校验、返回统一 `Result`。
- **Service**：业务规则校验、Redis 与数据库编排、事务边界。
- **Mapper / MyBatis-Plus**：数据库读写；实体映射数据表。
- **Redis**：验证码、登录会话、业务缓存、分布式锁、Feed 收件箱、点赞集合、GEO 位置、签到 BitMap、全局 ID 序列。
- **拦截器**：`RefreshTokenInterceptor`（所有请求，解析 Token 并滑动续期）、`LoginInterceptor`（除公开接口外校验登录态，未登录返回 401）。

---

## 目录结构

```text
dishreview/
├── pom.xml                              # Maven 依赖与构建配置
├── src/
│   ├── main/
│   │   ├── java/com/dish/review/
│   │   │   ├── DishReviewApplication.java
│   │   │   ├── config/                  # MvcConfig / MybatisConfig / WebExceptionAdvice
│   │   │   ├── controller/              # 9 个业务控制器 + UploadController
│   │   │   ├── dto/                     # Result / LoginFormDTO / UserDTO / ScrollResult
│   │   │   ├── entity/                  # 数据库实体（tb_* 一一对应）
│   │   │   ├── mapper/                  # MyBatis-Plus Mapper 接口
│   │   │   ├── service/                 # 业务接口与实现
│   │   │   └── utils/                   # CacheClient / SimpleRedisLock / RedisIdWorker
│   │   │                                 #  拦截器 / UserHolder / 常量 / 工具类
│   │   └── resources/
│   │       ├── application.yaml         # 端口、数据源、Redis、自定义配置
│   │       ├── db/dish_review.sql       # 建库建表脚本（含种子数据）
│   │       ├── db/migration/            # 增量迁移脚本（关注索引）
│   │       └── mapper/VoucherMapper.xml # 优惠券联表查询 SQL
│   └── test/java/com/dish/review/       # 单元测试 + 集成测试
├── nginx/
│   ├── conf/nginx.conf                  # Nginx 站点配置（含安全响应头）
│   └── html/dishreview/                 # 前端页面（HTML/CSS/JS 静态资源）
└── docs/
    ├── learning/                        # 项目学习笔记
    │   ├── 08-interview-and-secondary-development-guide.md
    │   └── 09-rabbitmq-seckill-flow.md  # RabbitMQ 秒杀链路与验收
    └── development/
        └── 10-rabbitmq-seckill-reliability-development-spec.md  # 可靠性闭环开发规格
```

---

## 数据库设计

数据库名 `dish_review`，初始化脚本见 `src/main/resources/db/dish_review.sql`（建库幂等，含种子数据）。

| 表 | 说明 | 关键约束/索引 |
| --- | --- | --- |
| `tb_user` | 用户（手机号、密码、昵称、头像） | `uniqe_key_phone` 手机号唯一 |
| `tb_user_info` | 用户扩展资料（城市、介绍、粉丝数等） | 以 `user_id` 为主键 |
| `tb_shop` | 商铺（名称、类型、图片、经纬度、评分） | `type_id` 索引 |
| `tb_shop_type` | 商铺分类 | 种子数据 10 类 |
| `tb_blog` | 探店笔记（标题、图片、内容、点赞/评论数） | 主键自增 |
| `tb_blog_comments` | 笔记评论（支持一级/二级评论） | `blog_id` 索引 |
| `tb_follow` | 关注关系 | `uk_user_follow(user_id, follow_user_id)` 唯一索引；`idx_follow_user_id` 被关注者索引 |
| `tb_voucher` | 优惠券（普通券/秒杀券） | `shop_id` |
| `tb_seckill_voucher` | 秒杀券库存与时间窗 | 以 `voucher_id` 为主键，与券一对一 |
| `tb_voucher_order` | 秒杀订单 | RedisIdWorker 生成 ID；`uk_voucher_order_user_voucher(user_id, voucher_id)` 保证一人一单 |
| `tb_seckill_order_event` | 秒杀消息事件（Outbox 核心） | 10 态主状态机、`row_version` CAS、`lease_owner/lease_until/lease_token` 租约、`(status, next_retry_time, lease_until)` 任务扫描索引 |
| `tb_seckill_publish_attempt` | 发布尝试证据 | 每次实际发送一行，记录 Confirm/Return/同步异常；`uk(event_id, attempt_no)` 唯一 |
| `tb_seckill_failure_case` | 失败记录 | 承接 DLQ、回滚异常与对账冲突；`idempotency_key` 唯一索引防重复落库 |
| `tb_seckill_failure_audit` | 失败处置审计 | 人工重放/回滚/关闭操作的操作者、时间与原因 |
| `tb_sign` | 签到表（预留，签到实际存储于 Redis BitMap） | — |

> 增量脚本位于 `db/migration/`：关注索引、订单一人一单唯一索引、事件表及可靠性升级（新列 + 三张新表）均已提供；项目未内置自动迁移框架，执行前需检查目标环境。

---

## 环境要求

| 软件 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 1.8+ | 项目编译运行环境 |
| Maven | 3.6+ | 依赖管理与构建 |
| MySQL | 5.6+ | 数据库 |
| Redis | 5.0+ | 需支持 GEO（3.2+）、BitField、SetNx 等命令 |
| RabbitMQ | 3.13（当前开发环境） | 秒杀消息投递与消费；消费者由 `SECKILL_RABBIT_CONSUMER_ENABLED` 控制 |
| Nginx | 1.18（可选） | 前端托管与反向代理；纯后端调试可跳过 |

**默认连接配置**：`application.yaml` 默认指向远程服务器 `115.29.220.133`（MySQL/Redis），可通过环境变量覆盖：

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `MYSQL_HOST` / `MYSQL_PORT` | `115.29.220.133` / `3306` | MySQL 地址 |
| `MYSQL_USER` / `MYSQL_PASSWORD` | `root` / `MyStudy@2026_Sql` | MySQL 账号密码 |
| `REDIS_HOST` / `REDIS_PORT` | `115.29.220.133` / `6379` | Redis 地址 |
| `REDIS_PASSWORD` | `MyStudy@2026_Sql` | Redis 密码 |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` | `localhost` / `5672` | RabbitMQ 地址；云服务器部署时必须显式配置 |
| `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` | `guest` / `guest` | RabbitMQ 账号；不要在生产环境使用默认账号 |
| `SECKILL_RABBIT_CONSUMER_ENABLED` | `false` | RabbitMQ 可达且拓扑确认后设为 `true` |

> ⚠️ 请勿在生产环境使用默认密码。建议通过环境变量注入密钥，或直接修改 `application.yaml`。

---

## 快速开始

### 1. 初始化数据库

使用 MySQL 客户端执行初始化脚本（会自动建库、建表并导入种子数据）：

```bash
mysql -u root -p < src/main/resources/db/dish_review.sql
```

首次部署还需执行增量迁移脚本：

```bash
mysql -u root -p dish_review < src/main/resources/db/migration/20260803_add_follow_indexes.sql
mysql -u root -p dish_review < src/main/resources/db/migration/20260819_add_voucher_order_unique_index.sql
mysql -u root -p dish_review < src/main/resources/db/migration/20260820_add_seckill_order_event.sql
mysql -u root -p dish_review < src/main/resources/db/migration/20260821_seckill_reliability_upgrade.sql
mysql -u root -p dish_review < src/main/resources/db/migration/20260822_add_seckill_failure_audit.sql
```

> 秒杀可靠性升级（`20260821`/`20260822`）只增加列和新表，不修改已执行的旧迁移；存量 `FAILED` 事件需按规格 17.1 节迁移为 `MANUAL_REVIEW`，禁止直接转为 `ROLLED_BACK`。

### 2. 配置连接信息

编辑 `src/main/resources/application.yaml`，或在启动时注入环境变量，确保 MySQL / Redis 可访问。

### 3. 启动后端

```bash
# 方式一：IDE 中直接运行 DishReviewApplication
# 方式二：Maven 打包后运行
mvn clean package -DskipTests
java -jar target/dish-review-0.0.1-SNAPSHOT.jar
```

启动成功后，后端监听 `http://localhost:8081`，验证：

```bash
curl http://localhost:8081/shop-type/list
```

### 4. 启动前端（Nginx）

修改 `nginx/conf/nginx.conf` 中的 `root` 路径指向前端目录（`html/dishreview`），启动 Nginx：

```bash
# Windows
nginx.exe -p nginx-1.18.0 -c conf/nginx.conf
```

访问 `http://localhost:8080` 即可打开首页。Nginx 将 `/api` 前缀的请求转发到后端 `8081` 端口。

> 若不使用 Nginx，可在 `nginx/html/dishreview/js/common.js` 中将 `commonURL` 改为后端地址（如 `http://localhost:8081`）直接联调，注意跨域问题。

---

## 使用说明

1. **登录**：打开登录页，输入手机号 → 获取验证码 → 登录。默认处于「测试模式」（`dish-review.sms-code-mode: test`），验证码会直接由接口返回；配置为 `prod` 后接入真实短信通道。也支持手机号 + 密码登录。
2. **首页**：查看店铺分类与热门探店笔记。
3. **商铺**：按分类浏览店铺列表；店铺详情页可查看商家信息；传入经纬度参数时支持「附近 5 公里」查询。
4. **笔记**：发布探店笔记（上传图片、填写标题与内容），浏览详情可点赞/取消点赞、查看点赞 Top5、发表评论。
5. **关注**：在他人主页关注/取关，查看共同关注；关注的人发布笔记会出现在「关注动态」Feed 流中（滚动分页）。
6. **签到**：个人中心每日签到，查看本月连续签到天数。
7. **优惠券**：店铺详情页查看可用优惠券；秒杀券在活动时间窗内可参与抢购，每人限购一单。
8. **个人中心**：查看/编辑个人资料（`info.html`）。

---

## API 文档

### 通用约定

- 基础路径：`http://localhost:8081`（经 Nginx 时前缀 `/api`）。
- 响应统一封装 `Result`：

```json
{ "success": true, "errorMsg": null, "data": {}, "total": null }
```

- 除标注 **公开** 的接口外，均需登录：请求头携带 `authorization: <token>`。未登录返回 HTTP 401。

### 用户

| 方法 | 路径 | 说明 | 鉴权 |
| --- | --- | --- | --- |
| POST | `/user/code?phone=` | 发送验证码（60s 频率限制） | 公开 |
| POST | `/user/login` | 登录，body：`{phone, code}` 或 `{phone, password}`；返回 token | 公开 |
| POST | `/user/logout` | 登出，使 token 失效 | 需登录 |
| GET | `/user/me` | 当前登录用户信息（UserDTO） | 需登录 |
| GET | `/user/info/{id}` | 用户扩展资料 | 需登录 |
| GET | `/user/{id}` | 用户基本信息（脱敏 UserDTO） | 需登录 |
| POST | `/user/sign` | 当日签到 | 需登录 |
| GET | `/user/sign/count` | 本月连续签到天数 | 需登录 |

登录请求示例：

```json
// POST /user/login
{ "phone": "13800138000", "code": "123456" }
```

```json
// 响应
{ "success": true, "data": "a1b2c3d4-...-token" }
```

### 商铺与分类

| 方法 | 路径 | 说明 | 鉴权 |
| --- | --- | --- | --- |
| GET | `/shop/{id}` | 商铺详情（走缓存） | 公开 |
| POST | `/shop` | 新增商铺 | 需登录 |
| PUT | `/shop` | 更新商铺（删缓存保一致） | 需登录 |
| GET | `/shop/of/type?typeId=&current=` | 按类型分页查询 | 公开 |
| GET | `/shop/of/name?name=&current=` | 按名称模糊分页查询 | 公开 |
| GET | `/shop/of/location?typeId=&current=&x=&y=` | 附近 5km 商铺（传坐标，含距离；不传则退化分页） | 公开 |
| GET | `/shop-type/list` | 店铺分类列表（Redis 缓存） | 公开 |

### 笔记（博客）

| 方法 | 路径 | 说明 | 鉴权 |
| --- | --- | --- | --- |
| POST | `/blog` | 发布笔记（自动 XSS 转义 + 推送到粉丝 Feed） | 需登录 |
| GET | `/blog/hot?current=` | 热门笔记（按点赞数倒序） | 公开 |
| GET | `/blog/{id}` | 笔记详情 | 需登录 |
| PUT | `/blog/like/{id}` | 点赞 / 取消点赞 | 需登录 |
| GET | `/blog/likes/{id}` | 点赞用户 Top5 | 需登录 |
| GET | `/blog/of/me?current=` | 我的笔记 | 需登录 |
| GET | `/blog/of/user?id=&current=` | 指定用户的笔记 | 需登录 |
| GET | `/blog/of/follow?lastId=&offset=` | 关注 Feed 流（滚动分页） | 需登录 |

Feed 流滚动分页示例（首次 `lastId=当前时间戳, offset=0`，后续用返回的 `minTime` / `offset` 继续翻页）：

```json
// GET /blog/of/follow?lastId=1786000000000&offset=0
{
  "success": true,
  "data": {
    "list": [ { "id": 23, "title": "...", "name": "作者昵称", "isLike": false } ],
    "minTime": 1785999000123,
    "offset": 2
  }
}
```

### 评论

| 方法 | 路径 | 说明 | 鉴权 |
| --- | --- | --- | --- |
| GET | `/blog-comments/{id}` | 笔记评论列表（创建时间倒序） | 需登录 |
| POST | `/blog-comments` | 新增评论，body：`{blogId, content}` | 需登录 |

### 关注

| 方法 | 路径 | 说明 | 鉴权 |
| --- | --- | --- | --- |
| PUT | `/follow/{id}/{isFollow}` | 关注（true）/ 取消关注（false） | 需登录 |
| GET | `/follow/or/not/{id}` | 是否已关注 | 需登录 |
| GET | `/follow/common/{id}` | 与目标用户的共同关注 | 需登录 |

### 优惠券与秒杀

| 方法 | 路径 | 说明 | 鉴权 |
| --- | --- | --- | --- |
| POST | `/voucher` | 新增普通券 | 需登录 |
| POST | `/voucher/seckill` | 新增秒杀券（含库存与时间窗） | 需登录 |
| GET | `/voucher/list/{shopId}` | 店铺优惠券列表 | 公开 |
| POST | `/voucher-order/seckill/{id}` | 秒杀下单，返回订单 id（异步受理，不代表订单已落库） | 需登录 |
| GET | `/voucher-order/status/{voucherId}/{orderId}` | 查询当前用户订单处理状态（推荐）：通过券维度 orderId 反向索引直接定位 Redis 预留 | 需登录 |
| GET | `/voucher-order/status/{orderId}` | 旧版订单状态查询（兼容保留）：仅查 MySQL 订单与事件，无法定位 Redis 预留时返回 `UNAVAILABLE` | 需登录 |

### 文件上传

| 方法 | 路径 | 说明 | 鉴权 |
| --- | --- | --- | --- |
| POST | `/upload/blog` | 上传图片（multipart，字段名 `file`，≤5MB，仅 jpg/jpeg/png/gif/webp/bmp） | 需登录 |
| DELETE | `/upload/blog/delete?name=` | 删除已上传图片 | 需登录 |

---

## 核心设计亮点

### 1. 缓存双方案：穿透 + 击穿

- **缓存穿透**：`CacheClient.queryWithPassThrough` 在数据库无数据时向 Redis 写入空值（`CACHE_NULL_TTL` 2 分钟），避免恶意请求直击数据库。
- **缓存击穿**：`queryWithLogicalExpire` 采用**逻辑过期**方案，过期后通过互斥锁（SETNX）只允许一个线程回源数据库并异步重建缓存，其余请求先返回旧数据，保证高并发下缓存雪崩保护。
- **缓存一致性**：商铺更新采用「先更新数据库、再删除缓存」策略。

### 2. 秒杀：预留账本 + Outbox + 对账的可靠性闭环

- 时间窗校验（未开始/已结束直接拒绝）；`eventId/orderId` 在 Lua 前生成，重发复用原 ID。
- `SeckillVoucherLuaExecutor` 一次 Lua 原子完成库存预扣、用户集合登记、预留账本写入和 orderId 反向索引登记（同 hash tag 六个 Key 同槽，兼容 Redis Cluster）。
- 请求线程只尽力写 `PENDING` 事件后即返回；事件写入失败不回滚 Redis，由对账任务按预留账本补建事件。
- Outbox 发布任务是唯一生产者入口：CAS + 租约抢占到期事件，同一事务内创建发布尝试记录并递增 `retry_count`，事务提交后调用一次 `convertAndSend`；`spring.rabbitmq.template.retry` 已禁用，避免双层重试相乘。
- Confirm/Return 回调只按 `attemptId` 落发送证据，统一由 `SeckillOrderFailureDecisionService` 决策：订单存在优先收敛 CONSUMED；存在 ACK 且无 Return 的可路由尝试或未知尝试时禁止回滚；全部尝试明确失败才进入 `ROLLBACK_PENDING`。
- 消费者事务内先 CAS 锁定事件状态（处理回滚并发），再条件扣 MySQL 库存、写订单、标记 `CONSUMED`；异常分为可重试/永久消息/一致性三类，重试耗尽先落 `tb_seckill_failure_case` 再拒绝进 DLQ。
- `SeckillReservationRollbackTask` 持久化执行按 `eventId` 校验的回滚 Lua（失败退避 5/30/300/1800 秒）；`SeckillOrderReconciliationTask` 双向对账（Redis 预留→MySQL 事件、事件→Redis 预留、库存账面一致性），采用"最近 7 天快速扫描 + 每小时全量游标分页兜底"两层任务，任意历史券的孤儿预留都有发现入口；信息不完整的异常预留写人工失败单后原子移出待对账集合并转入人工处理集合（`seckill:reservation:manual:{voucherId}`），不会阻塞排在其后的正常预留；`SeckillStockInitScanTask` 扫描缺失库存并安全原子初始化。
- DLQ 消费者补充 `x-death` 证据；失败处置 Service（重放/回滚/关闭 + 审计）暂不开放 Controller，待 RBAC 完成后暴露。
- 数据库联合唯一索引、消费者重复键分支和订单状态查询接口（多源裁决）共同保证幂等与可观测。

### 3. Feed 流：推模式 + 滚动分页

- 发布笔记时推送到所有粉丝收件箱 `feed:{userId}`（ZSet，score 为毫秒时间戳）。
- 查询用 `reverseRangeByScoreWithScores` 按时间倒序滚动分页，返回 `minTime + offset` 处理同时间戳数据，避免常规分页在实时推送下的翻页错乱。

### 4. 点赞：ZSet 一人一赞

- `blog:liked:{blogId}` ZSet 存储点赞用户，score 为点赞时间，天然支持 Top5 排序与重复点赞去重（加锁串行化）。
- 数据库 `liked` 计数与 Redis 双写，Redis 失败时尽力补偿数据库计数。

### 5. 全局唯一 ID：RedisIdWorker

时间戳（相对起点秒数，32 位）`<< 32 |` 当日自增序列（32 位），配合 `icr:{prefix}:yyyy:MM:dd` 自增键，分布式环境下生成趋势递增、全局唯一的订单 ID。

### 6. GEO 附近商铺

按类型维护 `shop:geo:{typeId}` GEO 集合，`GEORADIUS` 查询 5km 内店铺并按距离升序，手动分页后回表填充店铺信息与距离字段。

### 7. 签到 BitMap

`sign:{userId}:yyyyMM` 按月存储，第 N 天对应第 N-1 位；`BITFIELD` 一次取出本月签到记录，位运算统计从今天起连续签到天数，单日签到 O(1) 内存开销极低。

### 8. 双拦截器会话

`RefreshTokenInterceptor`（order 0，拦截全部请求）解析 `authorization` 恢复用户到 `ThreadLocal` 并**滑动续期**；`LoginInterceptor`（order 1）对非公开接口强制登录校验。`afterCompletion` 清理 `ThreadLocal`，防止 Tomcat 线程池复用导致串号。

---

## 安全设计

项目在迭代中完成了多项安全加固，关键修复如下：

| 编号 | 措施 |
| --- | --- |
| Fix 3-4 | 文件上传扩展名白名单 + 5MB 大小限制；删除接口改为 DELETE 并做路径穿越（canonical path）校验 |
| Fix 6 | 验证码 60s 发送频率限制、错误尝试上限 5 次（防暴力破解） |
| Fix 7 | 移除验证码明文日志输出 |
| Fix 9 | 拦截器 `afterCompletion` 清理 ThreadLocal（纵深防御） |
| Fix 11 | 登录 Token 有效期缩短至 30 分钟并配合滑动续期 |
| Fix 12 | 实现登出，服务端主动删除 Redis Token |
| Fix 13 | 笔记标题/内容 HTML 转义，防存储型 XSS |
| Fix 14 | 支持密码登录（盐 + MD5 存储校验） |
| Fix 16 | Nginx 安全响应头（X-Frame-Options 等）、HTTPS 重定向配置模板 |

此外：用户信息对外仅返回脱敏 `UserDTO`（不含手机号、密码）；生产日志级别收敛为 `info`；`MvcConfig` 细粒度放行公开查询接口，写操作一律要求登录。

> 说明：密码使用「随机盐 + MD5」实现，属教学项目折中方案；生产环境建议升级为 BCrypt/Argon2 等慢哈希算法。

---

## 测试

```bash
mvn test
```

| 测试类 | 类型 | 覆盖内容 |
| --- | --- | --- |
| `FeatureCompletionTests` | 单元测试 | 签到位运算、UserDTO 脱敏、Redis Key 构造、Result/ScrollResult、Feed 滚动分页 offset 计算（不依赖外部服务） |
| `SecurityFixTests` | 单元测试 | 上传白名单、密码加盐、XSS 转义等安全逻辑（不依赖外部服务） |
| `SeckillOrderEventServiceTests` 等 | 单元测试 | 秒杀可靠性闭环：状态机迁移、CAS/租约、发布尝试证据、失败决策、回滚任务（含 `SeckillRollbackRetryPolicyTests` 退避断言）、对账任务（含全量兜底扫描、异常预留安全移交人工）、DLQ 消费者、监听前 ErrorHandler、库存安全初始化、订单状态查询、失败处置 Service、路径穿越用例矩阵（共 185 个测试） |
| `FeatureIntegrationTests` | 集成测试 | 启动完整 Spring 上下文，验证核心 Service Bean 装配（依赖 MySQL/Redis） |

---

## 贡献指南

欢迎任何形式的贡献（Issue、PR、文档改进）。请遵循以下流程：

1. **Fork** 本仓库并创建功能分支：`git checkout -b feature/xxx`。
2. **编码规范**：
   - 遵循项目现有分层（Controller → Service → Mapper）与命名风格；
   - 新增 Redis Key 时统一维护在 `RedisConstants`；
   - 对外接口返回统一 `Result`，禁止泄露敏感字段。
3. **提交信息**：使用简洁的英文描述（如 `feat: add xx`, `fix: xx`）。
4. **测试**：功能变更请补充对应单元测试，并确保 `mvn test` 通过。
5. 发起 Pull Request，描述变更内容与测试结果。

---

## 许可证

> 待确认：仓库当前 **未包含 LICENSE 文件**。

在明确开源协议前，代码默认保留所有权利。若计划开源使用，建议：

- 个人/教学使用：选择 **MIT License**（宽松、需保留版权声明）；
- 需要传染性约束：选择 **GPL-3.0**；
- 不希望被商用：选择 **Apache-2.0** 或补充 NOTICE。

选定后请在仓库根目录添加 `LICENSE` 文件。

---

## 常见问题 FAQ

**Q1：启动报错无法连接 MySQL / Redis？**
检查 `application.yaml` 中的主机、端口、账号密码，或通过环境变量覆盖（见 [环境要求](#环境要求)）。确保中间件服务已启动、防火墙放行相应端口。

**Q2：登录时收不到验证码？**
当前默认 `dish-review.sms-code-mode: test`，验证码由接口直接返回（前端可直接使用）；`prod` 模式才走真实短信通道。另外注意 60 秒频率限制与单日错误次数上限。

**Q3：秒杀下单返回了订单 id，订单一定创建成功了吗？**
不一定。返回值表示请求已通过 Redis 原子预留并尽力创建 PENDING 事件，属于异步受理；订单由 Outbox 发布任务投递到 RabbitMQ 后由消费者在事务内落库。可调用 `GET /voucher-order/status/{voucherId}/{orderId}` 查询 `PROCESSING/SUCCESS/FAILED/MANUAL_REVIEW/NOT_FOUND/UNAVAILABLE`（旧接口 `/status/{orderId}` 兼容保留，无法定位 Redis 预留时返回 `UNAVAILABLE`）。同一用户重复抢购会先被 Redis 用户集合拦截；数据库联合唯一索引兜底一人一单。

**Q4：上传图片失败？**
检查图片格式是否在白名单（jpg/jpeg/png/gif/webp/bmp）、大小是否超过 5MB；并确认 `SystemConstants.IMAGE_UPLOAD_DIR` 配置的上传目录存在且有写权限。

**Q5：店铺详情查询返回 null？**
默认采用缓存穿透方案（冷启动可用）。`queryWithLogicalExpire` 逻辑过期方案需要先执行 `saveShop2Redis` 预热缓存，否则缓存为空时直接返回 null。

**Q6：前端页面打不开 / 接口 404？**
确认 Nginx 已启动且 `root` 指向 `html/dishreview`；接口需经 `/api` 前缀转发到 8081 端口（`location /api` 配置）。浏览器访问 `http://localhost:8080`。

**Q7：如何配置真实短信服务？**
将 `application.yaml` 中 `dish-review.sms-code-mode` 改为 `prod`，并在 `UserServiceImpl.sendCode` 中接入短信 SDK 发送逻辑。

**Q8：项目是否支持分布式部署？**
Redis 分布式锁、RedisIdWorker 全局 ID、Token 会话均基于 Redis，天然支持多实例水平扩展；GEO/Feed 数据亦集中存储于 Redis，无需额外改造。

---

*本文档由对项目源码的完整分析生成，如与代码存在出入，请以源码为准。*
