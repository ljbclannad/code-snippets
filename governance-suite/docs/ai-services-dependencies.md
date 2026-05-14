# AI 服务依赖关系说明

## 结论

**AI 相关的 3 个服务（ai-chat-service、user-profile-service、vector-memory-service）完全独立于订单/支付等业务服务，可以单独运行。**

## 服务依赖分析

### 1. ai-chat-service (端口 8103)

#### 依赖的基础设施
- ✅ **RabbitMQ** (localhost:5672) - 与其他 AI 服务通信
- ✅ **Nacos** (localhost:8848) - 服务注册与发现
- ✅ **DeepSeek API** - AI 对话能力

#### 依赖的 AI 服务
- ✅ **user-profile-service** - 通过 RabbitMQ RPC 获取用户画像
- ✅ **vector-memory-service** - 通过 RabbitMQ RPC 获取历史记忆

#### 不依赖
- ❌ 数据库（已排除 DataSourceAutoConfiguration）
- ❌ order-service
- ❌ payment-service
- ❌ inventory-service
- ❌ user-service

---

### 2. user-profile-service (端口 8101)

#### 依赖的基础设施
- ✅ **MySQL** (localhost:3306) - 数据库 `governance`
- ✅ **Redis** (localhost:36379) - 缓存用户画像
- ✅ **RabbitMQ** (localhost:5672) - 接收画像请求
- ✅ **Nacos** (localhost:8848) - 服务注册

#### 使用的数据表
```sql
governance.t_customer              -- 客户基本信息
governance.t_health_assessment     -- 健康评估数据
governance.b_ai_user_memory        -- 用户全局记忆
```

#### 不依赖
- ❌ order-service
- ❌ payment-service
- ❌ inventory-service
- ❌ user-service
- ❌ order_db、payment_db、inventory_db、user_db

---

### 3. vector-memory-service (端口 8102)

#### 依赖的基础设施
- ✅ **MySQL** (localhost:3306) - 数据库 `governance`
- ✅ **Milvus** (localhost:9091) - 向量数据库
- ✅ **RabbitMQ** (localhost:5672) - 接收记忆请求
- ✅ **Nacos** (localhost:8848) - 服务注册
- ✅ **通义千问/DeepSeek API** - 记忆提取和向量化

#### 使用的数据表
```sql
governance.b_ai_group_memory       -- 群组记忆
governance.b_ai_user_memory        -- 用户全局记忆
governance.b_ai_chat_history       -- 聊天历史
```

#### 不依赖
- ❌ order-service
- ❌ payment-service
- ❌ inventory-service
- ❌ user-service
- ❌ order_db、payment_db、inventory_db、user_db

---

## 依赖关系图

```
┌─────────────────────────────────────────────────────────────┐
│                      基础设施层                              │
├─────────────────────────────────────────────────────────────┤
│  MySQL (governance)  │  Redis  │  RabbitMQ  │  Nacos  │  Milvus │
└─────────────────────────────────────────────────────────────┘
                              ↑
                              │
        ┌─────────────────────┼─────────────────────┐
        │                     │                     │
┌───────▼────────┐   ┌────────▼────────┐   ┌───────▼────────┐
│ user-profile   │   │ vector-memory   │   │  ai-chat       │
│   -service     │   │   -service      │   │  -service      │
│  (端口 8101)   │   │  (端口 8102)    │   │ (端口 8103)    │
└────────────────┘   └─────────────────┘   └────────────────┘
        ↑                     ↑                     │
        │                     │                     │
        └─────────────────────┴─────────────────────┘
                    RabbitMQ RPC 通信

┌─────────────────────────────────────────────────────────────┐
│                   外部 AI 服务                               │
├─────────────────────────────────────────────────────────────┤
│  DeepSeek API  │  通义千问 API  │  Dify API (可选)          │
└─────────────────────────────────────────────────────────────┘
```

---

## 与订单/支付服务的关系

### 完全独立

AI 服务和订单/支付服务是**两个完全独立的子系统**：

| 子系统 | 服务列表 | 数据库 | 业务领域 |
|--------|---------|--------|---------|
| **订单/支付系统** | order-service<br>payment-service<br>inventory-service<br>user-service<br>outbox-consumer | order_db<br>payment_db<br>inventory_db<br>user_db | 电商交易 |
| **AI 记忆系统** | ai-chat-service<br>user-profile-service<br>vector-memory-service | governance<br>(独立表) | 健康咨询 |

