# governance-suite

一个**双轨学习项目**，包含两大核心部分：

1. **分布式系统治理** - 通过订单履约流程演示微服务治理模式
2. **AI 记忆系统** - 企业级 AI 对话服务，具备用户画像和向量记忆能力

## 技术栈

| 类别 | 技术 | 版本 |
|------|------|------|
| **框架** | Spring Boot | 3.4.0 |
| | Spring Cloud | 2023.0.x |
| | Spring AI | 1.1.2 |
| **服务治理** | Nacos | 2.x |
| | Seata | 2.x |
| | Sentinel | 1.8.x |
| **数据** | MySQL | 8.0 |
| | Redis | 7.0 |
| | ShardingSphere | 5.x |
| **消息** | RabbitMQ | 3.x |
| **搜索** | Elasticsearch | 8.x |
| **AI** | DeepSeek API | - |

## 项目架构

### 🏗️ 分布式系统部分（6个服务）

```
客户端 → gateway(8080) → Nacos 注册中心
           ├→ user-service(8084)
           ├→ order-service(8081) ──┬→ inventory-service(8083)
           │                        ├→ payment-service(8082)
           │                        └→ Seata 协调器
           └→ outbox-consumer(8090) → RabbitMQ → order-service
```

**核心能力**：
- **服务治理**：Nacos 注册发现、Gateway 路由、OpenFeign 调用
- **分布式事务**：Seata AT 模式（订单-库存-支付）
- **最终一致性**：Outbox 模式 + RabbitMQ 可靠投递
- **流量控制**：Sentinel 熔断降级、Gateway IP 限流
- **高并发**：Redis Lua 秒杀、Stream 异步落库
- **数据分片**：ShardingSphere 按 user_id 分表
- **幂等防重**：Redis SET NX + DB 唯一约束

### 🤖 AI 记忆系统部分（3个服务）

```
客户端 → ai-chat-service(8085) ──┬→ user-profile-service(8086) → MySQL
                                  ├→ vector-memory-service(8087) → 向量数据库
                                  ├→ DeepSeek API
                                  └→ Elasticsearch (异步同步)
```

**核心能力**：
- **对话编排**：SessionContext 管理（意图分析、对话阶段）
- **上下文管理**：Token 预算分配与智能压缩
- **记忆检索**：用户画像 + 向量记忆多维度检索
- **异步同步**：RabbitMQ + Elasticsearch 对话历史
- **可观测性**：Prometheus 监控 + 健康检查

### 🔗 共享基础

- **common 模块**：统一返回、TraceId、异常处理、Snowflake ID、HMAC Token
- **基础设施**：MySQL, Redis, RabbitMQ, Nacos, Seata, Sentinel

## 模块清单

| 模块 | 端口 | 职责 | 所属 |
|------|------|------|------|
| `gateway` | 8080 | 网关路由、Token 校验、限流熔断 | 分布式 |
| `user-service` | 8084 | 用户注册、登录、Token 签发 | 分布式 |
| `order-service` | 8081 | 订单主流程、Seata 发起方、秒杀 | 分布式 |
| `payment-service` | 8082 | 支付准备/确认、按 user_id 分表 | 分布式 |
| `inventory-service` | 8083 | 库存扣减、乐观锁、幂等 | 分布式 |
| `outbox-consumer` | 8090 | Outbox 轮询投递到 RabbitMQ | 分布式 |
| `ai-chat-service` | 8085 | AI 对话编排、上下文管理 | AI 系统 |
| `user-profile-service` | 8086 | 用户画像、偏好管理 | AI 系统 |
| `vector-memory-service` | 8087 | 向量记忆存储与检索 | AI 系统 |
| `common` | - | 公共工具（返回、TraceId、Token、ID） | 共享 |

## 快速开始

### 最小启动（仅分布式系统核心）

