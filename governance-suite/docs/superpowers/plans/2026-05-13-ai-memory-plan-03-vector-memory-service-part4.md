# AI 记忆功能实施计划 - Plan 3: vector-memory-service (Part 4/4)

> 继续 Part 3

## Task 13: 实现消息监听器

**Files:**
- Create: `vector-memory-service/src/main/java/com/lx/governance/vectormemory/listener/VectorMemoryMessageListener.java`

- [ ] **Step 1: 创建 listener 目录**

```bash
mkdir -p vector-memory-service/src/main/java/com/lx/governance/vectormemory/listener
```

- [ ] **Step 2: 创建 VectorMemoryMessageListener**

```bash
cat > vector-memory-service/src/main/java/com/lx/governance/vectormemory/listener/VectorMemoryMessageListener.java << 'JAVAEOF'
package com.lx.governance.vectormemory.listener;

import com.lx.governance.common.trace.Trace;
import com.lx.governance.vectormemory.dto.*;
import com.lx.governance.vectormemory.service.MemoryExtractionService;
import com.lx.governance.vectormemory.service.MemoryRetrievalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import java.util.List;

/**
 * 向量记忆消息监听器
 * 处理记忆检索请求和记忆提取请求
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorMemoryMessageListener {
    
    private final MemoryRetrievalService memoryRetrievalService;
    private final MemoryExtractionService memoryExtractionService;
    private final RabbitTemplate rabbitTemplate;
    
    /**
     * 处理记忆检索请求
     */
    @RabbitListener(queues = "vector.memory.request")
    public void handleMemoryRequest(MemoryRequestMessage request) {
        String correlationId = request.getCorrelationId();
        String groupId = request.getGroupId();
        String customerId = request.getCustomerId();
        String query = request.getQuery();
        Integer topK = request.getTopK() != null ? request.getTopK() : 5;
        
        Trace.setTraceId(correlationId);
        
        log.info("[{}] 收到记忆检索请求: groupId={}, customerId={}, query={}, topK={}", 
            correlationId, groupId, customerId, query, topK);
        
        MemoryResponseMessage response = new MemoryResponseMessage();
        response.setCorrelationId(correlationId);
        response.setTimestamp(System.currentTimeMillis());
        
        try {
            List<MemoryItemDTO> memories = memoryRetrievalService.retrieveGroupMemories(
                query, groupId, customerId, topK
            );
            
            response.setSuccess(true);
            response.setData(memories);
            
            log.info("[{}] 记忆检索成功: count={}", correlationId, memories.size());
            
        } catch (Exception e) {
            log.error("[{}] 记忆检索失败: groupId={}, customerId={}", 
                correlationId, groupId, customerId, e);
            response.setSuccess(false);
            response.setErrorMessage("记忆检索失败: " + e.getMessage());
        }
        
        // 发送响应
        String responseRoutingKey = "vector.memory.response." + correlationId;
        rabbitTemplate.convertAndSend(
            "vector.memory.exchange",
            responseRoutingKey,
            response
        );
        
        log.info("[{}] 记忆检索响应已发送", correlationId);
    }
    
    /**
     * 处理记忆提取请求
     */
    @RabbitListener(queues = "memory.extraction.queue")
    public void handleExtractionRequest(ExtractionRequestMessage request) {
        String correlationId = request.getCorrelationId();
        String groupId = request.getGroupId();
        String customerId = request.getCustomerId();
        
        Trace.setTraceId(correlationId);
        
        log.info("[{}] 收到记忆提取请求: groupId={}, customerId={}", 
            correlationId, groupId, customerId);
        
        try {
            // 异步提取记忆
            memoryExtractionService.extractAndSaveMemories(
                groupId,
                customerId,
                request.getUserMessage(),
                request.getAiResponse()
            );
            
            log.info("[{}] 记忆提取任务已提交", correlationId);
            
        } catch (Exception e) {
            log.error("[{}] 记忆提取任务提交失败: groupId={}, customerId={}", 
                correlationId, groupId, customerId, e);
        }
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

## Task 14: 添加日志配置

**Files:**
- Create: `vector-memory-service/src/main/resources/logback-spring.xml`

- [ ] **Step 1: 创建 logback-spring.xml**

```bash
cat > vector-memory-service/src/main/resources/logback-spring.xml << 'XMLEOF'
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <property name="LOG_PATH" value="logs"/>
    <property name="APP_NAME" value="vector-memory-service"/>
    
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [%X{traceId}] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    
    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>${LOG_PATH}/${APP_NAME}.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>${LOG_PATH}/${APP_NAME}.%d{yyyy-MM-dd}.log</fileNamePattern>
            <maxHistory>30</maxHistory>
        </rollingPolicy>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [%X{traceId}] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    
    <logger name="com.lx.governance.vectormemory" level="INFO"/>
    <logger name="org.springframework.ai" level="DEBUG"/>
    
    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="FILE"/>
    </root>
