# 架构设计文档

## 📐 系统概述

Governance Suite 是一个**双轨学习项目**，包含两个独立但共享基础设施的系统：

1. **分布式系统治理平台** - 电商订单场景，涵盖分布式事务、最终一致性、秒杀等核心模式
2. **AI 记忆系统** - 智能对话场景，涵盖用户画像、向量检索、上下文管理等 AI 应用架构

## 🏗️ 整体架构

### 系统架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                         客户端层                                  │
│                    (Postman / 前端应用)                          │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│                      API Gateway (8080)                          │
│              路由 │ Token 校验 │ 限流 │ 负载均衡                  │
└─────────────┬───────────────────────────────┬───────────────────┘
              │                               │
    ┌─────────┴─────────┐          ┌─────────┴──────────┐
    │  分布式系统服务群   │          │   AI 记忆系统服务群  │
    └─────────┬─────────┘          └─────────┬──────────┘
              │                               │
    ┌─────────┴─────────────────┐  ┌─────────┴──────────────────┐
    │                           │  │                            │
    ▼                           ▼  ▼                            ▼
┌────────┐  ┌────────┐  ┌────────┐  ┌────────┐  ┌────────┐  ┌────────┐
│ User   │  │ Order  │  │Payment │  │AI Chat │  │Profile │  │Vector  │
│Service │  │Service │  │Service │  │Service │  │Service │  │Memory  │
│ 8084   │  │ 8081   │  │ 8082   │  │ 8085   │  │ 8086   │  │ 8087   │
└───┬────┘  └───┬────┘  └───┬────┘  └───┬────┘  └───┬────┘  └───┬────┘
    │           │           │           │           │           │
    └───────────┴───────────┴───────────┴───────────┴───────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│                        基础设施层                                 │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐        │
│  │  Nacos   │  │  Seata   │  │RabbitMQ  │  │  Redis   │        │
│  │  8848    │  │  7091    │  │  5672    │  │  36379   │        │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘        │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐        │
│  │  MySQL   │  │Sentinel  │  │  Milvus  │  │  ES      │        │
│  │  33066   │  │  8858    │  │  19530   │  │  9200    │        │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘        │
└─────────────────────────────────────────────────────────────────┘
```

## 🎯 分布式系统架构

### 服务拓扑

```
                    ┌─────────────┐
                    │   Gateway   │
                    │   (8080)    │
                    └──────┬──────┘
                           │
          ┌────────────────┼────────────────┐
          │                │                │
          ▼                ▼                ▼
    ┌──────────┐    ┌──────────┐    ┌──────────┐
    │   User   │    │  Order   │    │ Payment  │
    │ Service  │    │ Service  │    │ Service  │
    │  (8084)  │    │  (8081)  │    │  (8082)  │
    └────┬─────┘    └────┬─────┘    └────┬─────┘
         │               │                │
         │      ┌────────┴────────┐       │
         │      │                 │       │
         │      ▼                 ▼       │
         │  ┌──────────┐    ┌──────────┐ │
         │  │Inventory │    │  Outbox  │ │
         │  │ Service  │    │ Consumer │ │
         │  │  (8083)  │    │  (8090)  │ │
         │  └──────────┘    └──────────┘ │
         │                                │
         └────────────┬───────────────────┘
                      │
              ┌───────┴────────┐
              │                │
              ▼                ▼
        ┌──────────┐    ┌──────────┐
        │  Nacos   │    │  Seata   │
        │ (服务发现)│    │(分布式事务)│
        └──────────┘    └──────────┘
```

### 核心流程

#### 1. 订单创建流程（Seata AT 模式）

```
用户请求
   │
   ▼
Gateway (Token 校验)
   │
   ▼
Order Service (TM - 事务管理器)
   │
   ├─────────────────────────────────┐
   │                                 │
   ▼                                 ▼
Inventory Service (RM)        Payment Service (RM)
   │                                 │
   ├─ 扣减库存 (乐观锁)              ├─ 创建支付准备记录
   ├─ 记录 undo_log                 ├─ 记录 undo_log
   │                                 │
   ▼                                 ▼