```bash
# 1. 启动基础设施
docker compose up -d

# 2. 编译
mvn -T 1C clean package -DskipTests

# 3. 启动核心服务（3个终端）
mvn -q -pl gateway spring-boot:run              # 终端1: 网关
mvn -q -pl user-service spring-boot:run         # 终端2: 用户服务
mvn -q -pl order-service spring-boot:run        # 终端3: 订单服务
```

### 完整启动（分布式系统 6个服务）

```bash
# 在不同终端分别启动
mvn -q -pl gateway spring-boot:run              # 8080
mvn -q -pl user-service spring-boot:run         # 8084
mvn -q -pl order-service spring-boot:run        # 8081
mvn -q -pl payment-service spring-boot:run      # 8082
mvn -q -pl inventory-service spring-boot:run    # 8083
mvn -q -pl outbox-consumer spring-boot:run      # 8090
```

### AI 记忆系统启动（可选）

```bash
mvn -q -pl user-profile-service spring-boot:run # 8086
mvn -q -pl vector-memory-service spring-boot:run # 8087
mvn -q -pl ai-chat-service spring-boot:run      # 8085
```

### 基础设施访问

| 服务 | 地址 | 账号 |
|------|------|------|
| MySQL | localhost:3306 | root/root |
| Redis | localhost:36379 | - |
| RabbitMQ 控制台 | http://localhost:15672 | guest/guest |
| Nacos 控制台 | http://localhost:8848 | nacos/nacos |
| Sentinel Dashboard | http://localhost:8858 | - |

> **提示**：首次启动或修改数据库脚本后，执行 `docker compose down -v && docker compose up -d`

### 停止服务

```bash
# 停止所有服务
lsof -ti :8080 :8081 :8082 :8083 :8084 :8085 :8086 :8087 :8090 | xargs kill

# 停止基础设施
docker compose down
```

## 快速验证

### 分布式系统 - 5分钟体验

```bash
# 1. 注册用户
curl -X POST http://localhost:8080/api/user/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"demo","password":"123456","displayName":"Demo User"}'

# 2. 登录获取 Token
TOKEN=$(curl -s -X POST http://localhost:8080/api/user/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"demo","password":"123456"}' | jq -r '.data.token')

# 3. 下单（Seata 分布式事务）
curl -X POST http://localhost:8080/api/order/order \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-1' \
  -d '{"productId":"p1","quantity":1,"amountCents":100}'

# 4. 查看订单（记录 orderNo）
# 返回结果中的 data.orderNo 用于后续操作

# 5. 模拟支付成功
curl -X POST "http://localhost:8080/api/payment/confirm/<orderNo>" \
  -H "Authorization: Bearer $TOKEN"

# 6. 查看订单状态（应为 PAID）
curl "http://localhost:8080/api/order/order/<orderNo>" \
  -H "Authorization: Bearer $TOKEN"
```

**观察点**：
- ✅ Seata 全局事务：订单、库存、支付三个服务原子性
- ✅ Outbox 最终一致性：支付成功事件通过 MQ 异步通知
- ✅ 幂等防重：重复提交相同 Idempotency-Key 会被拒绝

### AI 记忆系统 - 3分钟体验

```bash
# 1. 发起对话
curl -X POST http://localhost:8085/api/chat \
  -H 'Content-Type: application/json' \
  -d '{
    "customerId": "user123",
    "groupId": "group1",
    "conversationId": "conv-001",
    "message": "你好，我想了解一下产品信息"
  }'

# 2. 查看会话上下文
curl http://localhost:8085/api/chat/context/conv-001

# 3. 查看监控指标
curl http://localhost:8085/actuator/prometheus | grep ai_chat

# 4. 健康检查
curl http://localhost:8085/actuator/health
```

**观察点**：
- ✅ SessionContext：自动分析用户意图和对话阶段
- ✅ Token 预算：智能分配上下文、记忆、回复的 Token 额度
- ✅ 记忆检索：从用户画像和向量库检索相关信息

## 学习路径