### 共享的基础设施

两个子系统共享以下基础设施（但互不依赖）：

- **Nacos** - 服务注册中心
- **RabbitMQ** - 消息队列（使用不同的 exchange 和 queue）
- **MySQL** - 数据库服务器（使用不同的数据库/表）
- **Redis** - 缓存服务器（使用不同的 key 前缀）

---

## 启动顺序

### 最小化启动（仅 AI 服务）

```bash
# 1. 启动基础设施
docker compose up -d mysql redis rabbitmq nacos milvus

# 2. 初始化数据库
mysql -h localhost -P 3306 -u root -p < sql/init-all-ai-services.sql

# 3. 配置环境变量
export DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxxxxxxxxxx

# 4. 启动 AI 服务（任意顺序）
mvn -q -pl user-profile-service spring-boot:run &
mvn -q -pl vector-memory-service spring-boot:run &
mvn -q -pl ai-chat-service spring-boot:run &
```

**不需要启动**:
- ❌ order-service
- ❌ payment-service
- ❌ inventory-service
- ❌ user-service
- ❌ outbox-consumer
- ❌ gateway (可选，直接访问 8103 端口)

### 完整启动（AI + 订单/支付）

如果需要同时运行两个子系统：

```bash
# 1. 启动基础设施
docker compose up -d

# 2. 初始化所有数据库
mysql -h localhost -P 3306 -u root -p < sql/init-mysql.sql
mysql -h localhost -P 3306 -u root -p < sql/init-all-ai-services.sql

# 3. 启动所有服务
mvn -q -pl gateway spring-boot:run &
mvn -q -pl order-service spring-boot:run &
mvn -q -pl payment-service spring-boot:run &
mvn -q -pl inventory-service spring-boot:run &
mvn -q -pl user-service spring-boot:run &
mvn -q -pl outbox-consumer spring-boot:run &
mvn -q -pl user-profile-service spring-boot:run &
mvn -q -pl vector-memory-service spring-boot:run &
mvn -q -pl ai-chat-service spring-boot:run &
```

---

## 数据隔离

### 数据库隔离

| 数据库 | 用途 | 使用服务 |
|--------|------|---------|
| `order_db` | 订单数据 | order-service |
| `payment_db` | 支付数据 | payment-service |
| `inventory_db` | 库存数据 | inventory-service |
| `user_db` | 用户账号 | user-service |
| `governance` | AI 数据 | user-profile-service<br>vector-memory-service |
| `nacos` | Nacos 配置 | 所有服务 |
| `seata` | 分布式事务 | order/payment/inventory |

### RabbitMQ 隔离

| Exchange | Routing Key | 用途 | 使用服务 |
|----------|-------------|------|---------|
| `user.profile.exchange` | `user.profile.request`<br>`user.profile.response.*` | 用户画像 RPC | ai-chat-service<br>user-profile-service |
| `vector.memory.exchange` | `vector.memory.request`<br>`vector.memory.response.*` | 记忆检索 RPC | ai-chat-service<br>vector-memory-service |
| `memory.extraction.exchange` | `memory.extraction` | 记忆提取 | ai-chat-service<br>vector-memory-service |
| `payment.exchange` | `payment.confirmed` | 支付确认事件 | payment-service<br>order-service |

---

## 测试验证

### 测试 AI 服务（无需订单/支付服务）

```bash
# 1. 测试用户画像服务
./test-01-user-profile.sh

# 2. 测试向量记忆服务
./test-02-vector-memory.sh

# 3. 测试 AI 聊天服务（端到端）
./test-03-ai-chat.sh
```

所有测试都能通过，证明 AI 服务完全独立。

---

## 总结

✅ **AI 服务完全独立**
- 不依赖 order-service、payment-service 等业务服务
- 不使用 order_db、payment_db 等业务数据库
- 可以单独启动和测试

✅ **共享基础设施**
- 共享 MySQL、Redis、RabbitMQ、Nacos
- 但使用不同的数据库、队列、缓存 key

✅ **独立部署**
- AI 服务可以独立部署到不同的服务器
- 订单/支付服务可以独立部署到不同的服务器
- 两个子系统互不影响

✅ **灵活组合**
- 可以只运行 AI 服务（健康咨询场景）
- 可以只运行订单/支付服务（电商场景）
- 可以同时运行两个子系统（完整演示）