分支事务注册到 Seata TC          分支事务注册到 Seata TC
   │                                 │
   └─────────────┬───────────────────┘
                 │
                 ▼
         全局事务提交/回滚
                 │
                 ├─ 成功：删除 undo_log
                 └─ 失败：根据 undo_log 回滚
```

**关键技术点**：
- ✅ Seata AT 模式自动管理分支事务
- ✅ 乐观锁防止并发冲突
- ✅ undo_log 自动记录和回滚
- ✅ 全局事务 ID 跨服务传播

#### 2. 秒杀下单流程（Redis Lua）

```
用户请求
   │
   ▼
Gateway (限流)
   │
   ▼
Order Service
   │
   ├─ 1. Redis Lua 原子预扣
   │    ├─ 检查库存
   │    ├─ 检查一人一单
   │    ├─ 扣减库存
   │    └─ 记录购买者
   │
   ├─ 2. 发送到 Redis Stream
   │    └─ XADD order:stream:1001 *
   │
   └─ 3. 立即返回（异步处理）
        │
        ▼
   Consumer 监听 Stream
        │
        ├─ XREADGROUP 读取消息
        ├─ 落库（订单、库存、支付）
        └─ XACK 确认消费
```

**关键技术点**：
- ✅ Redis Lua 保证原子性
- ✅ 防止超卖和一人一单
- ✅ Redis Stream 异步落库
- ✅ Consumer Group 保证消息不丢失

#### 3. Outbox 最终一致性流程

```
支付回调
   │
   ▼
Order Service
   │
   ├─ 1. 更新订单状态
   ├─ 2. 写入 Outbox 表 (同一事务)
   │    └─ status = PENDING
   │
   └─ 3. 提交本地事务
        │
        ▼
Outbox Consumer (定时轮询)
        │
        ├─ 1. 查询 PENDING 消息
        ├─ 2. 发送到 RabbitMQ
        │    ├─ 成功：更新 status = SENT
        │    └─ 失败：保持 PENDING，下次重试
        │
        └─ 3. 重试策略
             ├─ 最大重试 3 次
             ├─ 指数退避
             └─ 超时标记 FAILED
```

**关键技术点**：
- ✅ 本地事务保证消息不丢失
- ✅ 定时轮询保证最终投递
- ✅ 幂等性保证重复投递无影响
- ✅ 状态机管理消息生命周期

## 🤖 AI 记忆系统架构

### 服务拓扑

```
                    ┌─────────────┐
                    │  AI Chat    │
                    │  Service    │
                    │   (8085)    │
                    └──────┬──────┘
                           │
          ┌────────────────┼────────────────┐
          │                │                │
          ▼                ▼                ▼
    ┌──────────┐    ┌──────────┐    ┌──────────┐
    │  User    │    │  Vector  │    │ DeepSeek │
    │ Profile  │    │  Memory  │    │   API    │
    │ Service  │    │ Service  │    │ (外部)   │
    │  (8086)  │    │  (8087)  │    └──────────┘
    └────┬─────┘    └────┬─────┘
         │               │
         ▼               ▼
    ┌──────────┐    ┌──────────┐
    │  MySQL   │    │  Milvus  │
    │(用户画像) │    │(向量存储) │
    └──────────┘    └──────────┘
```

### 核心流程

#### 1. AI 对话流程

```
用户消息
   │
   ▼
AI Chat Service
   │
   ├─ 1. 检索用户画像
   │    └─ GET /api/profile/{customerId}
   │
   ├─ 2. 检索向量记忆
   │    └─ POST /api/memory/search
   │         ├─ 文本向量化
   │         └─ Milvus 相似度检索
   │
   ├─ 3. 构建上下文
   │    ├─ 系统提示词
   │    ├─ 用户画像
   │    ├─ 相关记忆
   │    └─ 历史对话
   │
   ├─ 4. Token Budget 管理
   │    ├─ 计算当前 Token 使用
   │    ├─ 预留响应空间
   │    └─ 压缩历史消息（如需要）
   │
   ├─ 5. 调用 DeepSeek API
   │    └─ POST https://api.deepseek.com/chat/completions
   │
   ├─ 6. 保存对话历史
   │    └─ INSERT INTO conversation_history
   │
   └─ 7. 返回响应