### 路径 1：分布式系统治理（推荐顺序）

| 步骤 | 主题 | 实验内容 | 观察重点 |
|------|------|----------|----------|
| 1 | 服务注册与发现 | 启动 gateway + user-service | Nacos 控制台看到服务注册 |
| 2 | 分布式事务 | 完整下单流程 | Seata 全局事务回滚 |
| 3 | 最终一致性 | 支付成功回调 | Outbox 重试机制 |
| 4 | 幂等与防重 | 重复提交订单 | 幂等 Key 生效 |
| 5 | 秒杀削峰 | 使用 v2 接口 | Redis Lua + Stream 异步 |
| 6 | 限流熔断 | 压测网关 | IP 限流和 Sentinel 熔断 |

### 路径 2：AI 应用架构

| 步骤 | 主题 | 实验内容 | 观察重点 |
|------|------|----------|----------|
| 1 | 对话编排 | 发起多轮对话 | SessionContext 状态变化 |
| 2 | 上下文管理 | 长对话场景 | Token 预算分配策略 |
| 3 | 记忆检索 | 查询用户画像 | 多维度记忆融合 |
| 4 | 监控与健康 | 查看 Prometheus 指标 | 响应时间、成功率 |

## 故障注入实验

用于观察系统在异常情况下的行为：

```bash
# 实验1：库存扣减失败 → 观察 Seata 全局回滚
LAB_FAIL_INVENTORY_DEDUCT=true mvn -q -pl inventory-service spring-boot:run

# 实验2：支付准备失败 → 观察事务回滚
LAB_FAIL_PAYMENT_PREPARE=true mvn -q -pl payment-service spring-boot:run

# 实验3：Outbox 投递失败 → 观察重试机制
LAB_FAIL_OUTBOX_PUBLISH=true mvn -q -pl outbox-consumer spring-boot:run
```

**实验步骤**：
1. 启动带故障开关的服务
2. 执行正常的业务操作
3. 观察日志和数据库状态
4. 关闭故障开关，观察恢复

## 详细验证指南

<details>
<summary><b>分布式系统 - 完整验证链路</b></summary>

### 1. 用户注册与登录

```bash
# 注册
curl -X POST http://localhost:8080/api/user/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"u1001","password":"123456","displayName":"User 1001"}'

# 登录
curl -X POST http://localhost:8080/api/user/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"u1001","password":"123456"}'
# 返回 token，后续请求需要: -H 'Authorization: Bearer <token>'
```

### 2. 下单（Seata 分布式事务）

```bash
curl -X POST http://localhost:8080/api/order/order \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer <token>' \
  -H 'Idempotency-Key: demo-1' \
  -d '{"productId":"p1","quantity":1,"amountCents":100}'
```

**观察点**：
- gateway 校验 Token，透传 `X-User-Id` 给下游
- order-service 发起 Seata 全局事务
- 调用 inventory-service 扣库存（乐观锁重试）
- 调用 payment-service 准备支付（分表存储）
- 写入 t_outbox 记录（本地事务）

### 3. 秒杀场景（Redis Lua + Stream）

```bash
# 准备秒杀库存
redis-cli -p 36379 set seckill:stock:sku:1001 5
redis-cli -p 36379 del seckill:buyers:sku:1001

# 调用秒杀接口
curl -X POST http://localhost:8080/api/order/order/v2 \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer <token>' \
  -H 'Idempotency-Key: req-1001-u1' \
  -d '{"productId":"1001","quantity":1,"amountCents":100}'
```

**观察点**：
- Redis Lua 原子完成：校验库存、去重、扣库存、入队
- Stream 消费器异步落库（50ms 轮询）
- DB 侧通过 `t_seckill_order_guard` 幂等防重

### 4. 支付成功（Outbox 最终一致性）

```bash
curl -X POST http://localhost:8080/api/payment/confirm/<orderNo> \
  -H 'Authorization: Bearer <token>'
```

