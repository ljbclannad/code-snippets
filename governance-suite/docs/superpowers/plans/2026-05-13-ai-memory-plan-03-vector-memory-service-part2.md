# AI 记忆功能实施计划 - Plan 3: vector-memory-service (Part 2/4)

> 继续 Part 1

## Task 5: 配置 Spring AI - Milvus VectorStore

**Files:**
- Create: `vector-memory-service/src/main/java/com/lx/governance/vectormemory/config/MilvusVectorStoreConfig.java`

- [ ] **Step 1: 创建 config 目录**

```bash
mkdir -p vector-memory-service/src/main/java/com/lx/governance/vectormemory/config
```

- [ ] **Step 2: 创建 MilvusVectorStoreConfig**

```bash
cat > vector-memory-service/src/main/java/com/lx/governance/vectormemory/config/MilvusVectorStoreConfig.java << 'JAVAEOF'
package com.lx.governance.vectormemory.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.MilvusVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class MilvusVectorStoreConfig {
    
    @Value("${spring.ai.vectorstore.milvus.client.host:localhost}")
    private String milvusHost;
    
    @Value("${spring.ai.vectorstore.milvus.client.port:19530}")
    private int milvusPort;
    
    @Value("${spring.ai.vectorstore.milvus.database-name:default}")
    private String databaseName;
    
    @Value("${spring.ai.vectorstore.milvus.embedding-dimension:1024}")
    private int embeddingDimension;
    
    @Bean
    public MilvusServiceClient milvusClient() {
        return new MilvusServiceClient(
            ConnectParam.newBuilder()
                .withHost(milvusHost)
                .withPort(milvusPort)
                .withDatabaseName(databaseName)
                .build()
        );
    }
    
    /**
     * 群组记忆向量存储
     */
    @Bean
    @Primary
    public VectorStore groupMemoryVectorStore(
            MilvusServiceClient milvusClient,
            EmbeddingModel embeddingModel) {
        
        MilvusVectorStore.MilvusVectorStoreConfig config = 
            MilvusVectorStore.MilvusVectorStoreConfig.builder()
                .withCollectionName("ai_group_memories")
                .withDatabaseName(databaseName)
                .withIndexType(MilvusVectorStore.IndexType.IVF_FLAT)
                .withMetricType(MilvusVectorStore.MetricType.COSINE)
                .withEmbeddingDimension(embeddingDimension)
                .build();
        
        return new MilvusVectorStore(milvusClient, embeddingModel, config);
    }
    
    /**
     * 用户记忆向量存储
     */
    @Bean("userMemoryVectorStore")
    public VectorStore userMemoryVectorStore(
            MilvusServiceClient milvusClient,
            EmbeddingModel embeddingModel) {
        
        MilvusVectorStore.MilvusVectorStoreConfig config = 
            MilvusVectorStore.MilvusVectorStoreConfig.builder()
                .withCollectionName("ai_user_memories")
                .withDatabaseName(databaseName)
                .withIndexType(MilvusVectorStore.IndexType.IVF_FLAT)
                .withMetricType(MilvusVectorStore.MetricType.COSINE)
                .withEmbeddingDimension(embeddingDimension)
                .build();
        
        return new MilvusVectorStore(milvusClient, embeddingModel, config);
    }
}
JAVAEOF
```

- [ ] **Step 3: 验证编译**

```bash
mvn -pl vector-memory-service compile
```

Expected: BUILD SUCCESS

---

## Task 6: 配置 RabbitMQ

**Files:**
- Create: `vector-memory-service/src/main/java/com/lx/governance/vectormemory/config/RabbitMQConfig.java`

- [ ] **Step 1: 创建 RabbitMQConfig**

