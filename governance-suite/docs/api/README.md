# API 文档

## 📦 Postman Collection

### 导入方式

1. **直接导入文件**
   ```bash
   # 在 Postman 中点击 Import → 选择文件
   governance-suite.postman_collection.json
   ```

2. **配置环境变量**
   
   创建 Postman Environment，添加以下变量：
   
   | 变量名 | 默认值 | 说明 |
   |--------|--------|------|
   | `base_url` | `http://localhost:8080` | 网关地址 |
   | `ai_chat_url` | `http://localhost:8085` | AI 对话服务地址 |
   | `auth_token` | (自动设置) | 登录后自动保存 |
   | `user_id` | (自动设置) | 注册/登录后自动保存 |
   | `order_no` | (自动设置) | 创建订单后自动保存 |

### 📂 Collection 结构

```
Governance Suite API
├── 分布式系统 API
│   ├── 用户服务
│   │   ├── 注册用户
│   │   ├── 用户登录
│   │   └── 获取当前用户信息
│   ├── 订单服务
│   │   ├── 创建订单（Seata 分布式事务）
│   │   ├── 创建订单 v2（秒杀场景）
│   │   ├── 查询订单
│   │   └── 支付回调（Outbox 模式）
│   └── 支付服务
│       └── 确认支付
├── AI 记忆系统 API
│   ├── AI 对话服务
│   │   ├── 发起对话
│   │   ├── 查看会话上下文
│   │   └── 查看对话历史
│   ├── 用户画像服务
│   │   ├── 创建用户画像
│   │   ├── 查询用户画像
│   │   └── 更新用户画像
│   ├── 向量记忆服务
│   │   ├── 添加记忆
│   │   └── 检索记忆
│   └── 监控与健康检查
│       ├── AI Chat - 健康检查
│       └── AI Chat - Prometheus 指标
└── 测试场景
    └── 完整订单流程（5步）
```

## 🚀 快速开始

### 1. 测试分布式系统

按顺序执行以下请求：

1. **注册用户** → 自动保存 `user_id`
2. **用户登录** → 自动保存 `auth_token`
3. **创建订单** → 自动保存 `order_no`
4. **确认支付** → 使用保存的 `order_no`
5. **查询订单** → 验证订单状态

### 2. 测试 AI 记忆系统

#### 准备工作
```bash
# 1. 创建用户画像
POST http://localhost:8086/api/profile

# 2. 添加向量记忆
POST http://localhost:8087/api/memory

# 3. 发起对话（自动检索画像和记忆）
POST http://localhost:8085/api/chat
```

### 3. 使用测试场景

Collection 中包含 **"完整订单流程"** 测试场景，可以一键运行：

1. 在 Postman 中选择 `测试场景 → 完整订单流程`
2. 点击 `Run` 按钮
3. 自动执行 5 个步骤，验证完整流程

## 📝 API 详细说明

### 分布式系统 API

#### 用户服务 (User Service)

**注册用户**
```http
POST /api/user/register
Content-Type: application/json

{
  "username": "demo_user",
  "password": "123456",
  "displayName": "Demo User"
}
```

**用户登录**
```http
POST /api/user/login
Content-Type: application/json

{
  "username": "demo_user",
  "password": "123456"
}

Response:
{
  "success": true,
  "data": {
    "token": "eyJhbGc...",
    "user": {
      "userId": "123",
      "username": "demo_user"
    }
  }
}
```

#### 订单服务 (Order Service)

**创建订单（Seata AT 模式）**
```http
POST /api/order/order
Authorization: Bearer {{auth_token}}
Content-Type: application/json
Idempotency-Key: {{$randomUUID}}

{
  "productId": "p1",
  "quantity": 1,
  "amountCents": 100
}
```

**技术要点**：
- ✅ Seata AT 分布式事务
- ✅ 幂等性保证（Idempotency-Key）
- ✅ 库存扣减（乐观锁）
- ✅ 支付准备（分表）

**创建订单 v2（秒杀场景）**
```http
POST /api/order/order/v2
Authorization: Bearer {{auth_token}}
Content-Type: application/json
Idempotency-Key: {{$randomUUID}}

{
  "productId": "1001",
  "quantity": 1,
  "amountCents": 100
}
```

**技术要点**：
- ✅ Redis Lua 原子预扣
- ✅ Redis Stream 异步落库
- ✅ 防止超卖
- ✅ 一人一单

**准备秒杀库存**：
```bash
# 设置库存
redis-cli -p 36379 set seckill:stock:sku:1001 5

# 清空购买记录
redis-cli -p 36379 del seckill:buyers:sku:1001
```

#### 支付服务 (Payment Service)

**确认支付**
```http
POST /api/payment/confirm/{{order_no}}
Authorization: Bearer {{auth_token}}
```

### AI 记忆系统 API

#### AI 对话服务

