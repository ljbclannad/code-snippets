# AI 记忆功能设计文档 - 第三部分之二：vector-memory-service 组件设计（上）

## 文档信息

- **创建日期**: 2026-05-13
- **项目**: governance-suite
- **服务**: vector-memory-service
- **端口**: 8102
- **部分**: Spring AI 集成组件

## 一、服务职责概述

vector-memory-service 是整个记忆系统的核心，负责：

1. **向量生成**：使用阿里云通义千问 Embedding API
2. **向量存储**：使用 Milvus 存储和检索向量
3. **语义检索**：根据用户问题检索相关记忆
4. **记忆提取**：使用 DeepSeek API 从对话中提取信息
5. **数据双写**：MySQL + Milvus 双写保证数据完整性

本文档（上）主要介绍 Spring AI 集成的基础组件。

## 二、Spring AI 配置

### 2.1 依赖配置

**pom.xml**

```xml
<dependencies>
    <!-- Spring AI Core -->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-core</artifactId>
    </dependency>
    
    <!-- Spring AI Milvus Vector Store -->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-milvus-store-spring-boot-starter</artifactId>
    </dependency>
    
    <!-- Spring AI Alibaba (通义千问) -->
    <dependency>
        <groupId>com.alibaba.cloud.ai</groupId>
        <artifactId>spring-ai-alibaba-starter</artifactId>
    </dependency>
    
    <!-- Spring AI OpenAI (DeepSeek 兼容) -->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-openai-spring-boot-starter</artifactId>
    </dependency>
</dependencies>
```

---

### 2.2 application.yml 配置

```yaml
server:
  port: 8102

spring:
  application:
    name: vector-memory-service
  
  cloud:
    nacos:
      discovery:
        server-addr: localhost:8848
  
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest
  
  # Spring AI Milvus 配置
  ai:
    vectorstore:
      milvus:
        client:
          host: localhost
          port: 19530
        database-name: default
        embedding-dimension: 1024
        index-type: IVF_FLAT
        metric-type: COSINE
    
    # 阿里云通义千问 Embedding
    alibaba:
      dashscope:
        api-key: ${DASHSCOPE_API_KEY}
        embedding:
          model: text-embedding-v2
          options:
            dimension: 1024
    
    # DeepSeek (通过 OpenAI 兼容接口)
    openai:
      api-key: ${DEEPSEEK_API_KEY}
      base-url: https://api.deepseek.com/v1
      chat:
        options:
          model: deepseek-chat
          temperature: 0.7
          max-tokens: 2000

mybatis:
  mapper-locations: classpath:mapper/*.xml
  configuration:
    map-underscore-to-camel-case: true

logging:
  level:
    com.lx.governance.memory: INFO
    org.springframework.ai: DEBUG
    io.milvus: INFO
```

---

## 三、核心组件设计

### 3.1 EmbeddingService

**职责**：封装 Spring AI 的 EmbeddingModel，提供向量生成服务

**代码设计**：