```bash
cat > vector-memory-service/src/main/java/com/lx/governance/vectormemory/config/RabbitMQConfig.java << 'JAVAEOF'
package com.lx.governance.vectormemory.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {
    
    // 向量记忆检索
    public static final String VECTOR_MEMORY_EXCHANGE = "vector.memory.exchange";
    public static final String VECTOR_MEMORY_REQUEST_QUEUE = "vector.memory.request";
    
    // 记忆提取
    public static final String MEMORY_EXTRACTION_EXCHANGE = "memory.extraction.exchange";
    public static final String MEMORY_EXTRACTION_QUEUE = "memory.extraction.queue";
    
    // 死信队列
    public static final String DLX_EXCHANGE = "dlx.exchange";
    public static final String DLX_QUEUE = "dlx.queue";
    
    @Bean
    public DirectExchange vectorMemoryExchange() {
        return new DirectExchange(VECTOR_MEMORY_EXCHANGE, true, false);
    }
    
    @Bean
    public Queue vectorMemoryRequestQueue() {
        return QueueBuilder.durable(VECTOR_MEMORY_REQUEST_QUEUE)
            .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
            .withArgument("x-message-ttl", 60000)
            .build();
    }
    
    @Bean
    public Binding vectorMemoryRequestBinding() {
        return BindingBuilder
            .bind(vectorMemoryRequestQueue())
            .to(vectorMemoryExchange())
            .with("vector.memory.request");
    }
    
    @Bean
    public DirectExchange memoryExtractionExchange() {
        return new DirectExchange(MEMORY_EXTRACTION_EXCHANGE, true, false);
    }
    
    @Bean
    public Queue memoryExtractionQueue() {
        return QueueBuilder.durable(MEMORY_EXTRACTION_QUEUE)
            .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
            .build();
    }
    
    @Bean
    public Binding memoryExtractionBinding() {
        return BindingBuilder
            .bind(memoryExtractionQueue())
            .to(memoryExtractionExchange())
            .with("memory.extraction");
    }
    
    @Bean
    public FanoutExchange dlxExchange() {
        return new FanoutExchange(DLX_EXCHANGE, true, false);
    }
    
    @Bean
    public Queue dlxQueue() {
        return QueueBuilder.durable(DLX_QUEUE).build();
    }
    
    @Bean
    public Binding dlxBinding() {
        return BindingBuilder
            .bind(dlxQueue())
            .to(dlxExchange());
    }
    
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
JAVAEOF
```

- [ ] **Step 2: 验证编译**

```bash
mvn -pl vector-memory-service compile
```

Expected: BUILD SUCCESS

---

## Task 7: 配置异步和重试

**Files:**
- Create: `vector-memory-service/src/main/java/com/lx/governance/vectormemory/config/AsyncConfig.java`
- Create: `vector-memory-service/src/main/java/com/lx/governance/vectormemory/config/RetryConfig.java`

- [ ] **Step 1: 创建 AsyncConfig**

```bash
cat > vector-memory-service/src/main/java/com/lx/governance/vectormemory/config/AsyncConfig.java << 'JAVAEOF'
package com.lx.governance.vectormemory.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {
    
    @Bean(name = "memoryExtractionExecutor")
    public Executor memoryExtractionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("memory-extraction-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
JAVAEOF
```

- [ ] **Step 2: 创建 RetryConfig**

```bash
cat > vector-memory-service/src/main/java/com/lx/governance/vectormemory/config/RetryConfig.java << 'JAVAEOF'
package com.lx.governance.vectormemory.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

@Configuration
@EnableRetry
public class RetryConfig {
    
    @Bean
    public RetryTemplate retryTemplate() {
        RetryTemplate retryTemplate = new RetryTemplate();
        
        // 重试策略：最多重试 3 次
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy();
        retryPolicy.setMaxAttempts(3);
        retryTemplate.setRetryPolicy(retryPolicy);
        
        // 退避策略：固定间隔 2 秒
        FixedBackOffPolicy backOffPolicy = new FixedBackOffPolicy();
        backOffPolicy.setBackOffPeriod(2000);
        retryTemplate.setBackOffPolicy(backOffPolicy);
        
        return retryTemplate;
    }
}
JAVAEOF
```

- [ ] **Step 3: 验证编译**

```bash
mvn -pl vector-memory-service compile
```

Expected: BUILD SUCCESS

---

## Task 8: 实现 EmbeddingService

**Files:**
- Create: `vector-memory-service/src/main/java/com/lx/governance/vectormemory/service/EmbeddingService.java`

- [ ] **Step 1: 创建 service 目录**

```bash
mkdir -p vector-memory-service/src/main/java/com/lx/governance/vectormemory/service
```

- [ ] **Step 2: 创建 EmbeddingService**

