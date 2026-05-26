# AI 记忆功能实施计划 - Plan 3: vector-memory-service (概要)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 实现 vector-memory-service 微服务，集成 Spring AI，实现向量存储、语义检索和记忆提取

**Architecture:** 使用 Spring AI 集成 Milvus、通义千问 Embedding、DeepSeek API，实现向量检索和记忆提取，通过 RabbitMQ 响应请求

**Tech Stack:** Spring Boot 3.2.5, Spring AI 1.0.0-M3, Milvus, 通义千问, DeepSeek, RabbitMQ

**预计时间**: 6-8 小时

---

## 任务概览

### Task 1: 创建模块基础结构
- 创建 pom.xml（包含 Spring AI 依赖）
- 创建 Application 主类
- 创建 application.yml（配置 Milvus、通义千问、DeepSeek）

### Task 2: 创建 Entity 和 DTO
- AiGroupMemoryEntity
- MemoryItemDTO
- MemoryExtractionResult
- MemoryRequestMessage / MemoryResponseMessage
- ExtractionRequestMessage

### Task 3: 创建 Mapper 接口
- AiGroupMemoryMapper（查询、插入、更新）
- 对应的 XML 文件

### Task 4: 配置 Spring AI
- MilvusVectorStoreConfig（配置两个 VectorStore bean）
- RabbitMQConfig
- AsyncConfig（异步执行配置）
- RetryConfig（重试配置）

### Task 5: 实现 EmbeddingService
- 封装 Spring AI 的 EmbeddingModel
- generateEmbedding() 方法
- generateEmbeddings() 批量方法
- cosineSimilarity() 计算相似度

### Task 6: 实现 VectorStoreService
- saveGroupMemory() - 保存群组记忆到 Milvus
- saveUserMemory() - 保存用户记忆到 Milvus
- searchGroupMemories() - 检索群组记忆
- searchUserMemories() - 检索用户记忆
- deleteGroupMemory() / deleteUserMemory()

### Task 7: 实现 DeepSeekService
- 封装 Spring AI 的 ChatModel
- extractMemories() - 调用 DeepSeek API
- buildExtractionPrompt() - 构建提取 prompt

### Task 8: 实现 MemoryRetrievalService
- retrieveGroupMemories() - 整合向量检索和 MySQL 查询
- hasSimilarMemory() - 去重检查

### Task 9: 实现 MemoryExtractionService
- extractAndSaveMemories() - 异步提取和保存记忆
- saveMemory() - 保存单条记忆（MySQL + Milvus 双写）

### Task 10: 实现消息监听器
- VectorMemoryMessageListener
- handleMemoryRequest() - 处理检索请求
- handleExtractionRequest() - 处理提取请求

### Task 11: 添加日志配置
- logback-spring.xml

### Task 12: 构建和测试
- 编译验证
- 启动测试
- Milvus 连接测试
- 向量生成测试

---

## 关键实现要点

### Spring AI 配置

```yaml
spring:
  ai:
    vectorstore:
      milvus:
        client:
          host: localhost
          port: 19530
        database-name: default
        embedding-dimension: 1024
    
    alibaba:
      dashscope:
        api-key: ${DASHSCOPE_API_KEY}
        embedding:
          model: text-embedding-v2
    
    openai:
      api-key: ${DEEPSEEK_API_KEY}
      base-url: https://api.deepseek.com/v1
      chat:
        model: deepseek-chat
```

### 双写策略

```java
@Transactional
public void saveMemory(AiGroupMemoryEntity memory) {
    // 1. 先写 MySQL
    memoryMapper.insert(memory);
    
    try {
        // 2. 再写 Milvus
        String embeddingId = vectorStoreService.saveGroupMemory(memory);
        
        // 3. 更新 embedding_id
        memory.setEmbeddingId(embeddingId);
        memoryMapper.updateEmbeddingId(memory.getId(), embeddingId);
        
    } catch (Exception e) {
        log.error("保存到 Milvus 失败", e);
        // 记录到补偿队列
    }
}
```

### 异步记忆提取

```java
@Async
public void extractAndSaveMemories(
    String groupId,
    String customerId,
    String userMessage,
    String aiResponse
) {
    // 1. 调用 DeepSeek 提取
    MemoryExtractionResult result = deepSeekService.extractMemories(userMessage, aiResponse);
    
    // 2. 过滤低置信度
    // 3. 去重检查
    // 4. 保存到 MySQL + Milvus
}
```

---

## 验证清单

- [ ] 模块结构完整
- [ ] Spring AI 配置正确
- [ ] Milvus 连接成功
- [ ] 向量生成功能正常
- [ ] 向量检索功能正常
- [ ] 记忆提取功能正常
- [ ] 消息队列监听正常
- [ ] 服务注册到 Nacos
- [ ] 所有更改已提交

---

## 下一步

**Plan 4: ai-chat-service** - 实现 AI 聊天协调服务
