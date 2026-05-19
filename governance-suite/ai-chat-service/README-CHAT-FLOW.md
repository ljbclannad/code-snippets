# AI Chat Service - 完整流程设计文档

## 目录

- [概述](#概述)
- [整体架构](#整体架构)
- [核心流程](#核心流程)
  - [步骤1: 会话上下文管理](#步骤1-会话上下文管理)
  - [步骤2: 并行获取用户画像和记忆](#步骤2-并行获取用户画像和记忆)
  - [步骤3: 调用 DeepSeek API](#步骤3-调用-deepseek-api)
  - [步骤4: 更新会话上下文](#步骤4-更新会话上下文)
  - [步骤5: 保存对话历史](#步骤5-保存对话历史)
  - [步骤6: 异步触发记忆提取](#步骤6-异步触发记忆提取)
  - [步骤7: 相似对话推荐](#步骤7-相似对话推荐)
- [关键设计模式](#关键设计模式)
- [性能优化](#性能优化)

---

## 概述

AI Chat Service 是一个智能对话服务，通过整合用户画像、历史记忆、会话上下文等多维度信息，提供个性化的 AI 对话体验。

### 核心特性

- ✅ **多轮对话管理**：基于 Redis 的会话上下文，支持连续对话
- ✅ **智能意图识别**：使用 DeepSeek AI 分析用户意图和对话阶段
- ✅ **记忆增强**：向量数据库存储长期记忆，提升回复准确性
- ✅ **并行 RPC 调用**：完全异步设计，支持高并发
- ✅ **相似对话推荐**：基于 Elasticsearch 的历史对话检索

### 技术栈

| 组件 | 技术 | 用途 |
|------|------|------|
| **AI 模型** | DeepSeek API | 对话生成、意图分析 |
| **会话存储** | Redis | 会话上下文（TTL 30分钟） |
| **向量数据库** | Milvus | 长期记忆存储与检索 |
| **搜索引擎** | Elasticsearch | 对话历史全文搜索 |
| **关系数据库** | MySQL | 对话历史持久化 |
| **消息队列** | RabbitMQ | RPC 通信、异步任务 |

---

## 整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                         用户请求                                 │
│                    POST /api/chat/message                        │
└────────────────────────────┬────────────────────────────────────┘
                             ↓
┌─────────────────────────────────────────────────────────────────┐
│                      AiChatService                               │
│                   handleChatRequest()                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ① 会话上下文管理 (SessionContextService)                        │
│     ├─ Redis 读取已有上下文                                      │
│     └─ DeepSeek 意图分析（首次或需要重新分析）                    │
│                                                                  │
│  ② 并行获取数据（完全异步 RPC）                                   │
│     ├─ 用户画像 (UserProfileClientFixed → RabbitMQ RPC)         │
│     └─ 记忆检索 (VectorMemoryClientFixed → RabbitMQ RPC)        │
│                                                                  │
│  ③ 调用 DeepSeek API                                            │
│     └─ 传入：用户消息 + 画像 + 上下文 + 记忆                      │
│                                                                  │
│  ④ 更新会话上下文 (Redis)                                        │
│                                                                  │
│  ⑤ 保存对话历史                                                  │
│     ├─ MySQL 持久化                                              │
│     └─ RabbitMQ 异步同步到 Elasticsearch                         │
│                                                                  │
│  ⑥ 异步触发记忆提取                                               │
│     └─ RabbitMQ Fire-and-Forget → vector-memory-service         │
│                                                                  │
│  ⑦ 相似对话推荐 (Elasticsearch)                                  │
│                                                                  │
└────────────────────────────┬────────────────────────────────────┘
                             ↓
┌─────────────────────────────────────────────────────────────────┐
│                         返回响应                                 │
│  {                                                               │
│    "answer": "AI 回复内容",                                      │
│    "conversationId": "conv-123",                                │
│    "recommendations": [...]  // 可选                             │
│  }                                                               │
└─────────────────────────────────────────────────────────────────┘
```

---

## 核心流程

### 步骤1: 会话上下文管理

#### 核心方法

```java
// AiChatService.java 第71-86行
String sessionId = request.getConversationId() != null ? 
    request.getConversationId() : UUID.randomUUID().toString();

SessionContext sessionContext = metrics.recordContextAnalysis(() -> 
    sessionContextService.getOrCreateContext(
        sessionId,
        request.getCustomerId(),
        request.getGroupId(),
        request.getMessage()
    )
);
```

#### 设计原理

**目的：** 维护多轮对话的上下文状态，让 AI 理解对话的连续性。

**工作流程：**

```
用户第一次提问
    ↓
sessionContextService.getOrCreateContext()
    ↓
Redis 中没有上下文
    ↓
调用 DeepSeekIntentAnalyzer.analyzeIntent()
    ↓
DeepSeek API 分析：
    - 用户意图（咨询健康问题）
    - 对话阶段（信息收集）
    - 当前话题（血压管理）
    ↓
创建 SessionContext 并存入 Redis
    ↓
返回上下文
```

#### 为什么这么设计？

##### 1. **Redis 存储（而非内存）**

**原因：**
- 支持分布式部署（多个实例共享上下文）
- 服务重启不丢失会话
- TTL 自动过期（30分钟无活动自动清理）

**对比：**

| 存储方式 | 优点 | 缺点 |
|---------|------|------|
| **内存** | 速度快 | 不支持分布式、重启丢失 |
| **Redis** | 分布式、持久化 | 网络开销（可忽略） |
| **MySQL** | 永久存储 | 查询慢、无 TTL |

##### 2. **首次调用 AI 分析意图**

**原因：**
- 准确理解用户意图（咨询、投诉、闲聊等）
- 识别对话阶段（信息收集、问题解决、总结）
- 为后续回复提供上下文

**示例：**

```
用户：我最近血压有点高

DeepSeek 分析：
{
  "userIntent": "咨询健康问题",
  "conversationPhase": "信息收集",
  "currentTopic": "血压管理",
  "suggestedActions": ["询问具体数值", "了解症状"]
}
```

##### 3. **动态重新分析**

```java
// SessionContextService.java 第73-80行
if (shouldReanalyzeIntent(context, currentMessage)) {
    // 检测到话题转换，重新分析
    SessionContext updatedContext = intentAnalyzer.analyzeIntentWithTimeout(
        currentMessage,
        recentMessages,
        5000
    );
}
```

**触发条件：**
- 消息数达到阈值（每 5 条消息重新分析）
- 检测到话题转换（关键词变化）

**为什么需要？**

```
对话1：我血压有点高（话题：血压）
对话2：应该注意什么？（话题：血压）
对话3：我还想问问糖尿病（话题转换！）← 触发重新分析
```

#### 核心数据结构

```java
public class SessionContext {
    private String conversationId;      // 会话ID
    private String customerId;          // 客户ID
    private String groupId;             // 群组ID
    
    // AI 分析结果
    private String userIntent;          // 用户意图
    private String conversationPhase;   // 对话阶段
    private String currentTopic;        // 当前话题
    
    // 会话状态
    private Long sessionStartTime;      // 会话开始时间
    private Integer messageCount;       // 消息计数
    private List<MessageSummary> recentSummaries;  // 最近消息摘要
}
```

#### 性能指标

| 操作 | 耗时 | 说明 |
|------|------|------|
| **Redis 读取** | ~5ms | 已有上下文 |
| **AI 意图分析** | ~500ms | 首次创建 |
| **Redis 写入** | ~5ms | 保存上下文 |

#### 配置项

```yaml
# application.yml
ai-chat:
  session:
    ttl-minutes: 30              # Redis TTL
    simple-window-size: 3        # 简单对话窗口大小
    complex-window-size: 5       # 复杂对话窗口大小
    reanalyze-threshold: 5       # 重新分析阈值
```

---


### 步骤2: 并行获取用户画像和记忆

#### 核心方法

```java
// AiChatService.java 第88-122行

// 2. 并行获取用户画像和记忆（完全异步 RPC 设计）
//
// 设计初衷：
// - RPC 模式：通过 RabbitMQ 发送请求并等待响应（replyTo + correlationId）
// - 完全异步：不创建工作线程，不阻塞工作线程，节省线程池资源
// - 并行执行：两个 RPC 请求同时发送，总耗时 = max(请求1, 请求2)
// - 高并发优化：避免工作线程池耗尽，支持数千并发请求
//
// 线程模型：
//   主线程 → 发送 RabbitMQ 请求 → 注册回调 → 在 allOf().join() 阻塞
//   监听器线程 → 接收响应 → 完成 Future → 触发回调
//
CompletableFuture<UserProfileDTO> profileFuture = 
    userProfileClient.requestUserProfile(request.getCustomerId())
        .whenComplete((profile, ex) -> {
            if (ex == null) {
                metrics.recordProfileFetch(() -> profile);
            }
        });

CompletableFuture<List<MemoryItemDTO>> memoriesFuture = 
    memoryService.retrieveMemories(
        request.getGroupId(),
        request.getCustomerId(),
        request.getMessage()
    ).whenComplete((memories, ex) -> {
        if (ex == null) {
            metrics.recordMemoryRetrieval(() -> memories);
        }
    });

// 等待两个结果（不阻塞工作线程，只阻塞主线程）
CompletableFuture.allOf(profileFuture, memoriesFuture).join();

// 获取结果
UserProfileDTO profile = profileFuture.get();
List<MemoryItemDTO> memories = memoriesFuture.get();
```

#### 设计原理

**目的：** 并行获取用户画像和历史记忆，为 AI 提供个性化上下文。

**工作流程：**

```
T0: 主线程同时发起两个 RPC 请求
    ├─ 用户画像请求 → RabbitMQ → user-service（耗时 300ms）
    └─ 记忆检索请求 → RabbitMQ → vector-memory-service（耗时 200ms）
    
    主线程立即执行到 allOf().join()，进入等待状态
    
T200: 记忆检索响应到达
      → 监听器线程接收响应
      → 调用 memoriesFuture.complete()
      → 触发 whenComplete 回调
      
T300: 用户画像响应到达
      → 监听器线程接收响应
      → 调用 profileFuture.complete()
      → 触发 whenComplete 回调
      → allOf() 检测到两个 Future 都完成
      → join() 返回
      
T300: 主线程继续执行，获取结果
```

#### 为什么这么设计？

##### 1. **并行执行（而非串行）**

**串行方式（不推荐）：**
```java
UserProfileDTO profile = userProfileClient.requestUserProfile(...).join();  // 300ms
List<MemoryItemDTO> memories = memoryService.retrieveMemories(...).join();  // 200ms
// 总耗时：300ms + 200ms = 500ms
```

**并行方式（当前实现）：**
```java
CompletableFuture<UserProfileDTO> profileFuture = userProfileClient.requestUserProfile(...);
CompletableFuture<List<MemoryItemDTO>> memoriesFuture = memoryService.retrieveMemories(...);
CompletableFuture.allOf(profileFuture, memoriesFuture).join();
// 总耗时：max(300ms, 200ms) = 300ms
```

**性能提升：** (500 - 300) / 500 = **40%**

##### 2. **完全异步（不阻塞工作线程）**

**改进前（半异步）：**
```java
CompletableFuture.supplyAsync(() ->
    userProfileClient.requestUserProfile(...).join()  // ❌ 阻塞工作线程
);
```

**线程占用：**
```
100个并发请求：
- 主线程池：100个线程（立即释放）✅
- 工作线程池：100个线程（阻塞 300ms）❌
- 总线程：200个
```

**改进后（完全异步）：**
```java
userProfileClient.requestUserProfile(...)  // ✅ 不阻塞任何工作线程
    .whenComplete((profile, ex) -> { ... });
```

**线程占用：**
```
100个并发请求：
- 主线程池：100个线程（在 join() 阻塞）
- 工作线程池：0个线程 ✅
- 总线程：100个
```

**资源节省：** 50% 的线程资源

##### 3. **RabbitMQ RPC 模式**

**核心机制：**

```java
// UserProfileClientFixed.java 第52-56行
rabbitTemplate.convertAndSend(REQUEST_QUEUE, request, message -> {
    message.getMessageProperties().setReplyTo(REPLY_QUEUE);        // 指定响应队列
    message.getMessageProperties().setCorrelationId(correlationId); // 请求ID
    return message;
});

// 第84-95行
@RabbitListener(queues = REPLY_QUEUE)
public void handleResponse(UserProfileResponseMessage response, Message message) {
    String correlationId = message.getMessageProperties().getCorrelationId();
    CompletableFuture<UserProfileResponseMessage> future = pendingRequests.remove(correlationId);
    if (future != null) {
        future.complete(response);  // 完成 Future
    }
}
```

**RPC 流程图：**

```
调用方线程                    RabbitMQ                    服务端线程                    监听器线程
    |                           |                           |                           |
    |--① 创建 Future ---------->|                           |                           |
    |   存入 pendingRequests    |                           |                           |
    |                           |                           |                           |
    |--② 发送消息 ------------->|                           |                           |
    |   (replyTo + correlationId)|                          |                           |
    |                           |                           |                           |
    |--③ 返回 Future ---------->|                           |                           |
    |   (立即返回，无数据)        |                           |                           |
    |                           |                           |                           |
    |                           |--④ 路由到服务端 -------->|                           |
    |                           |                           |                           |
    |                           |                           |--⑤ 处理业务逻辑          |
    |                           |                           |   查询用户画像            |
    |                           |                           |                           |
    |                           |<--⑥ 发送响应到 replyTo --|                           |
    |                           |   (携带 correlationId)    |                           |
    |                           |                           |                           |
    |                           |--⑦ 路由到监听器 ---------------------------------->|
    |                           |                           |                           |
    |                           |                           |                           |--⑧ 提取 correlationId
    |                           |                           |                           |   从 Map 获取 Future
    |                           |                           |                           |
    |                           |                           |                           |--⑨ future.complete()
    |                           |                           |                           |
    |<--⑩ join/get 获取结果 ----------------------------------------------------|
    |   (阻塞等待直到 Future 完成)
```

**关键点：**
- `replyTo`：指定响应队列（固定队列，所有请求共用）
- `correlationId`：匹配异步请求和响应
- `CompletableFuture`：实现异步等待，支持超时控制

##### 4. **记忆检索的分层策略**

```java
// MemoryService.java 第40-86行
public CompletableFuture<List<MemoryItemDTO>> retrieveMemories(...) {
    // 1. 向量检索（请求更多数量以便过滤）
    int fetchCount = maxCount * 2;
    
    return vectorMemoryClient.requestMemories(groupId, customerId, query, fetchCount)
        .thenApply(memories -> {
            // 2. 按相似度过滤
            List<MemoryItemDTO> filtered = memories.stream()
                .filter(m -> m.getSimilarity() >= similarityThreshold)
                .collect(Collectors.toList());
            
            // 3. 去重（如果启用）
            if (enableDeduplication) {
                deduplicated = deduplicateMemories(filtered);
            }
            
            // 4. 按相似度排序并限制数量
            return deduplicated.stream()
                .sorted(Comparator.comparing(MemoryItemDTO::getSimilarity).reversed())
                .limit(maxCount)
                .collect(Collectors.toList());
        });
}
```

**为什么分层？**
- **过滤**：去除低相关性记忆（相似度 < 0.7）
- **去重**：避免重复信息
- **排序**：优先使用高相关性记忆
- **限制**：控制上下文长度（避免超出 token 限制）

#### 数据结构

**用户画像（UserProfileDTO）：**
```java
{
  "customerId": "user001",
  "name": "张三",
  "age": 45,
  "gender": "男",
  "healthConditions": ["高血压", "糖尿病"],
  "medications": ["降压药", "二甲双胍"],
  "preferences": {
    "language": "简体中文",
    "responseStyle": "专业详细"
  }
}
```

**记忆列表（List<MemoryItemDTO>）：**
```java
[
  {
    "id": "mem001",
    "content": "用户血压 140/90，属于高血压1级",
    "similarity": 0.92,
    "timestamp": "2024-01-15T10:30:00"
  },
  {
    "id": "mem002",
    "content": "用户对降压药有轻微副作用反应",
    "similarity": 0.85,
    "timestamp": "2024-01-10T14:20:00"
  }
]
```

#### 性能指标

| 操作 | 串行耗时 | 并行耗时 | 提升 |
|------|---------|---------|------|
| **用户画像 RPC** | 300ms | 300ms | - |
| **记忆检索 RPC** | 200ms | 200ms | - |
| **总耗时** | 500ms | 300ms | **40%** |

#### 配置项

```yaml
# application.yml
ai-chat:
  memory:
    max-count: 5                    # 最大返回记忆数
    similarity-threshold: 0.7       # 相似度阈值
    enable-deduplication: true      # 是否去重
  
  rpc:
    timeout-ms: 5000                # RPC 超时时间
```

---

### 步骤3: 调用 DeepSeek API

#### 核心方法

```java
// AiChatService.java 第127-128行
DifyResponse difyResponse = deepSeekClient.chat(
    request,        // 用户消息
    profile,        // 用户画像
    sessionContext, // 会话上下文
    memories        // 历史记忆
);
```

#### 设计原理

**目的：** 整合所有上下文信息，调用 DeepSeek AI 生成个性化回复。

**工作流程：**

```
DeepSeekClient.chat()
    ↓
ContextBudgetManager.buildSystemPrompt()
    ↓
构建系统提示词：
    ├─ 基础角色定义
    ├─ 用户画像信息
    ├─ 会话上下文（意图、阶段、话题）
    ├─ 历史记忆（Top-5 相关记忆）
    └─ 最近对话摘要
    ↓
调用 DeepSeek API
    ↓
返回 AI 回复
```

#### 为什么这么设计？

##### 1. **上下文预算管理（ContextBudgetManager）**

**问题：** DeepSeek API 有 token 限制（如 8K tokens）

**解决方案：** 动态分配 token 预算

```java
// ContextBudgetManager.java
public String buildSystemPrompt(
    UserProfileDTO profile,
    SessionContext sessionContext,
    List<MemoryItemDTO> memories
) {
    int totalBudget = 4000;  // 系统提示词预算
    
    // 动态分配
    int profileBudget = 500;      // 用户画像
    int contextBudget = 300;      // 会话上下文
    int memoryBudget = 2000;      // 历史记忆（最重要）
    int summaryBudget = 1200;     // 对话摘要
    
    // 按优先级截断
    String profileSection = truncate(buildProfileSection(profile), profileBudget);
    String contextSection = truncate(buildContextSection(sessionContext), contextBudget);
    String memorySection = truncate(buildMemorySection(memories), memoryBudget);
    String summarySection = truncate(buildSummarySection(sessionContext), summaryBudget);
    
    return profileSection + contextSection + memorySection + summarySection;
}
```

**预算分配策略：**

| 部分 | Token 预算 | 优先级 | 说明 |
|------|-----------|--------|------|
| **历史记忆** | 2000 | 最高 | 最相关的上下文信息 |
| **对话摘要** | 1200 | 高 | 最近对话内容 |
| **用户画像** | 500 | 中 | 个性化信息 |
| **会话上下文** | 300 | 中 | 意图和阶段 |

##### 2. **系统提示词结构**

```
你是一个专业的健康咨询助手。

## 用户信息
- 姓名：张三
- 年龄：45岁
- 性别：男
- 健康状况：高血压、糖尿病
- 当前用药：降压药、二甲双胍

## 对话上下文
- 用户意图：咨询健康问题
- 对话阶段：信息收集
- 当前话题：血压管理

## 相关记忆
1. 用户血压 140/90，属于高血压1级（相似度：0.92）
2. 用户对降压药有轻微副作用反应（相似度：0.85）
3. 用户关注饮食对血压的影响（相似度：0.78）

## 最近对话
用户：我最近血压有点高
助手：建议您监测血压变化，必要时就医
用户：应该注意什么？

## 当前问题
用户：血压高可以吃什么降压？
```

##### 3. **为什么传入这么多上下文？**

**对比：**

**无上下文（通用 AI）：**
```
用户：血压高可以吃什么降压？
AI：  可以吃芹菜、香蕉、燕麦等食物...（通用回答）
```

**有上下文（个性化 AI）：**
```
用户：血压高可以吃什么降压？
AI：  张先生，考虑到您目前血压 140/90，并且正在服用降压药，
      建议您在饮食上注意：
      1. 减少钠盐摄入（每天不超过6克）
      2. 多吃富含钾的食物（香蕉、土豆）
      3. 避免与降压药冲突的食物（如西柚）
      
      另外，您之前提到对降压药有轻微副作用，建议您在调整饮食的同时，
      定期监测血压，如有不适及时就医。
```

**差异：**
- ✅ 称呼用户姓名（个性化）
- ✅ 引用历史数据（140/90）
- ✅ 考虑用药情况（降压药）
- ✅ 关联历史记忆（副作用）
- ✅ 提供针对性建议

#### 性能指标

| 操作 | 耗时 | 说明 |
|------|------|------|
| **构建系统提示词** | ~10ms | 字符串拼接 |
| **DeepSeek API 调用** | ~1000ms | 网络 + AI 推理 |
| **解析响应** | ~5ms | JSON 解析 |
| **总耗时** | ~1015ms | - |

---

### 步骤4: 更新会话上下文

#### 核心方法

```java
// AiChatService.java 第130-132行
sessionContextService.updateContext(
    sessionId, 
    request.getMessage(),      // 用户消息
    difyResponse.getAnswer()   // AI 回复
);
```

#### 设计原理

**目的：** 将本轮对话添加到会话上下文，供下次对话使用。

**工作流程：**

```
SessionContextService.updateContext()
    ↓
1. 从 Redis 读取当前上下文
    ↓
2. 添加新的消息摘要
    ├─ 用户消息
    └─ AI 回复
    ↓
3. 更新消息计数
    ↓
4. 保持窗口大小（FIFO，保留最近 N 条）
    ↓
5. 保存回 Redis（刷新 TTL）
```

#### 为什么这么设计？

##### 1. **消息摘要（而非完整消息）**

**完整消息（不推荐）：**
```java
recentMessages.add(new Message(
    "我最近血压有点高，140/90，医生说要注意饮食，但我不知道具体该怎么做...",
    "建议您监测血压变化，必要时就医。关于饮食，您可以..."
));
// 占用大量 Redis 空间和 token
```

**消息摘要（当前实现）：**
```java
recentSummaries.add(new MessageSummary(
    "血压高，咨询饮食建议",  // 用户消息摘要（50字以内）
    "建议监测血压，注意饮食"  // AI 回复摘要（50字以内）
));
// 节省空间，保留关键信息
```

**优势：**
- 节省 Redis 存储空间
- 减少 token 消耗
- 保留关键信息

##### 2. **滑动窗口（FIFO）**

```java
// SessionContextService.java
private static final int SIMPLE_WINDOW_SIZE = 3;
private static final int COMPLEX_WINDOW_SIZE = 5;

public void updateContext(String sessionId, String userMessage, String aiResponse) {
    SessionContext context = getFromRedis(sessionId);
    
    // 添加新摘要
    context.getRecentSummaries().add(
        new MessageSummary(
            truncate(userMessage, MAX_SUMMARY_LENGTH),
            truncate(aiResponse, MAX_SUMMARY_LENGTH)
        )
    );
    
    // 保持窗口大小
    int windowSize = isComplexConversation(context) ? 
        COMPLEX_WINDOW_SIZE : SIMPLE_WINDOW_SIZE;
    
    if (context.getRecentSummaries().size() > windowSize) {
        context.getRecentSummaries().remove(0);  // 移除最旧的
    }
    
    // 更新计数
    context.setMessageCount(context.getMessageCount() + 1);
    
    // 保存回 Redis
    saveToRedis(sessionId, context);
}
```

**为什么需要窗口？**
- 控制上下文长度（避免超出 token 限制）
- 保留最近的对话（最相关）
- 自动淘汰旧对话

##### 3. **刷新 TTL**

```java
// 每次更新都刷新 TTL
redisTemplate.opsForValue().set(
    key, 
    jsonContext, 
    CONTEXT_TTL_MINUTES,  // 30分钟
    TimeUnit.MINUTES
);
```

**为什么刷新？**
- 活跃会话保持在线
- 不活跃会话自动过期
- 节省 Redis 内存

**示例：**
```
T0:    用户发消息 → TTL 设为 30分钟
T10:   用户发消息 → TTL 刷新为 30分钟
T20:   用户发消息 → TTL 刷新为 30分钟
T50:   30分钟无活动 → 自动过期删除
```

#### 数据流

```
对话1：
用户：我血压有点高
AI：  建议监测血压
    ↓
Redis: recentSummaries = [
  {user: "血压高", ai: "建议监测"}
]

对话2：
用户：应该注意什么？
AI：  注意饮食和运动
    ↓
Redis: recentSummaries = [
  {user: "血压高", ai: "建议监测"},
  {user: "注意什么", ai: "饮食运动"}
]

对话3：
用户：可以吃什么？
AI：  芹菜、香蕉等
    ↓
Redis: recentSummaries = [
  {user: "血压高", ai: "建议监测"},
  {user: "注意什么", ai: "饮食运动"},
  {user: "吃什么", ai: "芹菜香蕉"}
]

对话4：
用户：需要吃药吗？
AI：  建议就医评估
    ↓
Redis: recentSummaries = [
  {user: "注意什么", ai: "饮食运动"},  ← 最旧的被移除
  {user: "吃什么", ai: "芹菜香蕉"},
  {user: "吃药吗", ai: "就医评估"}
]
```

---

### 步骤5: 保存对话历史

#### 核心方法

```java
// AiChatService.java 第134-135行
saveConversationHistory(request, difyResponse);

// 第176-204行
private void saveConversationHistory(ChatRequest request, DifyResponse response) {
    // 1. 保存到 MySQL
    AiChatHistory history = new AiChatHistory();
    history.setCustomerId(request.getCustomerId());
    history.setConversationId(response.getConversationId());
    history.setUserMessage(request.getMessage());
    history.setAiResponse(response.getAnswer());
    history.setCreateTime(LocalDateTime.now());
    
    chatHistoryMapper.insert(history);
    
    // 2. 异步同步到 Elasticsearch
    if (syncEnabled) {
        syncToElasticsearch(history);
    }
}

// 第209-235行
private void syncToElasticsearch(AiChatHistory history) {
    ChatHistorySyncMessage message = ChatHistorySyncMessage.builder()
        .id(history.getId())
        .customerId(history.getCustomerId())
        .userMessage(history.getUserMessage())
        .aiResponse(history.getAiResponse())
        .createTime(history.getCreateTime())
        .build();
    
    // 发送到 RabbitMQ（Fire-and-Forget）
    rabbitTemplate.convertAndSend(
        RabbitMQConfig.CHAT_HISTORY_SYNC_EXCHANGE,
        RabbitMQConfig.CHAT_HISTORY_SYNC_ROUTING_KEY,
        message
    );
}
```

#### 设计原理

**目的：** 持久化对话历史，支持历史查询和相似对话推荐。

**双存储架构：**

```
对话历史
    ↓
    ├─→ MySQL（主存储）
    │   - 持久化存储
    │   - 事务保证
    │   - 结构化查询
    │
    └─→ Elasticsearch（搜索引擎）
        - 全文搜索
        - 相似度查询
        - 快速检索
```

#### 为什么这么设计？

##### 1. **MySQL + Elasticsearch 双存储**

**为什么不只用 MySQL？**

| 需求 | MySQL | Elasticsearch |
|------|-------|---------------|
| **精确查询** | ✅ 快 | ✅ 快 |
| **全文搜索** | ❌ 慢（LIKE '%keyword%'） | ✅ 快（倒排索引） |
| **相似度查询** | ❌ 不支持 | ✅ 支持（TF-IDF、BM25） |
| **事务保证** | ✅ 支持 | ❌ 不支持 |
| **持久化** | ✅ 可靠 | ⚠️ 需要配置 |

**结论：** MySQL 做主存储，Elasticsearch 做搜索加速。

##### 2. **异步同步到 Elasticsearch**

**同步方式（不推荐）：**
```java
chatHistoryMapper.insert(history);  // 50ms
elasticsearchRepository.save(document);  // 100ms
// 总耗时：150ms，用户等待时间增加
```

**异步方式（当前实现）：**
```java
chatHistoryMapper.insert(history);  // 50ms
rabbitTemplate.convertAndSend(...);  // 5ms（只是发送消息）
// 总耗时：55ms，用户无感知

// 后台消费者处理
@RabbitListener(queues = "chat.history.sync.queue")
public void handleSync(ChatHistorySyncMessage message) {
    elasticsearchRepository.save(toDocument(message));  // 后台执行
}
```

**优势：**
- 用户响应快（不等待 ES 写入）
- ES 写入失败不影响主流程
- 削峰填谷（高峰期消息排队）

##### 3. **Fire-and-Forget 模式**

```java
// 没有 replyTo，不等待响应
rabbitTemplate.convertAndSend(
    CHAT_HISTORY_SYNC_EXCHANGE,
    CHAT_HISTORY_SYNC_ROUTING_KEY,
    message
);
```

**对比 RPC 模式：**

| 特性 | RPC 模式 | Fire-and-Forget |
|------|---------|-----------------|
| **replyTo** | ✅ 有 | ❌ 无 |
| **等待响应** | ✅ 需要 | ❌ 不需要 |
| **用途** | 获取数据 | 触发后台任务 |
| **示例** | 用户画像查询 | ES 同步、记忆提取 |

##### 4. **失败处理**

```java
private void saveConversationHistory(ChatRequest request, DifyResponse response) {
    try {
        chatHistoryMapper.insert(history);
        
        if (syncEnabled) {
            syncToElasticsearch(history);
        }
    } catch (Exception e) {
        log.error("保存对话历史失败 - conversationId: {}", request.getConversationId(), e);
        // 不影响主流程，只记录错误
    }
}
```

**为什么不抛出异常？**
- 对话历史保存失败不应该影响用户收到回复
- 降级处理：MySQL 失败了，至少用户还能继续对话
- 监控告警：记录错误日志，运维介入

#### 数据表结构

**MySQL（t_ai_chat_history）：**
```sql
CREATE TABLE t_ai_chat_history (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    customer_id VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(64) NOT NULL,
    user_message TEXT NOT NULL,
    ai_response TEXT NOT NULL,
    prompt_tokens INT,
    completion_tokens INT,
    total_tokens INT,
    create_time DATETIME NOT NULL,
    is_deleted TINYINT DEFAULT 0,
    INDEX idx_customer_id (customer_id),
    INDEX idx_conversation_id (conversation_id),
    INDEX idx_create_time (create_time)
);
```

**Elasticsearch（chat_history）：**
```json
{
  "mappings": {
    "properties": {
      "id": {"type": "long"},
      "customerId": {"type": "keyword"},
      "conversationId": {"type": "keyword"},
      "userMessage": {"type": "text", "analyzer": "ik_max_word"},
      "aiResponse": {"type": "text", "analyzer": "ik_max_word"},
      "createTime": {"type": "date"}
    }
  }
}
```

#### 性能指标

| 操作 | 耗时 | 说明 |
|------|------|------|
| **MySQL 插入** | ~50ms | 同步操作 |
| **发送 MQ 消息** | ~5ms | 异步操作 |
| **ES 写入（后台）** | ~100ms | 用户无感知 |

---

### 步骤6: 异步触发记忆提取

#### 核心方法

```java
// AiChatService.java 第137-149行
CompletableFuture.runAsync(() -> {
    try {
        memoryService.extractMemory(
            request.getGroupId(),
            request.getCustomerId(),
            request.getMessage(),      // 用户消息
            difyResponse.getAnswer()   // AI 回复
        );
    } catch (Exception e) {
        log.error("记忆提取触发失败 - customerId: {}, correlationId: {}",
            request.getCustomerId(), correlationId, e);
    }
});

// MemoryService.java 第154-156行
public void extractMemory(String groupId, String customerId, String userMessage, String aiResponse) {
    vectorMemoryClient.triggerMemoryExtraction(groupId, customerId, userMessage, aiResponse);
}

// VectorMemoryClientFixed.java 第101-120行
public void triggerMemoryExtraction(String groupId, String customerId, String userMessage, String aiResponse) {
    ExtractionRequestMessage request = new ExtractionRequestMessage();
    request.setGroupId(groupId);
    request.setCustomerId(customerId);
    request.setUserMessage(userMessage);
    request.setAiResponse(aiResponse);
    request.setCorrelationId(correlationId);
    
    // 异步发送，不等待响应（Fire-and-Forget）
    rabbitTemplate.convertAndSend(EXTRACTION_QUEUE, request);
}
```

#### 设计原理

**目的：** 从对话中提取关键信息，存储为长期记忆（向量数据库）。

**工作流程：**

```
用户对话完成
    ↓
CompletableFuture.runAsync() → 独立线程
    ↓
memoryService.extractMemory()
    ↓
vectorMemoryClient.triggerMemoryExtraction()
    ↓
RabbitMQ (memory.extraction.queue) → Fire-and-Forget
    ↓
vector-memory-service 消费消息
    ↓
1. 使用 AI 分析对话，提取关键信息
2. 生成向量嵌入（Embedding）
3. 存储到 Milvus 向量数据库
```

#### 为什么这么设计？

##### 1. **双重异步**

```java
// 第一层异步：CompletableFuture.runAsync
CompletableFuture.runAsync(() -> {
    // 在独立线程中执行
    
    // 第二层异步：MQ Fire-and-Forget
    rabbitTemplate.convertAndSend(EXTRACTION_QUEUE, request);
});
```

**为什么双重异步？**

| 层次 | 作用 | 耗时 |
|------|------|------|
| **第一层** | 不阻塞主流程，用户立即收到回复 | ~5ms（发送 MQ） |
| **第二层** | 记忆提取是耗时操作，完全后台处理 | ~2000ms（AI 分析 + 向量化） |

**时间线对比：**

**同步方式（不推荐）：**
```
T0:    用户发送消息
T1300: AI 生成回复
T3300: 记忆提取完成（阻塞 2 秒）
T3300: 用户收到回复 ← 总耗时 3.3 秒
```

**异步方式（当前实现）：**
```
T0:    用户发送消息
T1300: AI 生成回复
T1300: 用户收到回复 ← 总耗时 1.3 秒 ✅
       ↓
       后台：记忆提取（2 秒）
T3300: 记忆提取完成（用户无感知）
```

##### 2. **Fire-and-Forget 模式（而非 RPC）**

```java
// 第116行：直接发送，不设置 replyTo
rabbitTemplate.convertAndSend(EXTRACTION_QUEUE, request);
// ↑ 没有 replyTo
// ↑ 没有 @RabbitListener 接收响应
// ↑ 不返回 CompletableFuture
```

**对比 RPC 模式：**

| 特性 | 记忆检索（RPC） | 记忆提取（Fire-and-Forget） |
|------|----------------|---------------------------|
| **模式** | Request-Reply | Fire-and-Forget |
| **replyTo** | ✅ 有 | ❌ 无 |
| **等待响应** | ✅ 需要 | ❌ 不需要 |
| **阻塞** | 主线程阻塞 | 不阻塞 |
| **用途** | 获取数据（影响回复） | 触发后台任务（不影响回复） |
| **时机** | 生成回复前 | 生成回复后 |

##### 3. **记忆提取的后台处理**

**vector-memory-service 消费者：**

```java
@RabbitListener(queues = "memory.extraction.queue")
public void handleExtraction(ExtractionRequestMessage message) {
    // 1. 调用 AI 分析对话，提取关键信息
    String extractedInfo = deepSeekClient.extractKeyInfo(
        message.getUserMessage(),
        message.getAiResponse()
    );
    // 示例输出："用户血压 140/90，属于高血压1级"
    
    // 2. 生成向量嵌入
    float[] embedding = embeddingService.generateEmbedding(extractedInfo);
    // 示例输出：[0.123, -0.456, 0.789, ...]（768维向量）
    
    // 3. 存储到 Milvus
    milvusClient.insert(
        collectionName,
        MemoryEntity.builder()
            .groupId(message.getGroupId())
            .customerId(message.getCustomerId())
            .content(extractedInfo)
            .embedding(embedding)
            .timestamp(System.currentTimeMillis())
            .build()
    );
}
```

##### 4. **失败不影响主流程**

```java
CompletableFuture.runAsync(() -> {
    try {
        memoryService.extractMemory(...);
    } catch (Exception e) {
        log.error("记忆提取触发失败...", e);
        // 只记录错误，不抛出异常
    }
});
```

**为什么？**
- 记忆提取失败不应该影响用户收到回复
- 降级处理：这次没提取成功，下次对话还能继续
- 监控告警：记录错误日志，运维介入

#### 实际场景示例

```
用户：我最近血压有点高，140/90
AI：  建议您监测血压变化，必要时就医

↓ 触发记忆提取（后台）

vector-memory-service 分析：
- 提取关键信息：用户血压 140/90，属于高血压1级
- 生成向量嵌入：[0.123, -0.456, 0.789, ...]
- 存储到 Milvus

↓ 下次对话

用户：我应该注意什么？
AI：  根据您之前提到的血压情况（140/90）...
      ↑ 从向量数据库检索到的记忆
```

#### 性能指标

| 操作 | 耗时 | 说明 |
|------|------|------|
| **发送 MQ 消息** | ~5ms | 用户无感知 |
| **AI 提取关键信息** | ~1000ms | 后台执行 |
| **生成向量嵌入** | ~500ms | 后台执行 |
| **Milvus 写入** | ~500ms | 后台执行 |
| **总耗时（后台）** | ~2000ms | 不影响用户 |

---

### 步骤7: 相似对话推荐

#### 核心方法

```java
// AiChatService.java 第155-172行
if (autoRecommendEnabled) {
    try {
        List<ChatResponse.SimilarConversation> recommendations = 
            getRecommendations(request.getCustomerId(), request.getMessage());
        
        if (!recommendations.isEmpty()) {
            return ChatResponse.successWithRecommendations(
                difyResponse.getAnswer(),
                difyResponse.getConversationId(),
                recommendations
            );
        }
    } catch (Exception e) {
        log.error("获取推荐失败...", e);
        // 推荐失败不影响主流程，继续返回正常响应
    }
}

// 第240-259行
private List<ChatResponse.SimilarConversation> getRecommendations(String customerId, String message) {
    List<ChatHistoryDocument> similarDocs = 
        searchService.findSimilarConversations(customerId, message, recommendCount);
    
    return similarDocs.stream()
        .map(doc -> ChatResponse.SimilarConversation.builder()
            .id(doc.getId())
            .userMessage(doc.getUserMessage())
            .aiResponse(doc.getAiResponse())
            .createTime(doc.getCreateTime())
            .build())
        .collect(Collectors.toList());
}
```

#### 设计原理

**目的：** 从 Elasticsearch 查找用户历史上问过的相似问题，推荐给用户参考。

**工作流程：**

```
getRecommendations()
    ↓
ChatHistorySearchService.findSimilarConversations()
    ↓
Elasticsearch 查询
    ├─ 提取关键词
    ├─ 基于文本相似度查询
    └─ 返回 Top-3 相似对话
    ↓
构建推荐列表
    ↓
返回给用户
```

#### 为什么这么设计？

##### 1. **可配置开关**

```yaml
ai-chat:
  chat-history:
    auto-recommend-enabled: true  # 是否启用推荐
    recommend-count: 3            # 推荐数量
```

**为什么需要开关？**
- 不同场景需求不同（客服需要，闲聊不需要）
- A/B 测试（对比有无推荐的效果）
- 降级开关（ES 故障时关闭）

##### 2. **失败不影响主流程**

```java
try {
    recommendations = getRecommendations(...);
} catch (Exception e) {
    log.error("获取推荐失败...");
    // 推荐失败不影响主流程，继续返回正常响应
}
```

**降级策略：**
```
ES 正常 → 返回 AI 回复 + 推荐
ES 故障 → 返回 AI 回复（无推荐）
```

**即使推荐失败，用户仍然能收到 AI 回复。**

##### 3. **条件返回**

```java
if (!recommendations.isEmpty()) {
    // 有推荐：返回带推荐的响应
    return ChatResponse.successWithRecommendations(...);
}
// 无推荐：返回普通响应
return ChatResponse.success(...);
```

##### 4. **Elasticsearch 相似度查询**

```java
// ChatHistorySearchService.java
public List<ChatHistoryDocument> findSimilarConversations(String customerId, String message, int limit) {
    // 1. 提取关键词
    String[] keywords = extractKeywords(message);
    
    // 2. 构建查询
    BoolQueryBuilder query = QueryBuilders.boolQuery()
        .must(QueryBuilders.termQuery("customerId", customerId))
        .should(QueryBuilders.matchQuery("userMessage", message).boost(2.0f))
        .should(QueryBuilders.matchQuery("aiResponse", message).boost(1.0f));
    
    // 3. 执行查询
    SearchHits<ChatHistoryDocument> hits = elasticsearchOperations.search(
        new NativeSearchQueryBuilder()
            .withQuery(query)
            .withPageable(PageRequest.of(0, limit))
            .build(),
        ChatHistoryDocument.class
    );
    
    return hits.stream()
        .map(SearchHit::getContent)
        .collect(Collectors.toList());
}
```

**查询策略：**
- 用户消息权重更高（boost 2.0）
- AI 回复权重较低（boost 1.0）
- 只查询当前用户的历史

#### 实际场景示例

**用户当前提问：**
```
用户：我血压有点高怎么办？
```

**AI 回复 + 相似对话推荐：**
```json
{
  "success": true,
  "answer": "建议您监测血压变化，必要时就医...",
  "conversationId": "conv-123",
  "recommendations": [
    {
      "id": "1001",
      "userMessage": "我血压140/90算高吗？",
      "aiResponse": "这个血压值属于高血压1级...",
      "createTime": "2024-01-15 10:30:00"
    },
    {
      "id": "1002",
      "userMessage": "高血压需要吃药吗？",
      "aiResponse": "是否需要药物治疗需要医生评估...",
      "createTime": "2024-01-10 14:20:00"
    },
    {
      "id": "1003",
      "userMessage": "血压高有什么症状？",
      "aiResponse": "高血压常见症状包括头晕、头痛...",
      "createTime": "2024-01-05 09:15:00"
    }
  ]
}
```

**前端展示效果：**
```
┌─────────────────────────────────────────┐
│ AI 回复：                                │
│ 建议您监测血压变化，必要时就医...        │
└─────────────────────────────────────────┘

💡 您可能还想了解：
┌─────────────────────────────────────────┐
│ 📌 我血压140/90算高吗？                  │
│    这个血压值属于高血压1级...            │
│    2024-01-15 10:30                     │
├─────────────────────────────────────────┤
│ 📌 高血压需要吃药吗？                    │
│    是否需要药物治疗需要医生评估...       │
│    2024-01-10 14:20                     │
├─────────────────────────────────────────┤
│ 📌 血压高有什么症状？                    │
│    高血压常见症状包括头晕、头痛...       │
│    2024-01-05 09:15                     │
└─────────────────────────────────────────┘
```

#### 与记忆检索的区别

| 维度 | 记忆检索（第2步） | 相似对话推荐（第7步） |
|------|-----------------|---------------------|
| **数据源** | Milvus 向量数据库 | Elasticsearch |
| **存储内容** | 提取的关键信息 | 完整对话历史 |
| **查询方式** | 向量相似度 | 文本相似度 |
| **用途** | 给 AI 提供上下文 | 给用户提供参考 |
| **时机** | 生成回复前 | 生成回复后 |
| **是否必需** | 必需（影响回复质量） | 可选（增强用户体验） |

#### 性能指标

| 操作 | 耗时 | 说明 |
|------|------|------|
| **ES 查询** | ~50ms | 文本相似度查询 |
| **构建推荐列表** | ~5ms | 数据转换 |
| **总耗时** | ~55ms | 可接受 |

---

## 关键设计模式

### 1. 完全异步 RPC 模式

**核心思想：** 不创建工作线程，不阻塞工作线程，节省线程池资源。

**实现方式：**
```java
CompletableFuture<UserProfileDTO> profileFuture = 
    userProfileClient.requestUserProfile(customerId)
        .whenComplete((profile, ex) -> { ... });
```

**优势：**
- 节省 50% 线程资源
- 支持高并发（数千请求/秒）
- 避免工作线程池耗尽

### 2. 并行执行模式

**核心思想：** 多个独立操作并行执行，总耗时 = max(操作1, 操作2, ...)

**实现方式：**
```java
CompletableFuture<UserProfileDTO> profileFuture = ...;
CompletableFuture<List<MemoryItemDTO>> memoriesFuture = ...;
CompletableFuture.allOf(profileFuture, memoriesFuture).join();
```

**优势：**
- 性能提升 40%（500ms → 300ms）
- 充分利用网络和 CPU 资源

### 3. Fire-and-Forget 模式

**核心思想：** 触发后台任务，不等待结果。

**实现方式：**
```java
rabbitTemplate.convertAndSend(EXTRACTION_QUEUE, request);
// 没有 replyTo，不等待响应
```

**适用场景：**
- 记忆提取（耗时 2 秒）
- ES 同步（耗时 100ms）
- 不影响主流程的后台任务

### 4. 降级处理模式

**核心思想：** 非核心功能失败不影响主流程。

**实现方式：**
```java
try {
    recommendations = getRecommendations(...);
} catch (Exception e) {
    log.error("获取推荐失败...");
    // 继续返回正常响应
}
```

**适用场景：**
- 相似对话推荐
- 记忆提取
- ES 同步

### 5. 上下文预算管理模式

**核心思想：** 动态分配 token 预算，避免超出 API 限制。

**实现方式：**
```java
int totalBudget = 4000;
int memoryBudget = 2000;  // 最重要
int summaryBudget = 1200;
int profileBudget = 500;
int contextBudget = 300;
```

**优势：**
- 保证不超出 token 限制
- 优先保留最重要的信息

---

## 性能优化

### 整体性能指标

| 步骤 | 操作 | 耗时 | 优化方式 |
|------|------|------|---------|
| **1** | 会话上下文管理 | ~5ms（已有）/ ~500ms（首次） | Redis 缓存 |
| **2** | 并行获取画像+记忆 | ~300ms | 并行执行（原 500ms） |
| **3** | DeepSeek API | ~1000ms | - |
| **4** | 更新会话上下文 | ~5ms | Redis 写入 |
| **5** | 保存对话历史 | ~55ms | 异步 ES 同步 |
| **6** | 触发记忆提取 | ~5ms | 双重异步 |
| **7** | 相似对话推荐 | ~55ms | 可选功能 |
| **总耗时** | - | **~1425ms** | - |

### 优化效果对比

| 场景 | 优化前 | 优化后 | 提升 |
|------|--------|--------|------|
| **串行 RPC** | 500ms | 300ms | **40%** |
| **线程占用** | 200个 | 100个 | **50%** |
| **用户响应** | 3300ms | 1425ms | **57%** |

### 高并发性能

| 并发数 | 优化前 TPS | 优化后 TPS | 提升 |
|--------|-----------|-----------|------|
| 100 | 333 | 333 | 0% |
| 200 | 666 | 666 | 0% |
| **300** | **666** | **1000** | **50%** |
| **500** | **666** | **1666** | **150%** |
| **1000** | **666** | **3333** | **400%** |

**关键点：** 当并发数超过线程池大小（200）时，异步模式的优势显现。

---

## 配置参考

```yaml
# application.yml
ai-chat:
  # 会话管理
  session:
    ttl-minutes: 30
    simple-window-size: 3
    complex-window-size: 5
    reanalyze-threshold: 5
  
  # 记忆检索
  memory:
    max-count: 5
    similarity-threshold: 0.7
    enable-deduplication: true
  
  # 对话历史
  chat-history:
    sync-enabled: true
    auto-recommend-enabled: true
    recommend-count: 3
  
  # RPC 配置
  rpc:
    timeout-ms: 5000

# DeepSeek API
deepseek:
  api:
    url: https://api.deepseek.com/v1/chat/completions
    key: ${DEEPSEEK_API_KEY}
    timeout: 30000
  
  context-budget:
    total: 4000
    profile: 500
    context: 300
    memory: 2000
    summary: 1200
```

---

## 总结

AI Chat Service 通过以下设计实现了高性能、高可用的智能对话服务：

1. **完全异步 RPC**：节省 50% 线程资源，支持高并发
2. **并行执行**：性能提升 40%
3. **智能上下文管理**：Redis + AI 意图分析
4. **记忆增强**：向量数据库 + 分层检索
5. **双存储架构**：MySQL + Elasticsearch
6. **降级处理**：非核心功能失败不影响主流程
7. **上下文预算管理**：避免超出 token 限制

**核心优势：**
- ✅ 响应快（1.4秒）
- ✅ 高并发（3000+ TPS）
- ✅ 个性化（用户画像 + 记忆）
- ✅ 高可用（降级处理）
- ✅ 可扩展（微服务架构）