**发起对话**
```http
POST /api/chat
Content-Type: application/json

{
  "customerId": "user123",
  "groupId": "group1",
  "conversationId": "conv-001",
  "message": "你好，我想了解一下产品信息"
}
```

**技术要点**：
- ✅ 自动检索用户画像
- ✅ 自动检索向量记忆
- ✅ Token Budget 管理
- ✅ 上下文压缩

**查看会话上下文**
```http
GET /api/chat/context/conv-001
```

返回 SessionContext：
- 意图识别
- 对话阶段
- 历史消息
- Token 使用情况

#### 用户画像服务

**创建用户画像**
```http
POST /api/profile
Content-Type: application/json

{
  "customerId": "user123",
  "groupId": "group1",
  "name": "张三",
  "preferences": {
    "language": "zh-CN",
    "interests": ["技术", "产品"]
  },
  "metadata": {
    "company": "某科技公司",
    "role": "产品经理"
  }
}
```

#### 向量记忆服务

**添加记忆**
```http
POST /api/memory
Content-Type: application/json

{
  "customerId": "user123",
  "groupId": "group1",
  "content": "用户对产品的定价策略非常关注",
  "memoryType": "PREFERENCE",
  "importance": 0.8
}
```

**检索记忆**
```http
POST /api/memory/search
Content-Type: application/json

{
  "customerId": "user123",
  "groupId": "group1",
  "query": "产品价格",
  "topK": 5
}
```

## 🔍 监控与健康检查

### 健康检查
```http
GET /actuator/health
```

### Prometheus 指标
```http
GET /actuator/prometheus
```

**关键指标**：
- `ai_chat_requests_total` - 对话请求总数
- `ai_chat_request_duration_seconds` - 请求耗时
- `ai_chat_token_usage_total` - Token 使用量
- `ai_chat_context_compression_total` - 上下文压缩次数

## 🧪 测试建议

### 1. 分布式事务测试

**正常流程**：
```bash
注册 → 登录 → 创建订单 → 确认支付 → 查询订单
```

**异常流程**（测试回滚）：
```bash
# 设置故障注入
export LAB_FAIL_INVENTORY=true

# 创建订单（库存扣减失败，事务回滚）
POST /api/order/order

# 验证：订单未创建，库存未扣减，支付未准备
```

### 2. 秒杀场景测试

**并发测试**：
```bash
# 使用 Postman Runner 或 JMeter
# 并发 100 个请求，库存 5 个
# 预期：5 个成功，95 个失败（库存不足）
```

### 3. Outbox 重试测试

```bash
# 1. 停止 RabbitMQ
docker stop rabbitmq

# 2. 触发支付回调
POST /api/order/order/payment/callback/{{order_no}}

# 3. 观察 Outbox 表（状态为 PENDING）
SELECT * FROM outbox_event WHERE order_no = '...';

# 4. 启动 RabbitMQ
docker start rabbitmq

# 5. 等待 Outbox Consumer 轮询投递（30秒）
# 6. 验证消息已投递（状态变为 SENT）
```

### 4. AI 对话测试

**完整流程**：
```bash
# 1. 创建用户画像
POST /api/profile

# 2. 添加向量记忆
POST /api/memory

# 3. 发起对话
POST /api/chat

# 4. 查看上下文
GET /api/chat/context/conv-001

# 5. 继续对话（验证上下文保持）
POST /api/chat
```

## 📊 响应格式

### 成功响应
```json
{
  "success": true,
  "data": { ... },
  "message": "操作成功",
  "timestamp": "2024-05-19T10:30:00"
}
```

### 错误响应
```json
{
  "success": false,
  "errorCode": "ORDER_NOT_FOUND",
  "message": "订单不存在",
  "timestamp": "2024-05-19T10:30:00",
  "traceId": "abc123"
}
```

## 🔐 认证说明

### Token 认证

大部分 API 需要 Token 认证：

```http
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

### 无需认证的 API

- `POST /api/user/register` - 注册
- `POST /api/user/login` - 登录
- AI 记忆系统所有 API（演示环境）

## 📚 相关文档

- [架构设计文档](../architecture/README.md)
- [部署指南](../deployment/production-deployment-guide.md)
- [项目 README](../../README.md)

## ❓ 常见问题

### Q1: Token 过期怎么办？
重新调用登录接口获取新 Token。

### Q2: 秒杀接口返回 "库存不足"？
检查 Redis 中的库存：
```bash
redis-cli -p 36379 get seckill:stock:sku:1001
```

### Q3: Outbox 消息未投递？
检查 Outbox Consumer 是否启动，查看日志：
```bash
tail -f outbox-consumer/logs/app.log
```

### Q4: AI 对话无响应？
检查 DeepSeek API Key 是否配置：
```bash
cat .env | grep DEEPSEEK_API_KEY
```
