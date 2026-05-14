# Dify 集成调用流程

## 概述

项目通过 `ai-chat-service` 集成 Dify API，实现带上下文的智能对话功能。核心特点是在调用 Dify 之前，先并行获取用户画像和历史记忆，将这些上下文信息注入到 Dify 请求中。

## 完整调用流程

```
用户请求
   ↓
AiChatController (POST /api/chat)
   ↓
AiChatService.handleChatRequest()
   ↓
   ├─→ [并行] UserProfileClient.requestUserProfile()
   │          ↓ (RabbitMQ RPC)
   │          user-profile-service
   │          ↓
   │          返回: UserProfileDTO (姓名、年龄、BMI、血压、全局记忆等)
   │
   └─→ [并行] VectorMemoryClient.requestGroupMemories()
              ↓ (RabbitMQ RPC)
              vector-memory-service
              ↓
              返回: List<MemoryItemDTO> (相关历史记忆，按相似度排序)
   ↓
等待两个并行请求完成
   ↓
DifyClient.chat(request, profile, memories)
   ↓
构建 DifyRequest:
   - query: 用户消息
   - user: customerId
   - conversationId: 会话ID（可选）
   - inputs: {
       user_profile: UserProfileDTO,
       memories: List<String>
     }
   - responseMode: false (阻塞模式)
   ↓
RestClient.post("/chat-messages")
   - Base URL: ${DIFY_API_BASE_URL}
   - Headers:
     * Authorization: Bearer ${DIFY_API_KEY}
     * Content-Type: application/json
   - Timeout: 30秒
   ↓
Dify API 处理
   ↓
返回 DifyResponse:
   - answer: AI 回复内容
   - conversationId: 会话ID
   - messageId: 消息ID
   - metadata: 使用统计
   ↓
[异步] VectorMemoryClient.triggerMemoryExtraction()
   ↓ (RabbitMQ 单向消息)
   vector-memory-service 提取新记忆
   ↓
返回 ChatResponse 给用户
```

## 核心组件

### 1. AiChatController
**路径**: `ai-chat-service/src/main/java/com/lx/governance/aichat/controller/AiChatController.java`

**端点**: `POST /api/chat`

**职责**: 
- 接收用户聊天请求
- 调用 AiChatService 处理
- 返回响应或错误信息

**请求示例**:
```json
{
  "customerId": "test-customer-001",
  "groupId": "health-group-001",
  "conversationId": "conv-12345",  // 可选，用于多轮对话
  "message": "我想了解如何改善血压"
}
```

### 2. AiChatService
**路径**: `ai-chat-service/src/main/java/com/lx/governance/aichat/service/AiChatService.java`

**职责**:
- 编排整个聊天流程
- 并行获取用户画像和记忆（使用 CompletableFuture）
- 调用 DifyClient
- 异步触发记忆提取

**关键代码**:
```java
// 并行获取上下文
CompletableFuture<UserProfileDTO> profileFuture = 
    CompletableFuture.supplyAsync(() -> 
        userProfileClient.requestUserProfile(customerId)
    );

CompletableFuture<List<MemoryItemDTO>> memoriesFuture = 
    CompletableFuture.supplyAsync(() -> 
        vectorMemoryClient.requestGroupMemories(groupId, customerId, message)
    );

// 等待完成
CompletableFuture.allOf(profileFuture, memoriesFuture).join();

// 调用 Dify
DifyResponse response = difyClient.chat(request, profile, memories);

// 异步提取记忆
CompletableFuture.runAsync(() -> 
    vectorMemoryClient.triggerMemoryExtraction(...)
);
```

### 3. DifyClient
**路径**: `ai-chat-service/src/main/java/com/lx/governance/aichat/client/DifyClient.java`

**职责**:
- 封装 Dify API 调用
- 构建请求参数（注入用户画像和记忆）
- 处理响应和异常

**关键方法**:
```java
public DifyResponse chat(ChatRequest request, 
                         UserProfileDTO userProfile, 
                         List<MemoryItemDTO> memories) {
    
    DifyRequest difyRequest = buildDifyRequest(request, userProfile, memories);
    
    return difyRestClient.post()
            .uri("/chat-messages")
            .body(difyRequest)
            .retrieve()
            .body(DifyResponse.class);
}
```

**构建请求逻辑**:
```java
private DifyRequest buildDifyRequest(...) {
    Map<String, Object> inputs = new HashMap<>();
    
    // 注入用户画像
    if (userProfile != null) {
        inputs.put("user_profile", userProfile);
    }
    
    // 注入记忆内容
    if (memories != null && !memories.isEmpty()) {
        List<String> memoryContents = memories.stream()
                .map(MemoryItemDTO::getContent)
                .collect(Collectors.toList());
        inputs.put("memories", memoryContents);
    }
    
    return DifyRequest.builder()
            .query(request.getMessage())
            .user(request.getCustomerId())
            .conversationId(request.getConversationId())
            .inputs(inputs)
            .responseMode(false)  // 阻塞模式
            .build();
}
```

### 4. RestClientConfig
**路径**: `ai-chat-service/src/main/java/com/lx/governance/aichat/config/RestClientConfig.java`

**职责**:
- 配置 RestClient Bean
- 设置 Dify API 认证和超时

**配置**:
```java
@Bean
public RestClient difyRestClient() {
    return RestClient.builder()
            .baseUrl(difyBaseUrl)  // https://api.dify.ai/v1
            .defaultHeader("Authorization", "Bearer " + difyApiKey)
            .defaultHeader("Content-Type", "application/json")
            .requestFactory(clientHttpRequestFactory())  // 30秒超时
            .build();
}
```

