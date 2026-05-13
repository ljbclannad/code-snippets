# AI 记忆功能设计文档 - 第三部分：核心组件设计（概览）

## 文档信息

- **创建日期**: 2026-05-13
- **项目**: governance-suite
- **功能**: AI 聊天用户记忆功能
- **文档版本**: 1.0
- **依赖文档**: 第一部分 - 整体架构，第二部分 - 数据库设计

## 一、组件总览

本部分将详细设计三个微服务的核心组件，分为以下子文档：

1. **03-1: user-profile-service 组件设计**
   - UserProfileService
   - UserProfileMessageListener
   - 数据模型和 DTO
   - 配置类

2. **03-2: vector-memory-service 组件设计（上）**
   - EmbeddingService（Spring AI）
   - VectorStoreService（Spring AI）
   - MilvusVectorStoreConfig

3. **03-3: vector-memory-service 组件设计（下）**
   - MemoryRetrievalService
   - MemoryExtractionService
   - DeepSeekService
   - VectorMemoryMessageListener

4. **03-4: ai-chat-service 组件设计**
   - AiChatController
   - AiChatService
   - DifyService
   - RabbitMQRequestService

## 二、技术栈总结

### 2.1 Spring AI 集成

所有服务都基于 Spring AI 框架：

| 组件 | Spring AI 接口 | 实现 |
|------|---------------|------|
| Embedding 生成 | EmbeddingModel | 阿里云通义千问 |
| 向量存储 | VectorStore | Milvus |
| 信息提取 | ChatModel | DeepSeek (OpenAI 兼容) |

### 2.2 消息队列模式

使用 RabbitMQ 实现请求-响应模式：

```
请求方 → 发送请求消息（包含 correlationId）
       ↓
    请求队列
       ↓
响应方 → 消费请求，处理业务逻辑
       ↓
    响应队列（根据 correlationId 路由）
       ↓
请求方 ← 接收响应消息
```

### 2.3 缓存策略

使用 Spring Cache + Redis：

```java
@Cacheable(value = "user:profile", key = "#customerId", ttl = 300)
public UserProfileDTO buildUserProfile(String customerId) {
    // 实现
}
```

## 三、组件依赖关系

```
ai-chat-service
    ├── RabbitMQRequestService (发送 MQ 请求)
    ├── DifyService (调用 Dify API)
    └── AiChatService (协调流程)

user-profile-service
    ├── UserProfileService (构建画像)
    └── UserProfileMessageListener (监听 MQ)

vector-memory-service
    ├── EmbeddingService (Spring AI)
    ├── VectorStoreService (Spring AI)
    ├── MemoryRetrievalService (检索)
    ├── MemoryExtractionService (提取)
    └── VectorMemoryMessageListener (监听 MQ)
```

## 四、数据流转

### 4.1 用户画像获取流程

```
ai-chat-service
    → 发送 UserProfileRequestMessage
    → user.profile.request 队列

user-profile-service
    → 监听 user.profile.request
    → 查询 MySQL (Customer + 健康评估 + 记忆)
    → 构建 UserProfileDTO
    → 发送 UserProfileResponseMessage
    → user.profile.response.{correlationId} 队列

ai-chat-service
    ← 接收 UserProfileResponseMessage
```

### 4.2 群组记忆检索流程

```
ai-chat-service
    → 发送 MemoryRequestMessage
    → vector.memory.request 队列

vector-memory-service
    → 监听 vector.memory.request
    → 使用 EmbeddingModel 生成查询向量
    → 使用 VectorStore 检索 Milvus
    → 从 MySQL 补充完整信息
    → 发送 MemoryResponseMessage
    → vector.memory.response.{correlationId} 队列

ai-chat-service
    ← 接收 MemoryResponseMessage
```

### 4.3 记忆提取流程

```
ai-chat-service
    → 发送 ExtractionRequestMessage
    → memory.extraction.request 队列

vector-memory-service
    → 监听 memory.extraction.request (异步)
    → 使用 ChatModel 调用 DeepSeek
    → 解析 JSON 提取结果
    → 去重检查（向量相似度）
    → 保存到 MySQL
    → 使用 VectorStore 保存到 Milvus
```

## 五、错误处理策略

### 5.1 超时处理

| 操作 | 超时时间 | 降级策略 |
|------|---------|---------|
| 用户画像请求 | 5秒 | 返回空画像 |
| 向量检索请求 | 5秒 | 返回空记忆列表 |
| Dify API 调用 | 30秒 | 返回错误给用户 |
| 记忆提取 | 无限制 | 异步处理，失败记录日志 |

### 5.2 重试机制

```java
@Retryable(
    value = {MilvusException.class},
    maxAttempts = 3,
    backoff = @Backoff(delay = 1000)
)
public void saveToMilvus(Document document) {
    vectorStore.add(List.of(document));
}
```

### 5.3 熔断降级

使用 Sentinel 进行熔断保护：

```java
@SentinelResource(
    value = "buildUserProfile",
    fallback = "buildUserProfileFallback"
)
public UserProfileDTO buildUserProfile(String customerId) {
    // 实现
}

public UserProfileDTO buildUserProfileFallback(String customerId, Throwable ex) {
    log.error("构建用户画像失败，返回空画像", ex);
    return UserProfileDTO.empty(customerId);
}
```

## 六、日志和监控

### 6.1 关键日志点

每个服务都需要记录以下日志：

```java
// 请求开始
log.info("[{}] 开始处理请求: customerId={}, groupId={}", traceId, customerId, groupId);

// 关键步骤
log.info("[{}] 用户画像构建完成: customerId={}, cost={}ms", traceId, customerId, cost);
log.info("[{}] 向量检索完成: topK={}, cost={}ms", traceId, results.size(), cost);

// 异常情况
log.error("[{}] 向量检索失败: customerId={}, error={}", traceId, customerId, ex.getMessage());

// 请求结束
log.info("[{}] 请求处理完成: totalCost={}ms", traceId, totalCost);
```

### 6.2 监控指标

使用 Micrometer 记录指标：

```java
@Timed(value = "user.profile.build", description = "用户画像构建耗时")
public UserProfileDTO buildUserProfile(String customerId) {
    // 实现
}

@Counted(value = "memory.extraction.success", description = "记忆提取成功次数")
public void extractMemories(...) {
    // 实现
}
```

## 七、下一步

接下来将在以下子文档中详细设计每个服务的组件实现：

- **03-1-user-profile-service.md**
- **03-2-vector-memory-service-part1.md**
- **03-3-vector-memory-service-part2.md**
- **03-4-ai-chat-service.md**