```java
package com.lx.governance.memory.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {
    
    private final EmbeddingModel embeddingModel;  // Spring AI 自动注入
    
    /**
     * 生成单个文本的向量
     * 
     * @param text 文本内容
     * @return 1024维向量
     */
    public float[] generateEmbedding(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("文本不能为空");
        }
        
        long startTime = System.currentTimeMillis();
        
        try {
            log.debug("开始生成向量: textLength={}", text.length());
            
            EmbeddingResponse response = embeddingModel.embedForResponse(List.of(text));
            float[] embedding = response.getResults().get(0).getOutput();
            
            long cost = System.currentTimeMillis() - startTime;
            log.debug("向量生成完成: dimension={}, cost={}ms", embedding.length, cost);
            
            return embedding;
            
        } catch (Exception e) {
            log.error("生成向量失败: text={}", text.substring(0, Math.min(100, text.length())), e);
            throw new RuntimeException("生成向量失败", e);
        }
    }
    
    /**
     * 批量生成向量
     * 
     * @param texts 文本列表
     * @return 向量列表
     */
    public List<float[]> generateEmbeddings(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            throw new IllegalArgumentException("文本列表不能为空");
        }
        
        long startTime = System.currentTimeMillis();
        
        try {
            log.debug("开始批量生成向量: count={}", texts.size());
            
            EmbeddingResponse response = embeddingModel.embedForResponse(texts);
            List<float[]> embeddings = response.getResults().stream()
                .map(result -> result.getOutput())
                .collect(Collectors.toList());
            
            long cost = System.currentTimeMillis() - startTime;
            log.debug("批量向量生成完成: count={}, cost={}ms", embeddings.size(), cost);
            
            return embeddings;
            
        } catch (Exception e) {
            log.error("批量生成向量失败: count={}", texts.size(), e);
            throw new RuntimeException("批量生成向量失败", e);
        }
    }
    
    /**
     * 计算两个向量的余弦相似度
     * 
     * @param vec1 向量1
     * @param vec2 向量2
     * @return 相似度 [0, 1]
     */
    public double cosineSimilarity(float[] vec1, float[] vec2) {
        if (vec1.length != vec2.length) {
            throw new IllegalArgumentException("向量维度不匹配");
        }
        
        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;
        
        for (int i = 0; i < vec1.length; i++) {
            dotProduct += vec1[i] * vec2[i];
            norm1 += vec1[i] * vec1[i];
            norm2 += vec2[i] * vec2[i];
        }
        
        return dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }
}
```

---

### 3.2 MilvusVectorStoreConfig

**职责**：配置 Spring AI 的 Milvus VectorStore

**代码设计**：

```java
package com.lx.governance.memory.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.MilvusVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class MilvusVectorStoreConfig {
    
    @Value("${spring.ai.vectorstore.milvus.client.host}")
    private String milvusHost;
    
    @Value("${spring.ai.vectorstore.milvus.client.port}")
    private int milvusPort;
    
    @Value("${spring.ai.vectorstore.milvus.database-name}")
    private String databaseName;
    
    /**
     * 创建 Milvus 客户端
     */
    @Bean
    public MilvusServiceClient milvusClient() {
        log.info("初始化 Milvus 客户端: host={}, port={}", milvusHost, milvusPort);
        
        return new MilvusServiceClient(
            ConnectParam.newBuilder()
                .withHost(milvusHost)
                .withPort(milvusPort)
                .withDatabaseName(databaseName)
                .build()
        );
    }
    
    /**
     * 创建群组记忆的 VectorStore
     */
    @Bean(name = "groupMemoryVectorStore")
    public VectorStore groupMemoryVectorStore(
        MilvusServiceClient milvusClient,
        EmbeddingModel embeddingModel
    ) {
        log.info("初始化群组记忆 VectorStore");
        
        MilvusVectorStore.MilvusVectorStoreConfig config = 
            MilvusVectorStore.MilvusVectorStoreConfig.builder()
                .withCollectionName("ai_group_memories")
                .withDatabaseName(databaseName)
                .withIndexType(MilvusVectorStore.IndexType.IVF_FLAT)
                .withMetricType(MilvusVectorStore.MetricType.COSINE)
                .withIndexParameters("{\"nlist\": 128}")
                .build();
        
        return new MilvusVectorStore(milvusClient, embeddingModel, config, true);
    }
    
    /**
     * 创建用户全局记忆的 VectorStore
     */
    @Bean(name = "userMemoryVectorStore")
    public VectorStore userMemoryVectorStore(
        MilvusServiceClient milvusClient,
        EmbeddingModel embeddingModel
    ) {
        log.info("初始化用户全局记忆 VectorStore");
        
        MilvusVectorStore.MilvusVectorStoreConfig config = 
            MilvusVectorStore.MilvusVectorStoreConfig.builder()
                .withCollectionName("ai_user_memories")
                .withDatabaseName(databaseName)
                .withIndexType(MilvusVectorStore.IndexType.IVF_FLAT)
                .withMetricType(MilvusVectorStore.MetricType.COSINE)
                .withIndexParameters("{\"nlist\": 128}")
                .build();
        
        return new MilvusVectorStore(milvusClient, embeddingModel, config, true);
    }
}
```

---

### 3.3 VectorStoreService

**职责**：封装 Spring AI 的 VectorStore，提供统一的向量存储和检索接口

