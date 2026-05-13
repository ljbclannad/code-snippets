# AI 记忆功能设计文档 - 第五部分：数据流程和接口设计

## 文档信息

- **创建日期**: 2026-05-13
- **项目**: governance-suite
- **功能**: AI 聊天用户记忆功能

## 一、完整数据流程

### 1.1 用户发起聊天的完整流程

```
┌─────────────────────────────────────────────────────────────┐
│ 步骤 1: 用户发起聊天请求                                      │
└────────────────────┬────────────────────────────────────────┘
                     │ POST /api/ai/chat/send
                     ↓
┌─────────────────────────────────────────────────────────────┐
│ ai-chat-service (8103)                                       │
│ - AiChatController 接收请求                                  │
│ - 生成 correlationId 和 traceId                              │
│ - 调用 AiChatService.chat()                                 │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ↓
┌─────────────────────────────────────────────────────────────┐
│ 步骤 2: 请求用户画像                                          │
│ - 发送 UserProfileRequestMessage                             │
│ - 队列: user.profile.request                                │
│ - 等待响应（5秒超时）                                         │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ↓
┌─────────────────────────────────────────────────────────────┐
│ user-profile-service (8101)                                  │
│ - UserProfileMessageListener 监听队列                        │
│ - UserProfileService.buildUserProfile()                     │
│   - 查询 Customer 表                                         │
│   - 查询 HealthAssessment 表                                 │
│   - 查询 b_ai_user_memory 表                                 │
│   - 聚合为 UserProfileDTO                                    │
│ - 发送 UserProfileResponseMessage                            │
│ - 队列: user.profile.response.{correlationId}               │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ↓
┌─────────────────────────────────────────────────────────────┐
│ 步骤 3: 请求群组记忆                                          │
│ - 发送 MemoryRequestMessage                                  │
│ - 队列: vector.memory.request                               │
│ - 等待响应（5秒超时）                                         │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ↓
┌─────────────────────────────────────────────────────────────┐
│ vector-memory-service (8102)                                 │
│ - VectorMemoryMessageListener 监听队列                       │
│ - MemoryRetrievalService.retrieveGroupMemories()            │
│   - EmbeddingService.generateEmbedding() 生成查询向量        │
│   - VectorStoreService.searchGroupMemories() 检索 Milvus    │
│   - 从 MySQL 补充完整信息                                     │
│   - 返回 Top-5 相关记忆                                       │
│ - 发送 MemoryResponseMessage                                 │
│ - 队列: vector.memory.response.{correlationId}              │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ↓
┌─────────────────────────────────────────────────────────────┐
│ 步骤 4: 调用 Dify API                                        │
│ - DifyService.chat()                                        │
│ - 构建请求：                                                  │
│   - query: 用户消息                                          │
│   - inputs.user_profile: 用户画像 JSON                       │
│   - inputs.group_context: 群组记忆 JSON                      │
│   - inputs.role: 角色类型                                    │
│   - conversation_id: 会话ID                                  │
│ - 发送 HTTP POST 到 Dify                                     │
│ - 等待响应（30秒超时）                                        │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ↓
┌─────────────────────────────────────────────────────────────┐
│ 步骤 5: 返回结果给用户                                        │
│ - 构建 ChatResponseDTO                                       │
│ - 保存聊天历史到 b_ai_chat_history                           │
│ - 返回 ApiResponse<ChatResponseDTO>                         │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ↓
┌─────────────────────────────────────────────────────────────┐
│ 步骤 6: 异步触发记忆提取                                      │
│ - 发送 ExtractionRequestMessage                              │
│ - 队列: memory.extraction.request                           │
│ - 不等待响应（fire-and-forget）                              │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ↓
┌─────────────────────────────────────────────────────────────┐
│ vector-memory-service (8102) - 异步处理                      │
│ - VectorMemoryMessageListener 监听队列                       │
│ - MemoryExtractionService.extractAndSaveMemories()          │
│   - DeepSeekService.extractMemories() 调用 DeepSeek API     │
│   - 解析 JSON 提取结果                                        │
│   - 对每条记忆：                                              │
│     - 检查置信度（>= 0.7）                                    │
│     - 去重检查（相似度 < 0.95）                               │
│     - 保存到 MySQL (b_ai_group_memory)                       │
│     - 保存到 Milvus (向量)                                    │
└─────────────────────────────────────────────────────────────┘
```

---

## 二、REST API 接口详细设计

### 2.1 ai-chat-service 接口

#### POST /api/ai/chat/send - 发送聊天消息

**请求**

```http
POST /api/ai/chat/send HTTP/1.1
Host: localhost:8103
Content-Type: application/json

{
  "customerId": "user123",
  "groupId": "group456",
  "message": "我最近胃部不太舒服，有什么建议吗？",
  "conversationId": "conv789",
  "role": "doctor"
}
```