</configuration>
XMLEOF
```

---

## Task 15: 构建和启动测试

**Files:**
- None (verification only)

- [ ] **Step 1: 完整构建模块**

```bash
mvn -pl vector-memory-service clean package -DskipTests
```

Expected: BUILD SUCCESS

- [ ] **Step 2: 启动服务**

```bash
mvn -pl vector-memory-service spring-boot:run &
```

- [ ] **Step 3: 等待服务启动**

```bash
sleep 30
```

- [ ] **Step 4: 检查服务注册到 Nacos**

```bash
curl -s http://localhost:8848/nacos/v1/ns/instance/list?serviceName=vector-memory-service | grep -q "vector-memory-service"
```

Expected: 命令成功（退出码 0）

- [ ] **Step 5: 检查健康状态**

```bash
curl -s http://localhost:8102/actuator/health | grep -q "UP"
```

Expected: 输出包含 "UP"

- [ ] **Step 6: 测试 Milvus 连接**

查看日志，确认 Milvus 连接成功：

```bash
tail -f logs/vector-memory-service.log | grep -i "milvus"
```

Expected: 看到 Milvus 连接成功的日志

- [ ] **Step 7: 停止服务**

```bash
pkill -f vector-memory-service
```

---

## Task 16: 单元测试

**Files:**
- Create: `vector-memory-service/src/test/java/com/lx/governance/vectormemory/service/EmbeddingServiceTest.java`
- Create: `vector-memory-service/src/test/java/com/lx/governance/vectormemory/service/VectorStoreServiceTest.java`

- [ ] **Step 1: 创建测试目录**

```bash
mkdir -p vector-memory-service/src/test/java/com/lx/governance/vectormemory/service
```

- [ ] **Step 2: 创建 EmbeddingServiceTest**

```bash
cat > vector-memory-service/src/test/java/com/lx/governance/vectormemory/service/EmbeddingServiceTest.java << 'JAVAEOF'
package com.lx.governance.vectormemory.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class EmbeddingServiceTest {
    
    @Autowired
    private EmbeddingService embeddingService;
    
    @Test
    void testGenerateEmbedding() {
        String text = "我喜欢清淡饮食，不吃辣";
        
        float[] embedding = embeddingService.generateEmbedding(text);
        
        assertNotNull(embedding);
        assertEquals(1024, embedding.length);
        
        System.out.println("Embedding dimension: " + embedding.length);
        System.out.println("First 5 values: ");
        for (int i = 0; i < 5; i++) {
            System.out.printf("  [%d] = %.6f\n", i, embedding[i]);
        }
    }
    
    @Test
    void testCosineSimilarity() {
        String text1 = "我喜欢清淡饮食";
        String text2 = "我喜欢清淡的食物";
        String text3 = "我喜欢运动健身";
        
        float[] embedding1 = embeddingService.generateEmbedding(text1);
        float[] embedding2 = embeddingService.generateEmbedding(text2);
        float[] embedding3 = embeddingService.generateEmbedding(text3);
        
        double similarity12 = embeddingService.cosineSimilarity(embedding1, embedding2);
        double similarity13 = embeddingService.cosineSimilarity(embedding1, embedding3);
        
        System.out.printf("相似度 (text1 vs text2): %.4f\n", similarity12);
        System.out.printf("相似度 (text1 vs text3): %.4f\n", similarity13);
        
        assertTrue(similarity12 > similarity13, "相似文本的相似度应该更高");
    }
}
JAVAEOF
```

- [ ] **Step 3: 创建 VectorStoreServiceTest**

```bash
cat > vector-memory-service/src/test/java/com/lx/governance/vectormemory/service/VectorStoreServiceTest.java << 'JAVAEOF'
package com.lx.governance.vectormemory.service;