**代码设计**：

```java
package com.lx.governance.memory.service;

import com.lx.governance.memory.entity.AiGroupMemoryEntity;
import com.lx.governance.memory.entity.AiUserMemoryEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class VectorStoreService {
    
    private final VectorStore groupMemoryVectorStore;
    private final VectorStore userMemoryVectorStore;
    
    public VectorStoreService(
        @Qualifier("groupMemoryVectorStore") VectorStore groupMemoryVectorStore,
        @Qualifier("userMemoryVectorStore") VectorStore userMemoryVectorStore
    ) {
        this.groupMemoryVectorStore = groupMemoryVectorStore;
        this.userMemoryVectorStore = userMemoryVectorStore;
    }
    
    /**
     * 保存群组记忆到向量库
     * 
     * @param memory 群组记忆实体
     * @return 向量ID
     */
    @Retryable(
        value = {Exception.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 1000)
    )
    public String saveGroupMemory(AiGroupMemoryEntity memory) {
        String embeddingId = UUID.randomUUID().toString();
        
        try {
            log.debug("保存群组记忆到向量库: embeddingId={}, customerId={}, groupId={}", 
                embeddingId, memory.getCustomerId(), memory.getGroupId());
            
            // 构建元数据
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("customer_id", memory.getCustomerId());
            metadata.put("group_id", memory.getGroupId());
            metadata.put("memory_type", memory.getMemoryType());
            metadata.put("memory_key", memory.getMemoryKey());
            metadata.put("timestamp", memory.getExtractedAt());
            
            // 构建 Document
            Document document = Document.builder()
                .id(embeddingId)
                .content(memory.getMemoryContent())
                .metadata(metadata)
                .build();
            
            // 保存到 Milvus
            groupMemoryVectorStore.add(List.of(document));
            
            log.info("群组记忆保存成功: embeddingId={}", embeddingId);
            return embeddingId;
            
        } catch (Exception e) {
            log.error("保存群组记忆失败: embeddingId={}", embeddingId, e);
            throw new RuntimeException("保存群组记忆失败", e);
        }
    }
    
    /**
     * 保存用户全局记忆到向量库
     * 
     * @param memory 用户全局记忆实体
     * @return 向量ID
     */
    @Retryable(
        value = {Exception.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 1000)
    )
    public String saveUserMemory(AiUserMemoryEntity memory) {
        String embeddingId = UUID.randomUUID().toString();
        
        try {
            log.debug("保存用户全局记忆到向量库: embeddingId={}, customerId={}", 
                embeddingId, memory.getCustomerId());
            
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("customer_id", memory.getCustomerId());
            metadata.put("memory_type", memory.getMemoryType());
            metadata.put("memory_key", memory.getMemoryKey());
            metadata.put("verified", memory.getVerified() == 1);
            metadata.put("timestamp", System.currentTimeMillis());
            
            Document document = Document.builder()
                .id(embeddingId)
                .content(memory.getMemoryContent())
                .metadata(metadata)
                .build();
            
            userMemoryVectorStore.add(List.of(document));
            
            log.info("用户全局记忆保存成功: embeddingId={}", embeddingId);
            return embeddingId;
            
        } catch (Exception e) {
            log.error("保存用户全局记忆失败: embeddingId={}", embeddingId, e);
            throw new RuntimeException("保存用户全局记忆失败", e);
        }
    }
    
    /**
     * 检索群组记忆
     * 
     * @param query 查询文本
     * @param customerId 用户ID
     * @param groupId 群组ID
     * @param topK 返回Top-K结果
     * @return 相关文档列表
     */
    public List<Document> searchGroupMemories(
        String query,
        String customerId,
        String groupId,
        int topK
    ) {
        long startTime = System.currentTimeMillis();
        
        try {
            log.debug("开始检索群组记忆: query={}, customerId={}, groupId={}, topK={}", 
                query, customerId, groupId, topK);
            
            // 构建过滤表达式
            Filter.Expression filterExpression = new FilterExpressionBuilder()
                .eq("customer_id", customerId)
                .and()
                .eq("group_id", groupId)
                .build();
            
            // 构建搜索请求
            SearchRequest searchRequest = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .filterExpression(filterExpression)
                .build();
            
            // 执行检索
            List<Document> results = groupMemoryVectorStore.similaritySearch(searchRequest);
            
            long cost = System.currentTimeMillis() - startTime;
            log.info("群组记忆检索完成: resultCount={}, cost={}ms", results.size(), cost);
            
            return results;
            
        } catch (Exception e) {
            log.error("检索群组记忆失败: query={}, customerId={}, groupId={}", 
                query, customerId, groupId, e);
            throw new RuntimeException("检索群组记忆失败", e);
        }
    }
    
    /**
     * 检索用户全局记忆
     * 
     * @param query 查询文本
     * @param customerId 用户ID
     * @param topK 返回Top-K结果
     * @return 相关文档列表
     */
    public List<Document> searchUserMemories(
        String query,
        String customerId,
        int topK
    ) {
        long startTime = System.currentTimeMillis();
        
        try {
            log.debug("开始检索用户全局记忆: query={}, customerId={}, topK={}", 
                query, customerId, topK);
            
            Filter.Expression filterExpression = new FilterExpressionBuilder()
                .eq("customer_id", customerId)
                .and()
                .eq("verified", true)  // 只检索已验证的记忆
                .build();
            
            SearchRequest searchRequest = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .filterExpression(filterExpression)
                .build();
            
            List<Document> results = userMemoryVectorStore.similaritySearch(searchRequest);
            
            long cost = System.currentTimeMillis() - startTime;
            log.info("用户全局记忆检索完成: resultCount={}, cost={}ms", results.size(), cost);
            
            return results;
            
        } catch (Exception e) {
            log.error("检索用户全局记忆失败: query={}, customerId={}", query, customerId, e);
            throw new RuntimeException("检索用户全局记忆失败", e);
        }
    }
    
    /**
     * 删除群组记忆
     * 
     * @param embeddingId 向量ID
     */
    public void deleteGroupMemory(String embeddingId) {
        try {
            log.info("删除群组记忆: embeddingId={}", embeddingId);
            groupMemoryVectorStore.delete(List.of(embeddingId));
        } catch (Exception e) {
            log.error("删除群组记忆失败: embeddingId={}", embeddingId, e);
            throw new RuntimeException("删除群组记忆失败", e);
        }
    }
    
    /**
     * 删除用户全局记忆
     * 
     * @param embeddingId 向量ID
     */
    public void deleteUserMemory(String embeddingId) {
        try {
            log.info("删除用户全局记忆: embeddingId={}", embeddingId);
            userMemoryVectorStore.delete(List.of(embeddingId));
        } catch (Exception e) {
            log.error("删除用户全局记忆失败: embeddingId={}", embeddingId, e);
            throw new RuntimeException("删除用户全局记忆失败", e);
        }
    }
}
```