**观察点**：
- 写入 `t_outbox(status=NEW)`
- outbox-consumer 轮询投递到 RabbitMQ（1秒间隔）
- order-service 消费消息，更新订单状态为 PAID
- 通过 `t_payment_event_consume` 去重

### 5. 查看订单状态

```bash
curl http://localhost:8080/api/order/order/<orderNo> \
  -H 'Authorization: Bearer <token>'
```

### 6. 验证 IP 限流

```bash
for i in {1..30}; do
  curl -s -o /dev/null -w "%{http_code}\n" \
    http://localhost:8080/api/order/order/not-exist
done
```

**观察点**：部分请求返回 `429`，说明 RedisRateLimiter 生效

### 7. Outbox 重试实验

```bash
# 停止 RabbitMQ
docker stop gov-rabbitmq

# 触发支付回调（会写入 Outbox）
curl -X POST http://localhost:8080/api/order/order/payment/callback/<orderNo> \
  -H 'Authorization: Bearer <token>'

# 查看 Outbox 状态（应为 FAILED）
mysql -uroot -proot -h127.0.0.1 -P3306 order_db -e "
SELECT id, event_type, status, retry_count, next_retry_at
FROM t_outbox ORDER BY id DESC LIMIT 5;"

# 恢复 RabbitMQ
docker start gov-rabbitmq

# 等待下一次轮询，状态自动变为 SENT
```

</details>

<details>
<summary><b>AI 记忆系统 - 完整验证链路</b></summary>

### 1. 发起对话

```bash
curl -X POST http://localhost:8085/api/chat \
  -H 'Content-Type: application/json' \
  -d '{
    "customerId": "user123",
    "groupId": "group1",
    "conversationId": "conv-001",
    "message": "你好，我想了解一下产品信息"
  }'
```

**观察点**：
- SessionContext 分析用户意图和对话阶段
- ContextBudgetManager 分配 Token 预算
- 从 user-profile-service 获取用户画像
- 从 vector-memory-service 检索相关记忆
- 调用 DeepSeek API 生成回复
- 异步同步对话历史到 Elasticsearch

### 2. 查看会话上下文

```bash
curl http://localhost:8085/api/chat/context/conv-001
```

### 3. 查看监控指标

```bash
curl http://localhost:8085/actuator/prometheus | grep ai_chat
```

**关键指标**：
- `ai_chat_requests_total` - 请求总数
- `ai_chat_response_time_seconds` - 响应时间
- `ai_chat_context_analysis_time_seconds` - 上下文分析耗时
- `ai_chat_memory_retrieval_time_seconds` - 记忆检索耗时

### 4. 健康检查

```bash
curl http://localhost:8085/actuator/health
```

</details>

## 核心技术模式

### 分布式系统

| 模式 | 实现 | 位置 |
|------|------|------|
| 服务治理 | Nacos 注册发现 + Gateway 路由 | gateway, common |
| 认证授权 | HMAC-SHA256 Token（非 JWT） | common/AuthTokenService |
| 分布式事务 | Seata AT 模式 | order-service/@GlobalTransactional |
| 最终一致性 | Outbox 模式 + RabbitMQ | outbox-consumer |
| 幂等防重 | Redis SET NX + DB 唯一约束 | IdempotencyService |
| 秒杀削峰 | Redis Lua + Stream 异步落库 | order-service/v2 |
| 限流熔断 | Sentinel + RedisRateLimiter | gateway |
| 数据分片 | ShardingSphere-JDBC | payment, order |
| 链路追踪 | MDC TraceId 透传 | common/TraceIdFilter |
| ID 生成 | Snowflake 算法（CAS 无锁） | common/SnowflakeIdGenerator |

### AI 记忆系统