## 数据传输对象 (DTOs)

### DifyRequest
```java
{
    "query": "用户消息内容",
    "user": "test-customer-001",
    "conversationId": "conv-12345",  // 可选
    "inputs": {
        "user_profile": {
            "basicInfo": {
                "name": "张三",
                "age": 35,
                "gender": "MALE"
            },
            "healthProfile": {
                "bmi": 24.5,
                "bloodPressure": "140/90",
                "bloodSugar": 5.8
            },
            "globalMemories": [
                {
                    "type": "ALLERGY",
                    "key": "海鲜过敏",
                    "content": "对虾、蟹等甲壳类海鲜过敏"
                }
            ]
        },
        "memories": [
            "用户最近提到血压偏高，需要控制盐分摄入",
            "用户偏好清淡饮食，不吃辣"
        ]
    },
    "responseMode": false
}
```

### DifyResponse
```java
{
    "answer": "根据您的情况，建议...",
    "conversation_id": "conv-12345",
    "message_id": "msg-67890",
    "metadata": {
        "usage": "...",
        "retrieverResources": 3
    }
}
```

## 配置说明

### application.yml
```yaml
# Dify API 配置
dify:
  api:
    base-url: ${DIFY_API_BASE_URL:https://api.dify.ai/v1}
    key: ${DIFY_API_KEY:your-dify-api-key}
    timeout: 30000  # 30秒

# 服务超时配置
service:
  timeout:
    user-profile: 5000      # 用户画像服务超时 5秒
    vector-memory: 5000     # 向量记忆服务超时 5秒
    dify-api: 30000         # Dify API 超时 30秒
```

### 环境变量
```bash
# 必须配置
export DIFY_API_BASE_URL=https://api.dify.ai/v1
export DIFY_API_KEY=app-xxxxxxxxxxxxxx

# 可选配置
export DIFY_API_TIMEOUT=30000
```

## 关键特性

### 1. 并行上下文获取
使用 `CompletableFuture` 并行获取用户画像和记忆，减少总响应时间：
- 串行: 5s (画像) + 5s (记忆) + 30s (Dify) = 40s
- 并行: max(5s, 5s) + 30s = 35s

### 2. 上下文注入
将用户画像和历史记忆作为 `inputs` 参数传递给 Dify，让 AI 能够：
- 了解用户基本信息（年龄、性别、健康状况）
- 参考历史对话记忆
- 提供个性化建议

### 3. 异步记忆提取
对话完成后，异步触发记忆提取，不阻塞响应：
```java
CompletableFuture.runAsync(() -> {
    vectorMemoryClient.triggerMemoryExtraction(
        groupId, customerId, userMessage, aiResponse
    );
});
```

### 4. 多轮对话支持
通过 `conversationId` 支持多轮对话：
- 首次对话: conversationId 为空，Dify 返回新的 conversationId
- 后续对话: 传入之前的 conversationId，Dify 维护对话上下文

### 5. 超时和异常处理
- 连接超时: 30秒
- 读取超时: 30秒
- 异常捕获: 记录日志并返回友好错误信息

## 调用示例

### 使用 curl 测试
```bash
curl -X POST http://localhost:8103/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "test-customer-001",
    "groupId": "health-group-001",
    "message": "我想了解如何改善血压问题"
  }'
```

### 响应示例
```json
{
  "success": true,
  "message": "success",
  "data": {
    "answer": "根据您的健康档案，您的血压为140/90mmHg，属于1级高血压。结合您的全局记忆显示您对海鲜过敏，以及最近的对话记录，我建议：\n\n1. 饮食调整：\n   - 减少盐分摄入，每日控制在5克以内\n   - 多吃富含钾的食物（香蕉、土豆、菠菜）\n   - 避免海鲜类食物（考虑到您的过敏史）\n\n2. 生活方式：\n   - 每周至少150分钟中等强度运动\n   - 保持规律作息，充足睡眠\n   - 减轻压力，适当放松\n\n3. 监测：\n   - 每天早晚测量血压并记录\n   - 如持续偏高，建议就医咨询\n\n请问您对这些建议有什么疑问吗？",
    "conversationId": "conv-abc123def456"
  },
  "traceId": "trace-xyz789"
}
```

## 性能优化

1. **并行请求**: 用户画像和记忆检索并行执行
2. **异步记忆提取**: 不阻塞主流程
3. **连接池**: RestClient 使用连接池复用连接
4. **超时控制**: 防止长时间等待
5. **日志记录**: 记录每个阶段的耗时，便于性能分析

## 监控指标

建议监控以下指标：
- Dify API 调用成功率
- Dify API 响应时间（P50, P95, P99）
- 用户画像获取耗时
- 记忆检索耗时
- 端到端响应时间
- 并发请求数

## 故障处理

### Dify API 不可用
- 捕获异常并返回友好错误信息
- 记录详细日志便于排查
- 考虑实现降级策略（不带上下文直接返回通用回复）

### 上下文服务超时
- 设置合理的超时时间（5秒）
- 超时后继续调用 Dify（不带该部分上下文）
- 记录超时事件用于监控

### 记忆提取失败
- 异步执行，不影响主流程
- 记录错误日志
- 考虑重试机制

## 扩展建议

1. **流式响应**: 将 `responseMode` 改为 `true`，支持流式返回
2. **缓存**: 对频繁访问的用户画像进行缓存
3. **限流**: 防止 Dify API 调用过于频繁
4. **降级**: Dify 不可用时的降级策略
5. **A/B 测试**: 对比带上下文和不带上下文的效果