---

## 四、重试和异常处理

### 4.1 重试配置

**RetryConfig**

```java
package com.lx.governance.memory.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;

@Configuration
@EnableRetry
public class RetryConfig {
    // Spring Retry 自动配置
}
```

---

### 4.2 全局异常处理

**GlobalExceptionHandler**

```java
package com.lx.governance.memory.exception;

import com.lx.governance.common.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    
    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResponse<Void> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("参数错误: {}", e.getMessage());
        return ApiResponse.fail(400, e.getMessage());
    }
    
    @ExceptionHandler(RuntimeException.class)
    public ApiResponse<Void> handleRuntimeException(RuntimeException e) {
        log.error("运行时异常", e);
        return ApiResponse.fail(500, "服务异常: " + e.getMessage());
    }
    
    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleException(Exception e) {
        log.error("未知异常", e);
        return ApiResponse.fail(500, "系统错误");
    }
}
```

---

## 五、总结

本文档（上）详细设计了 vector-memory-service 中基于 Spring AI 的核心组件：

1. **EmbeddingService**：封装通义千问 Embedding API
2. **MilvusVectorStoreConfig**：配置 Milvus VectorStore
3. **VectorStoreService**：提供统一的向量存储和检索接口
4. **重试和异常处理**：保证服务的健壮性

下一步将在第三部分之三中设计记忆检索和提取的业务逻辑组件。