**请求参数说明**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| customerId | String | 是 | 用户ID |
| groupId | String | 是 | 群组ID |
| message | String | 是 | 用户消息内容 |
| conversationId | String | 否 | Dify 会话ID，首次为空 |
| role | String | 否 | 角色类型：doctor/nutritionist/customer |

**响应**

```json
{
  "success": true,
  "code": 200,
  "message": "success",
  "traceId": "trace-abc123",
  "data": {
    "conversationId": "conv789",
    "messageId": "msg-xyz456",
    "answer": "根据您的描述，胃部不适可能与饮食习惯有关...",
    "createdAt": 1715600000000
  }
}
```

**响应字段说明**

| 字段 | 类型 | 说明 |
|------|------|------|
| success | Boolean | 请求是否成功 |
| code | Integer | 状态码 |
| message | String | 状态消息 |
| traceId | String | 链路追踪ID |
| data.conversationId | String | Dify 会话ID，用于后续对话 |
| data.messageId | String | 消息ID |
| data.answer | String | AI 回复内容 |
| data.createdAt | Long | 创建时间戳（毫秒） |

**错误响应**

```json
{
  "success": false,
  "code": 500,
  "message": "聊天请求处理失败: Dify API 调用超时",
  "traceId": "trace-abc123",
  "data": null
}
```

---

#### GET /api/ai/chat/history - 查询聊天历史

**请求**

```http
GET /api/ai/chat/history?customerId=user123&groupId=group456&page=1&size=20 HTTP/1.1
Host: localhost:8103
```

**请求参数说明**

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| customerId | String | 是 | - | 用户ID |
| groupId | String | 是 | - | 群组ID |
| page | Integer | 否 | 1 | 页码 |
| size | Integer | 否 | 20 | 每页数量 |

**响应**

```json
{
  "success": true,
  "code": 200,
  "message": "success",
  "traceId": "trace-def789",
  "data": [
    {
      "id": 1,
      "userMessage": "我最近胃部不太舒服",
      "aiResponse": "根据您的描述...",
      "role": "doctor",
      "createTime": "2026-05-13 10:30:00"
    },
    {
      "id": 2,
      "userMessage": "需要注意什么饮食禁忌吗？",
      "aiResponse": "建议您避免...",
      "role": "doctor",
      "createTime": "2026-05-13 10:32:00"
    }
  ]
}
```

---

### 2.2 user-profile-service 接口（可选）

#### GET /api/profile/{customerId} - 获取用户画像

**请求**

```http
GET /api/profile/user123 HTTP/1.1
Host: localhost:8101
```

**响应**

```json
{
  "success": true,
  "code": 200,
  "data": {
    "basicInfo": {
      "customerId": "user123",
      "name": "张三",
      "age": 35,
      "gender": "男",
      "phone": "13800138000",
      "email": "zhangsan@example.com"
    },
    "healthProfile": {
      "bmi": 24.5,
      "bloodPressure": "120/80",
      "bloodSugar": 5.6,
      "latestAssessmentDate": "2026-05-01",
      "healthGoals": ["减重", "改善睡眠"]
    },
    "behaviorPreferences": {
      "dietPreference": "地中海饮食",
      "exerciseFrequency": "每周3次",
      "checkInStreak": 15
    },
    "globalMemories": [
      {
        "type": "ALLERGY",
        "key": "青霉素",
        "content": "对青霉素过敏",
        "verified": true
      },
      {
        "type": "CHRONIC_DISEASE",
        "key": "高血压",
        "content": "高血压患者，正在服用降压药",
        "verified": true
      }
    ]
  }
}
```

---

### 2.3 vector-memory-service 接口（可选）

#### GET /api/memory/group - 查询群组记忆

**请求**

```http
GET /api/memory/group?groupId=group456&customerId=user123&page=1&size=20 HTTP/1.1
Host: localhost:8102
```

**响应**

```json
{
  "success": true,
  "code": 200,
  "data": {
    "total": 15,
    "records": [
      {
        "id": 1,
        "memoryType": "SYMPTOM",
        "memoryKey": "胃部不适",
        "memoryContent": "用户提到最近一周胃部不适，饭后有胀气感",
        "confidenceScore": 0.95,
        "extractedAt": 1715500000000,
        "createTime": "2026-05-10 14:20:00"
      }
    ]
  }
}
```

---

#### DELETE /api/memory/{id} - 删除记忆

**请求**

```http
DELETE /api/memory/1 HTTP/1.1
Host: localhost:8102
```

**响应**

```json
{
  "success": true,
  "code": 200,
  "message": "记忆已删除"
}
```

---

## 三、消息队列接口设计

### 3.1 用户画像请求-响应

**请求消息：user.profile.request**