```bash
cat > vector-memory-service/src/main/java/com/lx/governance/vectormemory/service/EmbeddingService.java << 'JAVAEOF'
package com.lx.governance.vectormemory.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Embedding 服务
 * 封装 Spring AI 的 EmbeddingModel，提供文本向量化功能
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {
    
    private final EmbeddingModel embeddingModel;
    
    /**
     * 生成单个文本的向量
     */
    @Retryable(
        maxAttempts = 3,
        backoff = @Backoff(delay = 2000),
        retryFor = {Exception.class}
    )
    public float[] generateEmbedding(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("文本不能为空");
        }
        
        long startTime = System.currentTimeMillis();
        
        try {
            EmbeddingResponse response = embeddingModel.embedForResponse(List.of(text));
            float[] embedding = response.getResults().get(0).getOutput();
            
            long cost = System.currentTimeMillis() - startTime;
            log.info("生成 Embedding 成功: textLength={}, dimension={}, cost={}ms", 
                text.length(), embedding.length, cost);
            
            return embedding;
            
        } catch (Exception e) {
            log.error("生成 Embedding 失败: text={}", text, e);
            throw new RuntimeException("生成 Embedding 失败", e);
        }
    }
    
    /**
     * 批量生成文本向量
     */
    @Retryable(
        maxAttempts = 3,
        backoff = @Backoff(delay = 2000),
        retryFor = {Exception.class}
    )
    public List<float[]> generateEmbeddings(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            throw new IllegalArgumentException("文本列表不能为空");
        }
        
        long startTime = System.currentTimeMillis();
        
        try {
            EmbeddingResponse response = embeddingModel.embedForResponse(texts);
            List<float[]> embeddings = response.getResults().stream()
                .map(result -> result.getOutput())
                .collect(Collectors.toList());
            
            long cost = System.currentTimeMillis() - startTime;
            log.info("批量生成 Embedding 成功: count={}, cost={}ms", texts.size(), cost);
            
            return embeddings;
            
        } catch (Exception e) {
            log.error("批量生成 Embedding 失败: count={}", texts.size(), e);
            throw new RuntimeException("批量生成 Embedding 失败", e);
        }
    }
    
    /**
     * 计算余弦相似度
     */
    public double cosineSimilarity(float[] vectorA, float[] vectorB) {
        if (vectorA.length != vectorB.length) {
            throw new IllegalArgumentException("向量维度不匹配");
        }
        
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        
        for (int i = 0; i < vectorA.length; i++) {
            dotProduct += vectorA[i] * vectorB[i];
            normA += vectorA[i] * vectorA[i];
            normB += vectorB[i] * vectorB[i];
        }
        
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
JAVAEOF
```

- [ ] **Step 3: 验证编译**

```bash
mvn -pl vector-memory-service compile
```

Expected: BUILD SUCCESS

---

## Task 9: 实现 VectorStoreService

**Files:**
- Create: `vector-memory-service/src/main/java/com/lx/governance/vectormemory/service/VectorStoreService.java`

- [ ] **Step 1: 创建 VectorStoreService**