```

**关键技术点**：
- ✅ 自动检索用户画像和记忆
- ✅ Token Budget 动态管理
- ✅ 上下文压缩策略
- ✅ 会话状态管理

#### 2. 向量记忆检索流程

```
检索请求
   │
   ▼
Vector Memory Service
   │
   ├─ 1. 文本向量化
   │    └─ 调用 Embedding API
   │         └─ 返回 768 维向量
   │
   ├─ 2. Milvus 检索
   │    ├─ 过滤条件：customerId + groupId
   │    ├─ 相似度计算：余弦相似度
   │    └─ TopK 排序
   │
   ├─ 3. 结果增强
   │    ├─ 加载元数据
   │    ├─ 计算相关性分数
   │    └─ 按重要性排序
   │
   └─ 4. 返回结果
        └─ List<Memory>
```

**关键技术点**：
- ✅ 向量化模型集成
- ✅ Milvus 高性能检索
- ✅ 多维度过滤
- ✅ 相关性排序

#### 3. Token Budget 管理流程

```
对话请求
   │
   ▼
ContextBudgetManagerV2
   │
   ├─ 1. 计算预算分配
   │    ├─ 总预算：128K tokens
   │    ├─ 系统提示：2K (固定)
   │    ├─ 用户画像：1K (固定)
   │    ├─ 向量记忆：5K (动态)
   │    ├─ 历史对话：100K (动态)
   │    └─ 响应预留：20K (固定)
   │
   ├─ 2. 检查是否超预算
   │    └─ 当前使用 > 可用预算？
   │
   ├─ 3. 压缩策略（如超预算）
   │    ├─ 优先级 1：压缩历史对话
   │    │   ├─ 保留最近 N 条
   │    │   └─ 摘要旧消息
   │    ├─ 优先级 2：减少记忆数量
   │    │   └─ 只保留高相关性记忆
   │    └─ 优先级 3：简化用户画像
   │         └─ 只保留核心字段
   │
   └─ 4. 返回优化后的上下文
```

**关键技术点**：
- ✅ 动态预算分配
- ✅ 多级压缩策略
- ✅ 优先级管理
- ✅ Token 精确计算

## 📊 数据库设计

### 分布式系统 ER 图

```
┌─────────────────┐
│     users       │
├─────────────────┤
│ user_id (PK)    │
│ username        │
│ password_hash   │
│ display_name    │
│ created_at      │
└────────┬────────┘
         │ 1
         │
         │ N
┌────────┴────────┐
│     orders      │
├─────────────────┤
│ order_no (PK)   │
│ user_id (FK)    │
│ product_id      │
│ quantity        │
│ amount_cents    │
│ status          │
│ created_at      │
└────────┬────────┘
         │ 1
         │
         ├─────────────────┐
         │ 1               │ 1
         │                 │
         │ 1               │ 1
┌────────┴────────┐ ┌─────┴──────────┐
│   inventory     │ │   payment_0    │
├─────────────────┤ │   payment_1    │
│ product_id (PK) │ ├────────────────┤
│ stock           │ │ payment_id (PK)│
│ version         │ │ order_no (FK)  │
│ updated_at      │ │ amount_cents   │
└─────────────────┘ │ status         │
                    │ created_at     │
                    └────────┬───────┘
                             │ 1
                             │
                             │ 1
                    ┌────────┴───────┐
                    │  outbox_event  │
                    ├────────────────┤
                    │ id (PK)        │
                    │ order_no       │
                    │ event_type     │
                    │ payload        │
                    │ status         │
                    │ retry_count    │
                    │ created_at     │
                    └────────────────┘
```

### AI 记忆系统 ER 图

```
┌─────────────────────┐
│   user_profile      │
├─────────────────────┤
│ id (PK)             │
│ customer_id (UK)    │
│ group_id            │
│ name                │
│ preferences (JSON)  │
│ metadata (JSON)     │
│ created_at          │
└──────────┬──────────┘
           │ 1
           │
           │ N
┌──────────┴──────────┐
│ conversation_history│
├─────────────────────┤
│ id (PK)             │
│ customer_id (FK)    │
│ conversation_id     │
│ role                │
│ content             │
│ token_count         │
│ created_at          │
└─────────────────────┘