import com.lx.governance.vectormemory.entity.AiGroupMemoryEntity;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class VectorStoreServiceTest {
    
    @Autowired
    private VectorStoreService vectorStoreService;
    
    @Test
    void testSaveAndSearchGroupMemory() {
        // 1. 保存记忆
        AiGroupMemoryEntity memory = new AiGroupMemoryEntity();
        memory.setId(1L);
        memory.setGroupId("test-group-001");
        memory.setCustomerId("test-customer-001");
        memory.setMemoryContent("我对海鲜过敏");
        memory.setConfidenceScore(BigDecimal.valueOf(0.95));
        memory.setVerified(1);
        
        String embeddingId = vectorStoreService.saveGroupMemory(memory);
        assertNotNull(embeddingId);
        System.out.println("Saved embedding ID: " + embeddingId);
        
        // 2. 等待索引
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        
        // 3. 检索记忆
        List<Document> results = vectorStoreService.searchGroupMemories(
            "过敏信息",
            "test-group-001",
            "test-customer-001",
            5
        );
        
        assertNotNull(results);
        System.out.println("Search results: " + results.size());
        
        for (Document doc : results) {
            System.out.println("  Content: " + doc.getContent());
            System.out.println("  Metadata: " + doc.getMetadata());
        }
    }
}
JAVAEOF
```

- [ ] **Step 4: 运行测试**

```bash
mvn -pl vector-memory-service test
```

Expected: 测试通过

---

## 验证清单

完成所有任务后，验证以下内容：

- [ ] vector-memory-service 模块结构完整
- [ ] 所有 Entity、DTO、Mapper 类已创建
- [ ] Spring AI 配置正确（Milvus、通义千问、DeepSeek）
- [ ] EmbeddingService 实现完整
- [ ] VectorStoreService 实现完整
- [ ] DeepSeekService 实现完整
- [ ] MemoryRetrievalService 实现完整
- [ ] MemoryExtractionService 实现完整
- [ ] 消息监听器实现完整
- [ ] 服务可以成功启动
- [ ] 服务注册到 Nacos
- [ ] Milvus 连接成功
- [ ] 向量生成功能正常
- [ ] 向量检索功能正常
- [ ] 单元测试通过
- [ ] 所有更改已提交到 Git

---

## 关键实现要点总结

### 1. Spring AI 集成

```yaml
spring:
  ai:
    vectorstore:
      milvus:
        client:
          host: localhost
          port: 19530
    alibaba:
      dashscope:
        api-key: ${DASHSCOPE_API_KEY}
    openai:
      api-key: ${DEEPSEEK_API_KEY}
      base-url: https://api.deepseek.com/v1
```

### 2. 双写策略

```java
// 先写 MySQL
memoryMapper.insert(memory);

// 再写 Milvus
String embeddingId = vectorStoreService.saveGroupMemory(memory);

// 更新 embedding_id
memoryMapper.updateEmbeddingId(memory.getId(), embeddingId);
```

### 3. 向量检索流程

```
用户查询 → 生成 Embedding → Milvus 检索 → 获取 embedding_id 
→ MySQL 查询完整信息 → 返回结果
```

### 4. 记忆提取流程

```
对话内容 → DeepSeek 提取 → 过滤低置信度 → 去重检查 
→ 保存到 MySQL → 保存到 Milvus
```

---

## 下一步

vector-memory-service 完成后，继续执行：

**Plan 4: ai-chat-service** - 实现 AI 聊天协调服务