```bash
cat > vector-memory-service/src/main/java/com/lx/governance/vectormemory/service/VectorStoreService.java << 'JAVAEOF'
package com.lx.governance.vectormemory.service;

import com.lx.governance.vectormemory.entity.AiGroupMemoryEntity;
import lombok.RequiredArgsConstructor;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 向量存储服务
 * 封装 Spring AI 的 VectorStore，提供向量存储和检索功能
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorStoreService {
    
    private final VectorStore groupMemoryVectorStore;
    
    @Qualifier("userMemoryVectorStore")
    private final VectorStore userMemoryVectorStore;
    
    /**
     * 保存群组记忆到 Milvus
     */
    @Retryable(
        maxAttempts = 3,
        backoff = @Backoff(delay = 2000),
        retryFor = {Exception.class}
    )
    public String saveGroupMemory(AiGroupMemoryEntity memory) {
        String embeddingId = UUID.randomUUID().toString();
        
        Document document = Document.builder()
            .id(embeddingId)
            .content(memory.getMemoryContent())
            .metadata("group_id", memory.getGroupId())
            .metadata("customer_id", memory.getCustomerId())
            .metadata("db_id", memory.getId())
            .metadata("confidence_score", memory.getConfidenceScore())
            .metadata("verified", memory.getVerified())
            .build();
        
        try {
            groupMemoryVectorStore.add(List.of(document));
            log.info("保存群组记忆到 Milvus 成功: embeddingId={}, groupId={}, customerId={}", 
                embeddingId, memory.getGroupId(), memory.getCustomerId());
            return embeddingId;
            
        } catch (Exception e) {
            log.error("保存群组记忆到 Milvus 失败: groupId={}, customerId={}", 
                memory.getGroupId(), memory.getCustomerId(), e);
            throw new RuntimeException("保存到 Milvus 失败", e);
        }
    }
    
    /**
     * 保存用户记忆到 Milvus
     */
    @Retryable(
        maxAttempts = 3,
        backoff = @Backoff(delay = 2000),
        retryFor = {Exception.class}
    )
    public String saveUserMemory(String customerId, String content, Long dbId) {
        String embeddingId = UUID.randomUUID().toString();
        
        Document document = Document.builder()
            .id(embeddingId)
            .content(content)
            .metadata("customer_id", customerId)
            .metadata("db_id", dbId)
            .build();
        
        try {
            userMemoryVectorStore.add(List.of(document));
            log.info("保存用户记忆到 Milvus 成功: embeddingId={}, customerId={}", 
                embeddingId, customerId);
            return embeddingId;
            
        } catch (Exception e) {
            log.error("保存用户记忆到 Milvus 失败: customerId={}", customerId, e);
            throw new RuntimeException("保存到 Milvus 失败", e);
        }
    }
    
    /**
     * 检索群组记忆
     */
    public List<Document> searchGroupMemories(
            String query, 
            String groupId, 
            String customerId, 
            int topK) {
        
        try {
            // 构建过滤条件
            Filter filter = new FilterExpressionBuilder()
                .eq("group_id", groupId)
                .and()
                .eq("customer_id", customerId)
                .build();
            
            // 构建检索请求
            SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(0.7)
                .filter(filter)
                .build();
            
            List<Document> results = groupMemoryVectorStore.similaritySearch(request);
            
            log.info("检索群组记忆成功: groupId={}, customerId={}, query={}, results={}", 
                groupId, customerId, query, results.size());
            
            return results;
            
        } catch (Exception e) {
            log.error("检索群组记忆失败: groupId={}, customerId={}, query={}", 
                groupId, customerId, query, e);
            throw new RuntimeException("检索失败", e);
        }
    }
    
    /**
     * 检索用户记忆
     */
    public List<Document> searchUserMemories(String query, String customerId, int topK) {
        try {
            Filter filter = new FilterExpressionBuilder()
                .eq("customer_id", customerId)
                .build();
            
            SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(0.7)
                .filter(filter)
                .build();
            
            List<Document> results = userMemoryVectorStore.similaritySearch(request);
            
            log.info("检索用户记忆成功: customerId={}, query={}, results={}", 
                customerId, query, results.size());
            
            return results;
            
        } catch (Exception e) {
            log.error("检索用户记忆失败: customerId={}, query={}", customerId, query, e);
            throw new RuntimeException("检索失败", e);
        }
    }
    
    /**
     * 删除群组记忆
     */
    public void deleteGroupMemory(String embeddingId) {
        try {
            groupMemoryVectorStore.delete(List.of(embeddingId));
            log.info("删除群组记忆成功: embeddingId={}", embeddingId);
            
        } catch (Exception e) {
            log.error("删除群组记忆失败: embeddingId={}", embeddingId, e);
            throw new RuntimeException("删除失败", e);
        }
    }
    
    /**
     * 删除用户记忆
     */
    public void deleteUserMemory(String embeddingId) {
        try {
            userMemoryVectorStore.delete(List.of(embeddingId));
            log.info("删除用户记忆成功: embeddingId={}", embeddingId);
            
        } catch (Exception e) {
            log.error("删除用户记忆失败: embeddingId={}", embeddingId, e);
            throw new RuntimeException("删除失败", e);
        }
    }
}
JAVAEOF
```

- [ ] **Step 2: 验证编译**

```bash
mvn -pl vector-memory-service compile
```

Expected: BUILD SUCCESS

---

继续 Part 3...