┌─────────────────────┐
│   vector_memory     │
├─────────────────────┤
│ id (PK)             │
│ customer_id         │
│ group_id            │
│ content             │
│ embedding (vector)  │
│ memory_type         │
│ importance          │
│ created_at          │
└─────────────────────┘
         │
         │ 存储在 Milvus
         ▼
┌─────────────────────┐
│  Milvus Collection  │
├─────────────────────┤
│ id                  │
│ customer_id         │
│ group_id            │
│ embedding (768维)   │
│ metadata            │
└─────────────────────┘
```

### 关键表设计

#### orders 表
```sql
CREATE TABLE orders (
    order_no VARCHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    product_id VARCHAR(64) NOT NULL,
    quantity INT NOT NULL,
    amount_cents BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,  -- PENDING, PAID, CANCELLED
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id),
    INDEX idx_status (status)
);
```

#### inventory 表（乐观锁）
```sql
CREATE TABLE inventory (
    product_id VARCHAR(64) PRIMARY KEY,
    stock INT NOT NULL,
    version INT NOT NULL DEFAULT 0,  -- 乐观锁版本号
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 乐观锁更新
UPDATE inventory 
SET stock = stock - ?, version = version + 1 
WHERE product_id = ? AND version = ? AND stock >= ?;
```

#### payment 表（分表）
```sql
-- 按 order_no 哈希分表
CREATE TABLE payment_0 (
    payment_id BIGINT PRIMARY KEY,
    order_no VARCHAR(64) NOT NULL,
    amount_cents BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,  -- PREPARED, CONFIRMED, CANCELLED
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_order_no (order_no)
);

CREATE TABLE payment_1 (
    -- 同上
);
```

#### outbox_event 表
```sql
CREATE TABLE outbox_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_no VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,  -- ORDER_PAID
    payload TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,  -- PENDING, SENT, FAILED
    retry_count INT DEFAULT 0,
    next_retry_at TIMESTAMP NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_status_retry (status, next_retry_at)
);
```

## 🔧 技术选型

### 分布式系统技术栈

| 技术 | 版本 | 用途 | 选型理由 |
|------|------|------|----------|
| Spring Boot | 3.2.5 | 应用框架 | 生态成熟，开发效率高 |
| Spring Cloud | 2023.0.1 | 微服务框架 | 与 Spring Boot 无缝集成 |
| Nacos | 2.3.2 | 服务发现/配置中心 | 阿里开源，功能完善 |
| Seata | 2.0.0 | 分布式事务 | AT 模式简单易用 |
| RabbitMQ | 3.13 | 消息队列 | 可靠性高，支持多种模式 |
| Redis | 7.2 | 缓存/分布式锁 | 性能优异，Lua 支持好 |
| MySQL | 8.0 | 关系数据库 | 成熟稳定，事务支持完善 |
| ShardingSphere | 5.4.1 | 分库分表 | 透明化分片，易于集成 |
| Sentinel | 1.8.7 | 流量控制 | 实时监控，规则灵活 |

### AI 记忆系统技术栈

| 技术 | 版本 | 用途 | 选型理由 |
|------|------|------|----------|
| Spring AI | 1.0.0-M1 | AI 集成框架 | Spring 官方，API 统一 |
| DeepSeek | V3 | 大语言模型 | 性价比高，128K 上下文 |
| Milvus | 2.3 | 向量数据库 | 高性能，支持大规模检索 |
| Elasticsearch | 8.12 | 全文检索 | 搜索能力强，生态完善 |
| Micrometer | 1.12.5 | 监控指标 | Prometheus 集成简单 |

## 📈 性能指标

### 分布式系统

| 指标 | 目标值 | 说明 |
|------|--------|------|
| 订单创建 RT | < 500ms | Seata 分布式事务 |
| 秒杀下单 RT | < 50ms | Redis Lua 原子操作 |
| Outbox 投递延迟 | < 30s | 定时轮询间隔 |
| 并发支持 | 1000 QPS | 单机性能 |

### AI 记忆系统

| 指标 | 目标值 | 说明 |
|------|--------|------|
| 对话响应 RT | < 3s | 包含检索和 LLM 调用 |
| 向量检索 RT | < 100ms | Milvus TopK=5 |
| Token 使用率 | < 80% | 128K 上下文窗口 |
| 上下文压缩率 | > 50% | 历史消息压缩 |

## 🔐 安全设计

### 认证与授权

```
用户请求
   │
   ▼