```json
{
  "correlationId": "uuid-123",
  "customerId": "user123",
  "timestamp": 1715600000000
}
```

**响应消息：user.profile.response.{correlationId}**

```json
{
  "correlationId": "uuid-123",
  "success": true,
  "errorMessage": null,
  "data": {
    "basicInfo": { ... },
    "healthProfile": { ... },
    "behaviorPreferences": { ... },
    "globalMemories": [ ... ]
  },
  "timestamp": 1715600001000
}
```

---

### 3.2 群组记忆请求-响应

**请求消息：vector.memory.request**

```json
{
  "correlationId": "uuid-456",
  "groupId": "group456",
  "customerId": "user123",
  "query": "我最近胃部不太舒服",
  "topK": 5,
  "timestamp": 1715600000000
}
```

**响应消息：vector.memory.response.{correlationId}**

```json
{
  "correlationId": "uuid-456",
  "success": true,
  "errorMessage": null,
  "data": [
    {
      "id": 1,
      "memoryType": "SYMPTOM",
      "memoryKey": "胃部不适",
      "memoryContent": "用户提到最近一周胃部不适，饭后有胀气感",
      "confidenceScore": 0.95,
      "extractedAt": 1715500000000,
      "createTime": "2026-05-10 14:20:00"
    }
  ],
  "timestamp": 1715600001000
}
```

---

### 3.3 记忆提取请求（异步）

**请求消息：memory.extraction.request**

```json
{
  "groupId": "group456",
  "customerId": "user123",
  "userMessage": "我最近胃部不太舒服，有什么建议吗？",
  "aiResponse": "根据您的描述，胃部不适可能与饮食习惯有关...",
  "timestamp": 1715600000000
}
```

**无响应消息**（异步处理，不返回响应）

---

## 四、时序图

### 4.1 完整聊天流程时序图

```
用户    ai-chat    user-profile    vector-memory    Dify API
 │         │             │                │             │
 │─POST───>│             │                │             │
 │         │             │                │             │
 │         │─MQ Request─>│                │             │
 │         │             │                │             │
 │         │             │─Query MySQL───>│             │
 │         │             │<───Result──────│             │
 │         │             │                │             │
 │         │<─MQ Response│                │             │
 │         │             │                │             │
 │         │─MQ Request──────────────────>│             │
 │         │             │                │             │
 │         │             │                │─Search──────>│
 │         │             │                │  Milvus     │
 │         │             │                │<─Results────│
 │         │             │                │             │
 │         │<─MQ Response────────────────│             │
 │         │             │                │             │
 │         │─HTTP POST──────────────────────────────────>│
 │         │             │                │             │
 │         │<─HTTP Response──────────────────────────────│
 │         │             │                │             │
 │<─Result─│             │                │             │
 │         │             │                │             │
 │         │─MQ Async────────────────────>│             │
 │         │  (extraction)                │             │
 │         │             │                │─DeepSeek───>│
 │         │             │                │  API        │
 │         │             │                │<─Extract────│
 │         │             │                │             │
 │         │             │                │─Save MySQL─>│
 │         │             │                │─Save Milvus>│
```

---

## 五、性能指标

### 5.1 响应时间目标

| 操作 | 目标时间 | 说明 |
|------|---------|------|
| 用户画像查询（缓存命中） | < 100ms | Redis 缓存 |
| 用户画像查询（缓存未命中） | < 500ms | MySQL 查询 |
| 向量检索 | < 200ms | Milvus 检索 |
| 完整聊天流程 | < 3s | 不含 Dify API 时间 |
| Dify API 调用 | < 30s | 外部依赖 |
| 记忆提取 | 异步 | 不影响主流程 |

### 5.2 并发能力目标

| 指标 | 目标值 |
|------|--------|
| 单服务 QPS | > 100 |
| 集群 QPS | > 500 |
| 消息队列吞吐量 | > 1000 msg/s |

---

## 六、错误码定义

| 错误码 | 说明 | 处理建议 |
|--------|------|---------|
| 200 | 成功 | - |
| 400 | 参数错误 | 检查请求参数 |
| 404 | 资源不存在 | 检查用户ID或群组ID |
| 500 | 服务内部错误 | 查看日志，联系管理员 |
| 503 | 服务不可用 | 稍后重试 |
| 504 | 请求超时 | 检查网络或增加超时时间 |

---

## 七、总结

本文档详细设计了 AI 记忆功能的数据流程和接口：

1. **完整数据流程**：从用户请求到记忆提取的完整流程
2. **REST API 接口**：详细的请求和响应格式
3. **消息队列接口**：MQ 消息格式定义
4. **时序图**：可视化的交互流程
5. **性能指标**：响应时间和并发能力目标
6. **错误码定义**：统一的错误处理

下一步将编写第六部分：部署和测试策略。