| 模式 | 实现 | 位置 |
|------|------|------|
| 对话编排 | SessionContext 管理 | SessionContextService |
| 上下文管理 | Token 预算分配与压缩 | ContextBudgetManager |
| 记忆检索 | 用户画像 + 向量记忆 | MemoryService |
| 异步同步 | RabbitMQ + Elasticsearch | ChatHistorySyncMessage |
| 监控指标 | Micrometer + Prometheus | AiChatMetrics |
| 健康检查 | Spring Actuator | AiServicesHealthIndicator |

## 常见问题

<details>
<summary><b>Q: 服务启动失败，提示端口被占用？</b></summary>

```bash
# 查看端口占用
lsof -i :8080

# 停止占用端口的进程
kill -9 <PID>
```
</details>

<details>
<summary><b>Q: Nacos 注册失败？</b></summary>

1. 检查 Nacos 是否启动：`docker ps | grep nacos`
2. 检查 Nacos 日志：`docker logs gov-nacos`
3. 访问 Nacos 控制台：http://localhost:8848/nacos
4. 检查服务配置：`application.yml` 中的 `spring.cloud.nacos.discovery.server-addr`
</details>

<details>
<summary><b>Q: Seata 事务不回滚？</b></summary>

1. 检查 Seata Server 是否启动：`docker ps | grep seata`
2. 检查服务是否注册到 Seata：查看 Seata 控制台
3. 检查 `@GlobalTransactional` 注解是否生效
4. 查看 Seata 日志：`docker logs gov-seata`
</details>

<details>
<summary><b>Q: Outbox 消息不投递？</b></summary>

1. 检查 RabbitMQ 是否启动：`docker ps | grep rabbitmq`
2. 查看 Outbox 表状态：
   ```sql
   SELECT * FROM t_outbox WHERE status != 'SENT' ORDER BY id DESC LIMIT 10;
   ```
3. 检查 outbox-consumer 日志
4. 检查 RabbitMQ 控制台：http://localhost:15672
</details>

<details>
<summary><b>Q: AI 服务调用失败？</b></summary>

1. 检查 DeepSeek API Key 是否配置
2. 检查网络连接是否正常
3. 查看 ai-chat-service 日志
4. 检查健康检查：`curl http://localhost:8085/actuator/health`
</details>

<details>
<summary><b>Q: 数据库连接失败？</b></summary>

1. 检查 MySQL 是否启动：`docker ps | grep mysql`
2. 检查数据库是否初始化：
   ```bash
   mysql -uroot -proot -h127.0.0.1 -P3306 -e "SHOW DATABASES;"
   ```
3. 重新初始化数据库：`docker compose down -v && docker compose up -d`
</details>

## 学习价值

### 分布式系统

- ✅ 为什么要有注册中心，而不是全写死地址
- ✅ 为什么用户 ID 更适合作为分表键，而不是用户名
- ✅ 为什么 Token 校验更适合放在网关
- ✅ 为什么同步链路适合 Seata，异步链路适合 Outbox
- ✅ 为什么幂等不能只靠前端按钮防重
- ✅ 为什么秒杀要先削峰再落库
- ✅ 为什么限流和 fallback 是保护系统，不是业务成功

### AI 应用架构

- ✅ 为什么需要 SessionContext 管理对话状态
- ✅ 为什么需要 Token 预算控制（避免超限）
- ✅ 为什么需要多维度记忆检索（画像 + 向量）
- ✅ 为什么对话历史要异步同步（性能 vs 一致性）
- ✅ 为什么需要监控和健康检查（可观测性）

## 重要说明

⚠️ **这是一个学习项目，不是生产模板**

- Seata + ShardingSphere 的组合在不同版本下兼容性差异较大
- Outbox 展示的是最终一致性，不代表所有消息场景都应这样实现
- 这些方案的价值在于理解"解决什么问题"和"付出什么成本"
- 生产环境需要考虑更多因素：高可用、容灾、监控、安全等

## 贡献与反馈

欢迎提交 Issue 和 Pull Request！

---

**⭐ 如果这个项目对你有帮助，欢迎 Star！**