Gateway
   │
   ├─ 1. 提取 Token
   │    └─ Authorization: Bearer <token>
   │
   ├─ 2. Token 校验
   │    ├─ JWT 签名验证
   │    ├─ 过期时间检查
   │    └─ 用户信息提取
   │
   ├─ 3. 注入用户上下文
   │    └─ X-User-Id: {userId}
   │
   └─ 4. 转发到下游服务
```

### 数据安全

- ✅ 密码使用 BCrypt 加密存储
- ✅ Token 使用 HMAC-SHA256 签名
- ✅ 敏感数据传输使用 HTTPS
- ✅ SQL 注入防护（PreparedStatement）
- ✅ XSS 防护（输入验证）

## 📊 监控与可观测性

### 监控指标

```
应用层指标
├─ HTTP 请求
│  ├─ 请求总数
│  ├─ 响应时间（P50/P95/P99）
│  └─ 错误率
├─ 业务指标
│  ├─ 订单创建数
│  ├─ 支付成功率
│  └─ AI 对话次数
└─ 系统指标
   ├─ JVM 内存使用
   ├─ GC 次数和耗时
   └─ 线程池状态

中间件指标
├─ MySQL
│  ├─ 连接数
│  ├─ 慢查询
│  └─ 事务耗时
├─ Redis
│  ├─ 命中率
│  ├─ 内存使用
│  └─ 命令耗时
└─ RabbitMQ
   ├─ 队列深度
   ├─ 消费速率
   └─ 消息堆积
```

### 日志规范

```java
// 结构化日志
log.info("Order created, orderNo={}, userId={}, amount={}, traceId={}", 
    orderNo, userId, amount, traceId);

// 错误日志
log.error("Failed to create order, userId={}, error={}, traceId={}", 
    userId, e.getMessage(), traceId, e);
```

### 链路追踪

```
TraceId: abc123
├─ Gateway (10ms)
├─ Order Service (200ms)
│  ├─ Inventory Service (50ms)
│  └─ Payment Service (80ms)
└─ Total: 210ms
```

## 🚀 部署架构

### 开发环境

```
本地开发
├─ Docker Compose
│  ├─ MySQL
│  ├─ Redis
│  ├─ RabbitMQ
│  ├─ Nacos
│  └─ Seata
└─ IDEA 启动服务
   ├─ Gateway
   ├─ User Service
   ├─ Order Service
   └─ ...
```

### 生产环境（建议）

```
Kubernetes 集群
├─ Namespace: governance-suite
├─ Deployment
│  ├─ gateway (3 replicas)
│  ├─ user-service (2 replicas)
│  ├─ order-service (3 replicas)
│  └─ ...
├─ Service (ClusterIP)
├─ Ingress (域名路由)
└─ ConfigMap (配置管理)

中间件
├─ MySQL (主从复制)
├─ Redis (哨兵模式)
├─ RabbitMQ (集群模式)
└─ Nacos (集群模式)
```

## 📚 相关文档

- [API 文档](../api/README.md)
- [部署指南](../deployment/production-deployment-guide.md)
- [时序图](./sequence-diagrams.md)
- [架构决策记录](./adr/)

## 🔄 演进计划

### 短期（1-3 个月）

- [ ] 添加 Grafana Dashboard
- [ ] 完善单元测试覆盖率
- [ ] 添加压力测试报告
- [ ] 优化 Token Budget 算法

### 中期（3-6 个月）

- [ ] 支持多租户隔离
- [ ] 添加 API 限流策略
- [ ] 实现配置热更新
- [ ] 优化向量检索性能

### 长期（6-12 个月）

- [ ] 支持多模型切换
- [ ] 实现智能路由
- [ ] 添加 A/B 测试框架
- [ ] 支持流式响应
