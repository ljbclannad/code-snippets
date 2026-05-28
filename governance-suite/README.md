# governance-suite

一个**多轨学习项目**，包含四个完全独立的系统：

1. **[分布式系统治理](#-part-1-分布式系统治理)** - 通过订单履约流程演示微服务治理模式
2. **[AI 记忆系统](#-part-2-ai-记忆系统)** - 企业级 AI 对话服务，具备用户画像和向量记忆能力
3. **[医生助手 Agent](#-part-3-医生助手-agent)** - 基于 ReAct 架构的自主推理 AI Agent 系统
4. **[医院挂号场景](#-part-4-医院挂号场景)** - 医院挂号抢号系统，演示高并发场景下的完整治理体系

> 💡 **四个系统完全独立**：可以单独运行任一系统，也可以同时运行多个系统。

---

# 📦 Part 1: 分布式系统治理

## 系统概述

通过订单履约流程演示微服务治理模式，包含 6 个服务 + 共享基础设施。

### 架构图

```
客户端 → gateway(8080) → Nacos 注册中心
           ├→ user-service(8084)
           ├→ order-service(8081) ──┬→ inventory-service(8083)
           │                        ├→ payment-service(8082)
           │                        └→ Seata 协调器
           └→ outbox-consumer(8090) → RabbitMQ → order-service
```

### 核心能力

- **服务治理**：Nacos 注册发现、Gateway 路由、OpenFeign 调用
- **分布式事务**：Seata AT 模式（订单-库存-支付）
- **最终一致性**：Outbox 模式 + RabbitMQ 可靠投递
- **流量控制**：Sentinel 熔断降级、Gateway IP 限流
- **高并发**：Redis Lua 秒杀、Stream 异步落库
- **数据分片**：ShardingSphere 按 user_id 分表
- **幂等防重**：Redis SET NX + DB 唯一约束

### 技术栈

| 类别 | 技术 | 版本 |
|------|------|------|
| **框架** | Spring Boot | 3.4.0 |
| | Spring Cloud | 2023.0.x |
| **服务治理** | Nacos | 2.x |
| | Seata | 2.x |
| | Sentinel | 1.8.x |
| **数据** | MySQL | 8.0 |
| | Redis | 7.0 |
| | ShardingSphere | 5.x |
| **消息** | RabbitMQ | 3.x |

### 模块清单

| 模块 | 端口 | 职责 |
|------|------|------|
| `gateway` | 8080 | 网关路由、Token 校验、限流熔断 |
| `user-service` | 8084 | 用户注册、登录、Token 签发 |
| `order-service` | 8081 | 订单主流程、Seata 发起方、秒杀 |
| `payment-service` | 8082 | 支付准备/确认、按 user_id 分表 |
| `inventory-service` | 8083 | 库存扣减、乐观锁、幂等 |
| `outbox-consumer` | 8090 | Outbox 轮询投递到 RabbitMQ |
| `common` | - | 公共工具（返回、TraceId、Token、ID） |

## 快速开始

### 1. 启动基础设施

```bash
# 启动分布式系统所需的基础设施
docker compose up -d
```

**基础设施访问**：

| 服务 | 地址 | 账号 |
|------|------|------|
| MySQL | localhost:3306 | root/root |
| Redis | localhost:36379 | - |
| RabbitMQ 控制台 | http://localhost:15672 | guest/guest |
| Nacos 控制台 | http://localhost:8848 | nacos/nacos |
| Sentinel Dashboard | http://localhost:8858 | - |

> **提示**：首次启动或修改数据库脚本后，执行 `docker compose down -v && docker compose up -d`

### 2. 编译项目

```bash
mvn -T 1C clean package -DskipTests
```

### 3. 启动服务（选择一种方式）

#### 方式 A：最小启动（3个核心服务）

```bash
# 在不同终端分别启动
mvn -q -pl gateway spring-boot:run              # 终端1: 网关 (8080)
mvn -q -pl user-service spring-boot:run         # 终端2: 用户服务 (8084)
mvn -q -pl order-service spring-boot:run        # 终端3: 订单服务 (8081)
```

#### 方式 B：完整启动（6个服务）

```bash
# 在不同终端分别启动
mvn -q -pl gateway spring-boot:run              # 8080
mvn -q -pl user-service spring-boot:run         # 8084
mvn -q -pl order-service spring-boot:run        # 8081
mvn -q -pl payment-service spring-boot:run      # 8082
mvn -q -pl inventory-service spring-boot:run    # 8083
mvn -q -pl outbox-consumer spring-boot:run      # 8090
```

### 停止服务

```bash
# 停止分布式系统服务
lsof -ti :8080 :8081 :8082 :8083 :8084 :8090 | xargs kill

# 停止基础设施
docker compose down
```

## 快速验证（5分钟体验）

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

## 学习路径

| 步骤 | 主题 | 实验内容 | 观察重点 |
|------|------|----------|----------|
| 1 | 服务注册与发现 | 启动 gateway + user-service | Nacos 控制台看到服务注册 |
| 2 | 分布式事务 | 完整下单流程 | Seata 全局事务回滚 |
| 3 | 最终一致性 | 支付成功回调 | Outbox 重试机制 |
| 4 | 幂等与防重 | 重复提交订单 | 幂等 Key 生效 |
| 5 | 秒杀削峰 | 使用 v2 接口 | Redis Lua + Stream 异步 |
| 6 | 限流熔断 | 压测网关 | IP 限流和 Sentinel 熔断 |

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

## 核心技术模式

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

## 学习价值

- ✅ 为什么要有注册中心，而不是全写死地址
- ✅ 为什么用户 ID 更适合作为分表键，而不是用户名
- ✅ 为什么 Token 校验更适合放在网关
- ✅ 为什么同步链路适合 Seata，异步链路适合 Outbox
- ✅ 为什么幂等不能只靠前端按钮防重
- ✅ 为什么秒杀要先削峰再落库
- ✅ 为什么限流和 fallback 是保护系统，不是业务成功

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
<summary><b>Q: 数据库连接失败？</b></summary>

1. 检查 MySQL 是否启动：`docker ps | grep mysql`
2. 检查数据库是否初始化：
   ```bash
   mysql -uroot -proot -h127.0.0.1 -P3306 -e "SHOW DATABASES;"
   ```
3. 重新初始化数据库：`docker compose down -v && docker compose up -d`
</details>

---

# 🤖 Part 2: AI 记忆系统

## 系统概述

企业级 AI 对话服务，具备用户画像和向量记忆能力，包含 3 个服务。

### 架构图

```
客户端 → ai-chat-service(8085) ──┬→ user-profile-service(8086) → MySQL
                                  ├→ vector-memory-service(8087) → 向量数据库
                                  ├→ DeepSeek API
                                  └→ Elasticsearch (异步同步)
```

### 核心能力

- **对话编排**：SessionContext 管理（意图分析、对话阶段）
- **上下文管理**：Token 预算分配与智能压缩
- **记忆检索**：用户画像 + 向量记忆多维度检索
- **异步同步**：RabbitMQ + Elasticsearch 对话历史
- **可观测性**：Prometheus 监控 + 健康检查

### 技术栈

| 类别 | 技术 | 版本 |
|------|------|------|
| **框架** | Spring Boot | 3.4.0 |
| | Spring AI | 1.1.2 |
| **数据** | MySQL | 8.0 |
| | Elasticsearch | 8.x |
| **消息** | RabbitMQ | 3.x |
| **AI** | DeepSeek API | - |

### 模块清单

| 模块 | 端口 | 职责 |
|------|------|------|
| `ai-chat-service` | 8085 | AI 对话编排、上下文管理 |
| `user-profile-service` | 8086 | 用户画像、偏好管理 |
| `vector-memory-service` | 8087 | 向量记忆存储与检索 |

## 快速开始

### 1. 启动基础设施

```bash
# 启动 AI 系统所需的基础设施
docker compose up -d
```

**基础设施访问**：

| 服务 | 地址 | 账号 |
|------|------|------|
| MySQL | localhost:3306 | root/root |
| RabbitMQ 控制台 | http://localhost:15672 | guest/guest |
| Elasticsearch | localhost:9200 | - |

### 2. 编译项目

```bash
mvn -T 1C clean package -DskipTests
```

### 3. 启动服务

```bash
# 在不同终端分别启动
mvn -q -pl user-profile-service spring-boot:run # 8086
mvn -q -pl vector-memory-service spring-boot:run # 8087
mvn -q -pl ai-chat-service spring-boot:run      # 8085
```

### 停止服务

```bash
# 停止 AI 系统服务
lsof -ti :8085 :8086 :8087 | xargs kill

# 停止基础设施
docker compose down
```

## 快速验证（3分钟体验）

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

| 步骤 | 主题 | 实验内容 | 观察重点 |
|------|------|----------|----------|
| 1 | 对话编排 | 发起多轮对话 | SessionContext 状态变化 |
| 2 | 上下文管理 | 长对话场景 | Token 预算分配策略 |
| 3 | 记忆检索 | 查询用户画像 | 多维度记忆融合 |
| 4 | 监控与健康 | 查看 Prometheus 指标 | 响应时间、成功率 |

## 核心技术模式

| 模式 | 实现 | 位置 |
|------|------|------|
| 对话编排 | SessionContext 管理 | SessionContextService |
| 上下文管理 | Token 预算分配与压缩 | ContextBudgetManager |
| 记忆检索 | 用户画像 + 向量记忆 | MemoryService |
| 异步同步 | RabbitMQ + Elasticsearch | ChatHistorySyncMessage |
| 监控指标 | Micrometer + Prometheus | AiChatMetrics |
| 健康检查 | Spring Actuator | AiServicesHealthIndicator |

## 学习价值

- ✅ 为什么需要 SessionContext 管理对话状态
- ✅ 为什么需要 Token 预算控制（避免超限）
- ✅ 为什么需要多维度记忆检索（画像 + 向量）
- ✅ 为什么对话历史要异步同步（性能 vs 一致性）
- ✅ 为什么需要监控和健康检查（可观测性）

## 常见问题

<details>
<summary><b>Q: AI 服务调用失败？</b></summary>

1. 检查 DeepSeek API Key 是否配置
2. 检查网络连接是否正常
3. 查看 ai-chat-service 日志
4. 检查健康检查：`curl http://localhost:8085/actuator/health`
</details>

<details>
<summary><b>Q: 向量数据库连接失败？</b></summary>

1. 检查向量数据库是否启动
2. 检查配置文件中的连接信息
3. 查看 vector-memory-service 日志
</details>

<details>
<summary><b>Q: Elasticsearch 同步失败？</b></summary>

1. 检查 Elasticsearch 是否启动：`docker ps | grep elasticsearch`
2. 检查 RabbitMQ 消息队列
3. 查看 ai-chat-service 日志
</details>

---

# 🔗 共享基础

## common 模块

统一返回、TraceId、异常处理、Snowflake ID、HMAC Token

**核心组件**：
- **`ApiResponse<T>`** — 每个端点返回 `{success, code, message, traceId, data}`
- **`Trace` / `TraceIdFilter`** — MDC 传播的 `traceId` 注入到每个请求和响应
- **`AuthTokenService`** — 无状态 HMAC-SHA256 Token（2部分 Base64，非 JWT），TTL 2小时
- **`SnowflakeIdGenerator`** — 无锁 CAS，自定义纪元 2024-01-01
- **`GlobalExceptionHandler`** — 将所有异常映射到 `ApiResponse`

---

# 🤖 Part 3: 医生助手 Agent

## 系统概述

基于 **ReAct (Reasoning + Acting)** 架构的医生助手 AI Agent 系统，实现自主推理、多步决策和智能行动执行。

### 架构图

```
医生请求 → AgentController(8088)
              ↓
         ReActAgentEngine (核心引擎)
              ↓
    ┌─────────┴─────────┐
    ↓                   ↓
Thought (推理)      Action (行动)
    ↓                   ↓
Claude API         ToolRegistry (13个工具)
    ↓                   ↓
决策下一步          ├─ 低风险工具 (直接执行)
    ↓               ├─ 中风险工具 (执行+记录)
Observation         └─ 高风险工具 (需医生确认)
    ↓                   ↓
循环直到完成        PendingTaskService
    ↓                   ↓
Final Answer        医生审批 → 恢复执行
```

### 核心能力

- **自主推理**：Agent 自己决定查询什么、分析什么、做什么
- **多步决策**：根据观察结果动态调整策略，不是固定流程
- **智能行动**：不只是查询数据，还能发送消息、创建任务、起草处方
- **完全透明**：每一步 Thought、Action、Observation 都可见
- **医疗安全**：高风险操作需医生确认，低风险操作直接执行

### 技术栈

| 类别 | 技术 | 版本 |
|------|------|------|
| **框架** | Spring Boot | 3.2.5 |
| **ORM** | MyBatis Plus | 3.5.5 |
| **数据** | MySQL | 8.0 |
| | Redis | 7.0 |
| **AI** | Claude API | Opus 4.7 |
| **HTTP** | OkHttp | 4.12.0 |
| **JSON** | Fastjson | 2.0.43 |
| **监控** | Micrometer | - |

### 模块清单

| 模块 | 端口 | 职责 |
|------|------|------|
| `doctor-assistant-agent` | 8088 | ReAct Agent 引擎、工具系统、会话管理 |

## 快速开始

### 1. 数据库初始化

```bash
# 创建数据库并导入表结构
mysql -u root -p < doctor-assistant-agent/sql/schema.sql
```

### 2. 配置文件

创建 `doctor-assistant-agent/src/main/resources/application.yml`：

```yaml
server:
  port: 8088

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/doctor_assistant?useUnicode=true&characterEncoding=utf8
    username: root
    password: root
  redis:
    host: localhost
    port: 6379

# Claude API 配置
claude:
  api:
    key: ${CLAUDE_API_KEY}
    base-url: https://api.anthropic.com
    model: claude-opus-4-7
    max-tokens: 4096

# Agent 配置
agent:
  max-steps: 15
  session-timeout: 3600
  task-expire-hours: 24
```

### 3. 编译运行

```bash
# 编译
mvn clean package -DskipTests

# 运行
mvn -q -pl doctor-assistant-agent spring-boot:run
```

### 停止服务

```bash
# 停止 Agent 服务
lsof -ti :8088 | xargs kill
```

## 快速验证（5分钟体验）

```bash
# 1. 执行 Agent 任务
curl -X POST http://localhost:8088/api/agent/execute \
  -H 'Content-Type: application/json' \
  -d '{
    "customerId": "C001",
    "doctorId": "D001",
    "goal": "分析患者健康状况，识别风险因素"
  }'

# 2. 查询会话详情
curl http://localhost:8088/api/agent/sessions/{sessionId}

# 3. 获取待确认任务
curl http://localhost:8088/api/agent/pending-tasks?doctorId=D001

# 4. 审批任务
curl -X POST http://localhost:8088/api/agent/pending-tasks/{taskId}/approve \
  -H 'Content-Type: application/json' \
  -d '{
    "approved": true,
    "comment": "同意执行"
  }'
```

**观察点**：
- ✅ ReAct 循环：Thought → Action → Observation 的完整流程
- ✅ 工具调用：13个医疗工具的智能选择和执行
- ✅ 风险控制：高风险操作自动暂停等待医生审批
- ✅ 执行透明：每一步推理和行动都完整记录

## ReAct 工作流程示例

```
医生："分析患者张三的健康状况"
    ↓
【第1步】Agent Thought: "我需要先了解患者的基本信息"
        Agent Action: get_patient_profile
        Action Input: {"customerId": "张三"}
        Observation: {"age": 35, "gender": "女", "bmi": 22.5, "chronicDiseases": ["高血压"]}
    ↓
【第2步】Agent Thought: "患者有高血压，需要查看用药情况"
        Agent Action: get_prescription_history
        Action Input: {"customerId": "张三"}
        Observation: [历史处方数据]
    ↓
【第3步】Agent Thought: "体重目标是减重，需要查看进展"
        Agent Action: get_weight_history
        Action Input: {"customerId": "张三", "days": 30}
        Observation: [体重下降3kg，但最近反弹1kg]
    ↓
【第4步】Agent Thought: "反弹可能有原因，查看聊天记录"
        Agent Action: get_chat_history
        Action Input: {"customerId": "张三"}
        Observation: [患者说"有点焦虑"、"是不是方法不对"]
    ↓
【第5步】Agent Thought: "患者有焦虑情绪，建议心理咨询"
        Agent Action: draft_prescription
        Action Input: {"customerId": "张三", "recommendations": [...]}
        ⚠️ 高风险操作 → 创建待确认任务 → Agent 暂停
    ↓
【医生审批】医生查看详情 → 批准执行
    ↓
Agent 恢复执行
    ↓
【第6步】Agent Thought: "现在我有足够信息生成完整分析了"
        Final Answer: [完整的患者分析报告]
```

## 工具系统（13个工具）

### 低风险工具（直接执行）
1. **get_patient_profile** - 查询患者基本信息
2. **get_weight_history** - 查询体重历史
3. **get_chat_history** - 查询聊天记录
4. **get_prescription_history** - 查询历史处方
5. **search_similar_cases** - 搜索相似病例（Milvus）
6. **analyze_weight_trend** - 分析体重趋势并预测
7. **analyze_sentiment** - 分析聊天情感倾向
8. **calculate_risk_score** - 计算患者风险评分

### 中风险工具（执行但记录）
9. **send_reminder** - 给患者发送提醒消息
10. **create_followup_task** - 创建随访任务
11. **flag_high_risk_patient** - 标记高风险患者

### 高风险工具（需医生确认）
12. **draft_prescription** - 起草处方建议
13. **suggest_diagnosis** - 提供诊断建议

## 学习路径

| 步骤 | 主题 | 实验内容 | 观察重点 |
|------|------|----------|----------|
| 1 | ReAct 基础 | 简单患者分析 | Thought-Action-Observation 循环 |
| 2 | 工具调用 | 多工具组合使用 | 工具选择的智能性 |
| 3 | 风险控制 | 触发高风险操作 | 暂停-审批-恢复流程 |
| 4 | 多步推理 | 复杂病例分析 | 动态决策和策略调整 |
| 5 | 会话管理 | 长时间会话 | 会话状态和超时处理 |

## 核心技术模式

| 模式 | 实现 | 位置 |
|------|------|------|
| ReAct 循环 | Thought-Action-Observation | ReActAgentEngine |
| 工具注册 | 动态工具发现和调用 | ToolRegistry |
| 风险分级 | 三级风险控制策略 | ToolRiskLevel |
| 会话管理 | 暂停/恢复机制 | AgentSessionService |
| 审批流程 | 待确认任务队列 | PendingTaskService |
| Prompt 工程 | 结构化 Prompt 构建 | ReActPromptBuilder |
| 响应解析 | JSON 结构化解析 | ReActResponseParser |
| 执行日志 | 完整执行链路记录 | AgentExecutionLog |

## 学习价值

- ✅ 为什么需要 ReAct 架构（推理+行动的结合）
- ✅ 为什么 Agent 比固定流程更智能（动态决策）
- ✅ 为什么需要工具系统（扩展 AI 能力边界）
- ✅ 为什么需要风险分级（医疗安全保障）
- ✅ 为什么需要会话管理（支持长时间任务）
- ✅ 为什么需要执行透明（可解释性和可审计性）

## 性能指标

| 指标 | 目标值 | 说明 |
|------|--------|------|
| 平均步数 | 5-8步 | 完成一次分析的平均步数 |
| 总响应时间 | P95 < 15s | 包含所有 ReAct 循环 |
| 单步延迟 | P95 < 2s | 单次 Thought+Action 的延迟 |
| 并发能力 | 50 QPS | 单实例支持的并发 Agent 数 |

## 常见问题

<details>
<summary><b>Q: Agent 执行失败或陷入循环？</b></summary>

1. 检查 Claude API Key 是否配置正确
2. 查看执行日志中的 Thought 和 Action
3. 检查是否达到最大步数限制（默认15步）
4. 查看工具执行是否返回有效的 Observation
</details>

<details>
<summary><b>Q: 高风险操作没有暂停？</b></summary>

1. 检查工具的风险等级配置
2. 查看 PendingTaskService 是否正常工作
3. 检查数据库中的 agent_pending_task 表
4. 查看会话状态是否正确更新为 WAITING_APPROVAL
</details>

<details>
<summary><b>Q: 工具调用失败？</b></summary>

1. 检查工具是否正确注册到 ToolRegistry
2. 查看工具的输入参数是否符合要求
3. 检查数据库中是否有相关数据
4. 查看工具执行器的异常日志
</details>

<details>
<summary><b>Q: 会话超时如何处理？</b></summary>

1. 默认会话超时时间为 3600 秒（1小时）
2. 超时的会话状态会自动更新为 TIMEOUT
3. 可以通过配置 `agent.session-timeout` 调整超时时间
4. 超时后可以创建新会话重新执行
</details>

## 开发计划

### Phase 1: 核心引擎（当前阶段）
- [x] 项目结构搭建
- [x] 数据模型定义
- [x] 数据库设计
- [ ] ReActAgentEngine 实现
- [ ] ToolRegistry 实现
- [ ] ActionExecutor 实现
- [ ] Prompt 构建和解析

### Phase 2: 工具扩展
- [ ] 13个工具的完整实现
- [ ] Claude API 客户端
- [ ] 待确认任务流程
- [ ] 会话管理服务

### Phase 3: API 开发
- [ ] Controller 层实现
- [ ] Service 层实现
- [ ] Mapper 层实现
- [ ] 异常处理

### Phase 4: 测试部署
- [ ] 单元测试
- [ ] 集成测试
- [ ] Docker 部署
- [ ] 监控告警

---

# 🏥 Part 4: 医院挂号场景

## 系统概述

医院挂号抢号系统，通过真实的医疗场景演示高并发场景下的完整治理体系，从基础设施到业务逻辑的10个治理层次。

### 架构图

```
客户端 → gateway(8080) ──┬→ 双层限流（IP + 用户）
                        └→ appointment-service(8089)
                              ├→ Redis Lua 抢号
                              ├→ Seata 分布式事务
                              ├→ MySQL 订单存储
                              └→ RabbitMQ 异步通知
```

### 核心能力

- **排班查询**：查询医生排班信息，支持按科室、医生、日期筛选
- **抢号下单**：Redis Lua 原子抢号，防止超卖和重复预约
- **支付确认**：Seata 分布式事务保证订单-支付-号源一致性
- **订单取消**：支持用户取消预约，自动释放号源
- **双层限流**：Gateway IP 限流 + 用户级限流，保护系统稳定性
- **异步通知**：RabbitMQ 异步发送预约成功/取消通知

### 技术栈

| 类别 | 技术 | 版本 |
|------|------|------|
| **框架** | Spring Boot | 3.4.0 |
| | Spring Cloud | 2023.0.x |
| **服务治理** | Nacos | 2.x |
| | Seata | 2.x |
| **数据** | MySQL | 8.0 |
| | Redis | 7.0 |
| **消息** | RabbitMQ | 3.x |

### 模块清单

| 模块 | 端口 | 职责 |
|------|------|------|
| `gateway` | 8080 | 网关路由、Token 校验、双层限流 |
| `appointment-service` | 8089 | 排班查询、抢号、支付、取消、通知 |
| `common` | - | 公共工具（返回、TraceId、Token、ID） |

## 快速开始

### 1. 启动基础设施

```bash
# 启动医院挂号系统所需的基础设施
docker compose up -d
```

**基础设施访问**：

| 服务 | 地址 | 账号 |
|------|------|------|
| MySQL | localhost:3306 | root/root |
| Redis | localhost:36379 | - |
| RabbitMQ 控制台 | http://localhost:15672 | guest/guest |
| Nacos 控制台 | http://localhost:8848 | nacos/nacos |

### 2. 编译项目

```bash
mvn -T 1C clean package -DskipTests
```

### 3. 启动服务

```bash
# 在不同终端分别启动
mvn -q -pl gateway spring-boot:run              # 终端1: 网关 (8080)
mvn -q -pl user-service spring-boot:run         # 终端2: 用户服务 (8084)
mvn -q -pl appointment-service spring-boot:run  # 终端3: 挂号服务 (8089)
```

### 停止服务

```bash
# 停止医院挂号系统服务
lsof -ti :8080 :8084 :8089 | xargs kill

# 停止基础设施
docker compose down
```

## 快速验证（5分钟体验）

```bash
# 1. 注册用户
curl -X POST http://localhost:8080/api/user/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"patient001","password":"123456","displayName":"患者001"}'

# 2. 登录获取 Token
TOKEN=$(curl -s -X POST http://localhost:8080/api/user/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"patient001","password":"123456"}' | jq -r '.data.token')

# 3. 查询排班信息（使用未来日期，例如 2026-06-15）
curl "http://localhost:8080/api/appointment/schedules?departmentId=1&date=2026-06-15" \
  -H "Authorization: Bearer $TOKEN"

# 4. 抢号下单（记录 appointmentId）
curl -X POST http://localhost:8080/api/appointment/reserve \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: patient001-appt-1' \
  -d '{
    "scheduleId": "sch-001",
    "patientName": "张三",
    "patientPhone": "13800138000",
    "patientIdCard": "110101199001011234"
  }'

# 5. 支付确认
curl -X POST "http://localhost:8080/api/appointment/<appointmentId>/pay" \
  -H "Authorization: Bearer $TOKEN"

# 6. 查看预约详情（应为 PAID 状态）
curl "http://localhost:8080/api/appointment/<appointmentId>" \
  -H "Authorization: Bearer $TOKEN"

# 7. 取消预约（可选）
curl -X POST "http://localhost:8080/api/appointment/<appointmentId>/cancel" \
  -H "Authorization: Bearer $TOKEN"
```

**观察点**：
- ✅ Redis Lua 抢号：原子性保证不超卖、不重复
- ✅ Seata 分布式事务：预约-支付-号源扣减原子性
- ✅ 双层限流：IP 限流 + 用户级限流保护系统
- ✅ 幂等防重：重复提交相同 Idempotency-Key 会被拒绝
- ✅ 异步通知：RabbitMQ 发送预约成功/取消通知

## 学习路径

| 步骤 | 主题 | 实验内容 | 观察重点 |
|------|------|----------|----------|
| 1 | 排班查询 | 查询医生排班 | 基础 CRUD 和数据模型 |
| 2 | 抢号下单 | Redis Lua 抢号 | 原子性和防超卖 |
| 3 | 支付确认 | Seata 分布式事务 | 跨服务事务一致性 |
| 4 | 订单取消 | 号源释放 | 状态机和补偿逻辑 |
| 5 | 双层限流 | 压测网关 | IP 限流和用户限流 |
| 6 | 异步通知 | 查看 RabbitMQ | 消息投递和消费 |
| 7 | 幂等防重 | 重复提交 | 幂等 Key 生效 |
| 8 | 并发测试 | 多用户抢号 | 并发控制和数据一致性 |

## 10个治理层次详解

### L1: 基础设施层

**职责**：提供数据存储、缓存、消息队列等基础能力

| 组件 | 作用 | 配置 |
|------|------|------|
| MySQL | 持久化存储（排班、订单、支付） | 端口 3306 |
| Redis | 号源库存、限流计数、幂等锁 | 端口 36379 |
| RabbitMQ | 异步通知（预约成功/取消） | 端口 5672, 管理端口 15672 |

**关键设计**：
- MySQL 存储核心业务数据，保证持久化和事务性
- Redis 存储热点数据（号源库存），支持高并发读写
- RabbitMQ 解耦同步链路和异步通知

### L2: 服务注册与发现

**职责**：服务自动注册、健康检查、动态路由

| 组件 | 作用 | 配置 |
|------|------|------|
| Nacos | 服务注册中心 | 端口 8848 |

**关键设计**：
- 服务启动时自动注册到 Nacos
- Gateway 通过 Nacos 动态发现 appointment-service
- 支持服务实例的动态上下线

**代码位置**：
- `appointment-service/application.yml` - Nacos 客户端配置
- `gateway/application.yml` - Nacos 服务发现配置

### L3: API 网关层

**职责**：统一入口、认证鉴权、双层限流、路由转发

| 功能 | 实现 | 配置 |
|------|------|------|
| Token 校验 | AuthTokenGlobalFilter | order=-90 |
| IP 限流 | RedisRateLimiter | 10 req/s per IP |
| 用户限流 | UserRateLimitFilter | 5 req/s per user |
| 路由转发 | Spring Cloud Gateway | lb://appointment-service |

**关键设计**：
- **双层限流**：先 IP 限流（防刷），再用户限流（公平性）
- **Token 透传**：校验后注入 `X-User-Id` / `X-Username` 给下游
- **公共路径豁免**：`/api/user/register`, `/api/user/login` 不校验 Token

**代码位置**：
- `gateway/filter/AuthTokenGlobalFilter.java` - Token 校验
- `gateway/filter/UserRateLimitFilter.java` - 用户限流
- `gateway/application.yml` - IP 限流配置

### L4: 分布式事务层

**职责**：保证跨服务操作的原子性（预约-支付-号源扣减）

| 组件 | 作用 | 模式 |
|------|------|------|
| Seata | 分布式事务协调器 | AT 模式 |

**关键设计**：
- `AppointmentAppService.payAppointment()` 标注 `@GlobalTransactional`
- 涉及三个操作：更新预约状态、创建支付记录、扣减号源库存
- 任一操作失败，Seata 自动回滚所有操作

**代码位置**：
- `appointment-service/service/AppointmentAppService.java` - `@GlobalTransactional`
- `appointment-service/application.yml` - Seata 客户端配置

### L5: 幂等性保证层

**职责**：防止重复提交、重复支付、重复扣减

| 场景 | 实现 | Key 格式 |
|------|------|----------|
| 抢号幂等 | Redis SET NX | `idempotency:{requestId}` |
| 支付幂等 | Redis SET NX | `idem:appt:pay:{userId}:{appointmentId}` |
| 通知幂等 | DB 唯一约束 | `t_appointment_notification(appointment_id, event_type)` |

**关键设计**：
- **双重保障**：Redis 快速拦截 + DB 唯一约束兜底
- **TTL 设置**：Redis 幂等锁 1 小时过期，避免永久占用
- **请求级幂等**：客户端传入 `Idempotency-Key` 请求头

**代码位置**：
- `common/service/IdempotencyService.java` - 幂等锁实现
- `appointment-service/service/AppointmentAppService.java` - 幂等调用

### L6: 库存管理层

**职责**：号源库存的原子扣减，防止超卖和重复预约

| 实现 | 技术 | 特点 |
|------|------|------|
| Redis Lua 脚本 | Lua 原子执行 | 校验库存、去重、扣减、入队一次完成 |

**关键设计**：
- **原子性**：Lua 脚本在 Redis 中原子执行，不会被打断
- **防超卖**：先检查库存，库存不足直接返回失败
- **防重复**：通过 `appt:reserved:{scheduleId}` Set 记录已预约用户
- **Key 设计**：
  - `appt:stock:{scheduleId}` - 号源库存
  - `appt:reserved:{scheduleId}` - 已预约用户集合

**代码位置**：
- `appointment-service/service/AppointmentReserveService.java` - Lua 脚本执行
- `appointment-service/lua/reserve_appointment.lua` - Lua 脚本

### L7: 订单状态机层

**职责**：管理预约订单的生命周期，保证状态流转的正确性

| 状态 | 说明 | 允许的操作 |
|------|------|------------|
| NEW | 已预约，未支付 | 支付、取消 |
| RESERVED | 已预约（同 NEW） | 支付、取消 |
| PAID | 已支付 | 取消（退款） |
| CANCELLED | 已取消 | 无 |
| EXPIRED | 已过期 | 无 |

**状态流转**：
```
NEW/RESERVED ──支付──> PAID ──取消──> CANCELLED
     │                              ↑
     └──────────取消─────────────────┘
     │
     └──────────超时─────────────> EXPIRED
```

**关键设计**：
- **状态校验**：每个操作前检查当前状态是否允许
- **状态更新**：使用乐观锁（version 字段）防止并发冲突
- **补偿逻辑**：取消时自动释放号源库存

**代码位置**：
- `appointment-service/entity/Appointment.java` - 状态枚举
- `appointment-service/service/AppointmentAppService.java` - 状态流转逻辑

### L8: 异步通知层

**职责**：解耦同步链路和异步通知，提升用户体验

| 事件 | 触发时机 | 通知内容 |
|------|----------|----------|
| appointment.paid | 支付成功 | 预约成功通知（就诊时间、地点） |
| appointment.cancelled | 取消预约 | 取消成功通知 |

**关键设计**：
- **事件驱动**：支付成功后发布事件到 RabbitMQ
- **异步处理**：通知服务消费消息，发送短信/推送
- **重试机制**：消息消费失败自动重试（最多 3 次）
- **幂等消费**：通过 `t_appointment_notification` 表去重

**代码位置**：
- `appointment-service/event/AppointmentPaidEventHandler.java` - 事件发布
- `appointment-service/listener/AppointmentNotificationListener.java` - 事件消费

### L9: 可观测性层

**职责**：链路追踪、日志记录、监控告警

| 功能 | 实现 | 说明 |
|------|------|------|
| TraceId | MDC 透传 | 全链路追踪，从 Gateway 到 Service |
| 业务日志 | SLF4J + Logback | 记录关键操作（抢号、支付、取消） |
| 监控指标 | Actuator | 健康检查、JVM 指标 |

**关键设计**：
- **TraceId 透传**：Gateway 生成 TraceId，通过 HTTP Header 传递给下游
- **结构化日志**：每条日志包含 traceId、userId、操作类型、结果
- **异常捕获**：GlobalExceptionHandler 统一处理异常，返回标准格式

**代码位置**：
- `common/filter/TraceIdFilter.java` - TraceId 生成和透传
- `common/handler/GlobalExceptionHandler.java` - 异常处理
- `appointment-service/application.yml` - Actuator 配置

### L10: 测试验证层

**职责**：端到端测试，验证系统的正确性和稳定性

| 测试类型 | 工具 | 验证内容 |
|----------|------|----------|
| 功能测试 | curl 脚本 | 正常流程（查询、抢号、支付、取消） |
| 并发测试 | JMeter / wrk | 多用户同时抢号，验证不超卖 |
| 限流测试 | curl 循环 | 触发 IP 限流和用户限流 |
| 幂等测试 | curl 重复请求 | 验证幂等 Key 生效 |

**测试脚本**：
```bash
# 并发抢号测试（10个用户抢5个号）
for i in {1..10}; do
  curl -X POST http://localhost:8080/api/appointment/reserve \
    -H "Authorization: Bearer $TOKEN_$i" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: user-$i-appt-1" \
    -d '{
      "scheduleId": "sch-001",
      "patientName": "患者'$i'",
      "patientPhone": "1380013800'$i'",
      "patientIdCard": "11010119900101123'$i'"
    }' &
done
wait

# 验证结果：只有5个成功，5个失败（库存不足）
```

**代码位置**：
- `test-appointment.sh` - 功能测试脚本（项目根目录）

## 核心技术模式

| 模式 | 实现 | 位置 |
|------|------|------|
| 服务治理 | Nacos 注册发现 + Gateway 路由 | gateway, appointment-service |
| 认证授权 | HMAC-SHA256 Token | common/AuthTokenService |
| 双层限流 | IP 限流 + 用户限流 | gateway/filter |
| 分布式事务 | Seata AT 模式 | AppointmentAppService/@GlobalTransactional |
| 幂等防重 | Redis SET NX + DB 唯一约束 | IdempotencyService |
| 原子抢号 | Redis Lua 脚本 | AppointmentReserveService |
| 订单状态机 | 状态枚举 + 乐观锁 | Appointment.status |
| 异步通知 | RabbitMQ 事件驱动 | AppointmentPaidEventHandler |
| 链路追踪 | MDC TraceId 透传 | common/TraceIdFilter |
| ID 生成 | Snowflake 算法 | common/SnowflakeIdGenerator |

## 学习价值

- ✅ 为什么需要双层限流（IP + 用户）
- ✅ 为什么抢号要用 Redis Lua 而不是数据库锁
- ✅ 为什么支付要用 Seata 分布式事务
- ✅ 为什么需要幂等防重（前端防抖不够）
- ✅ 为什么通知要异步（不阻塞主流程）
- ✅ 为什么需要订单状态机（保证状态流转正确性）
- ✅ 为什么需要 TraceId（排查问题的利器）
- ✅ 为什么需要并发测试（验证系统的正确性）

## 常见问题

<details>
<summary><b>Q: 抢号失败，提示库存不足？</b></summary>

1. 检查 Redis 中的库存：`redis-cli -p 36379 get appt:stock:{scheduleId}`
2. 检查是否已经预约过：`redis-cli -p 36379 sismember appt:reserved:{scheduleId} {userId}`
3. 查看数据库中的排班信息：
   ```sql
   SELECT * FROM t_schedule WHERE id = '{scheduleId}';
   ```
4. 重新初始化库存：`redis-cli -p 36379 set appt:stock:{scheduleId} 10`
</details>

<details>
<summary><b>Q: 支付失败，Seata 事务不回滚？</b></summary>

1. 检查 Seata Server 是否启动：`docker ps | grep seata`
2. 检查服务是否注册到 Seata：查看 Seata 控制台
3. 检查 `@GlobalTransactional` 注解是否生效
4. 查看 Seata 日志：`docker logs gov-seata`
</details>

<details>
<summary><b>Q: 通知消息不发送？</b></summary>

1. 检查 RabbitMQ 是否启动：`docker ps | grep rabbitmq`
2. 查看 RabbitMQ 控制台：http://localhost:15672
3. 检查队列中是否有消息积压
4. 查看 appointment-service 日志中的事件发布记录
</details>

<details>
<summary><b>Q: 限流不生效？</b></summary>

1. 检查 Redis 是否启动：`docker ps | grep redis`
2. 查看 Gateway 日志中的限流记录
3. 检查限流配置：`gateway/application.yml`
4. 验证限流计数：`redis-cli -p 36379 keys rate_limit:*`
</details>

<details>
<summary><b>Q: 并发测试出现超卖？</b></summary>

1. 检查 Lua 脚本是否正确加载
2. 查看 Redis 日志，确认 Lua 脚本执行
3. 检查数据库中的预约记录数量
4. 验证 Redis 库存和数据库记录是否一致
</details>

---

# 📚 详细验证指南

## 分布式系统 - 完整验证链路

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

## AI 记忆系统 - 完整验证链路

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

## 医院挂号场景 - 完整验证链路

### 1. 用户注册与登录

```bash
# 注册多个用户（用于并发测试）
for i in {1..10}; do
  curl -X POST http://localhost:8080/api/user/register \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"patient00$i\",\"password\":\"123456\",\"displayName\":\"患者00$i\"}"
done

# 登录获取 Token
TOKEN=$(curl -s -X POST http://localhost:8080/api/user/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"patient001","password":"123456"}' | jq -r '.data.token')
```

### 2. 查询排班信息

```bash
# 查询指定科室和日期的排班
curl "http://localhost:8080/api/appointment/schedules?departmentId=1&date=2026-06-15" \
  -H "Authorization: Bearer $TOKEN"

# 查询指定医生的排班
curl "http://localhost:8080/api/appointment/schedules?doctorId=D001&date=2026-06-15" \
  -H "Authorization: Bearer $TOKEN"
```

**观察点**：
- 返回医生信息、科室信息、可预约时段
- 显示剩余号源数量

### 3. 抢号下单（Redis Lua 原子操作）

```bash
# 初始化号源库存（测试前准备）
redis-cli -p 36379 set appt:stock:sch-001 5
redis-cli -p 36379 del appt:reserved:sch-001

# 抢号
curl -X POST http://localhost:8080/api/appointment/reserve \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: patient001-appt-1' \
  -d '{
    "scheduleId": "sch-001",
    "patientName": "张三",
    "patientPhone": "13800138000",
    "patientIdCard": "110101199001011234"
  }'

# 记录返回的 appointmentId
```

**观察点**：
- Redis Lua 脚本原子执行：校验库存、去重、扣减、返回结果
- 幂等 Key 生效：重复提交返回 409 Conflict
- 库存不足时返回失败

### 4. 支付确认（Seata 分布式事务）

```bash
curl -X POST "http://localhost:8080/api/appointment/<appointmentId>/pay" \
  -H "Authorization: Bearer $TOKEN"
```

**观察点**：
- Seata 全局事务：更新预约状态、创建支付记录、扣减号源库存
- 任一操作失败，自动回滚
- 支付成功后发布事件到 RabbitMQ

### 5. 查看预约详情

```bash
curl "http://localhost:8080/api/appointment/<appointmentId>" \
  -H "Authorization: Bearer $TOKEN"
```

**观察点**：
- 订单状态为 PAID
- 包含就诊时间、医生信息、患者信息

### 6. 取消预约

```bash
curl -X POST "http://localhost:8080/api/appointment/<appointmentId>/cancel" \
  -H "Authorization: Bearer $TOKEN"
```

**观察点**：
- 订单状态变为 CANCELLED
- 自动释放号源库存（Redis 库存 +1）
- 发布取消事件到 RabbitMQ

### 7. 验证双层限流

```bash
# 测试 IP 限流（10 req/s）
for i in {1..30}; do
  curl -s -o /dev/null -w "%{http_code}\n" \
    "http://localhost:8080/api/appointment/schedules?departmentId=1&date=2026-06-15"
  sleep 0.05
done

# 测试用户限流（5 req/s）
for i in {1..20}; do
  curl -s -o /dev/null -w "%{http_code}\n" \
    "http://localhost:8080/api/appointment/schedules?departmentId=1&date=2026-06-15" \
    -H "Authorization: Bearer $TOKEN"
  sleep 0.1
done
```

**观察点**：
- 部分请求返回 `429 Too Many Requests`
- Redis 中的限流计数器：`redis-cli -p 36379 keys rate_limit:*`

### 8. 并发抢号测试（防超卖验证）

```bash
# 准备：初始化5个号源
redis-cli -p 36379 set appt:stock:sch-002 5
redis-cli -p 36379 del appt:reserved:sch-002

# 10个用户同时抢5个号
for i in {1..10}; do
  TOKEN_i=$(curl -s -X POST http://localhost:8080/api/user/login \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"patient00$i\",\"password\":\"123456\"}" | jq -r '.data.token')
  
  curl -X POST http://localhost:8080/api/appointment/reserve \
    -H "Authorization: Bearer $TOKEN_i" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: patient00$i-appt-2" \
    -d "{
      \"scheduleId\": \"sch-002\",
      \"patientName\": \"患者00$i\",
      \"patientPhone\": \"1380013800$i\",
      \"patientIdCard\": \"11010119900101123$i\"
    }" &
done
wait

# 验证结果
echo "Redis 剩余库存："
redis-cli -p 36379 get appt:stock:sch-002

echo "已预约用户数："
redis-cli -p 36379 scard appt:reserved:sch-002

echo "数据库预约记录数："
mysql -uroot -proot -h127.0.0.1 -P3306 appointment_db -e "
SELECT COUNT(*) FROM t_appointment WHERE schedule_id = 'sch-002' AND status != 'CANCELLED';"
```

**观察点**：
- 只有 5 个用户抢号成功，5 个失败
- Redis 库存为 0
- 已预约用户集合大小为 5
- 数据库记录数为 5
- 无超卖现象

### 9. 异步通知验证

```bash
# 查看 RabbitMQ 队列
curl -u guest:guest http://localhost:15672/api/queues/%2F/appointment.notification

# 查看通知记录
mysql -uroot -proot -h127.0.0.1 -P3306 appointment_db -e "
SELECT * FROM t_appointment_notification ORDER BY id DESC LIMIT 10;"
```

**观察点**：
- RabbitMQ 队列中有消息
- 通知记录表中有对应记录
- 通知状态为 SENT

### 10. 幂等性验证

```bash
# 重复提交相同的抢号请求
for i in {1..3}; do
  curl -X POST http://localhost:8080/api/appointment/reserve \
    -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' \
    -H 'Idempotency-Key: patient001-appt-duplicate' \
    -d '{
      "scheduleId": "sch-003",
      "patientName": "李四",
      "patientPhone": "13900139000",
      "patientIdCard": "110101199002021234"
    }'
  echo ""
done
```

**观察点**：
- 第一次请求成功（201 Created）
- 后续请求返回 409 Conflict（幂等 Key 已存在）
- 只创建了一条预约记录

---

# ⚠️ 重要说明

**这是一个学习项目，不是生产模板**

- Seata + ShardingSphere 的组合在不同版本下兼容性差异较大
- Outbox 展示的是最终一致性，不代表所有消息场景都应这样实现
- 这些方案的价值在于理解"解决什么问题"和"付出什么成本"
- 生产环境需要考虑更多因素：高可用、容灾、监控、安全等

---

# 🤝 贡献与反馈

欢迎提交 Issue 和 Pull Request！

**⭐ 如果这个项目对你有帮助，欢迎 Star！**
