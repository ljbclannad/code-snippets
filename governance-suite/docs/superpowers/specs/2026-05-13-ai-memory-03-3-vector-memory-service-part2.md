# AI 记忆功能设计文档 - 第三部分之三：vector-memory-service 组件设计（下）

## 文档信息

- **创建日期**: 2026-05-13
- **项目**: governance-suite
- **服务**: vector-memory-service
- **端口**: 8102
- **部分**: 业务逻辑组件

## 一、概述

本文档（下）介绍 vector-memory-service 的业务逻辑组件，包括：

1. **MemoryRetrievalService**：记忆检索服务
2. **MemoryExtractionService**：记忆提取服务
3. **DeepSeekService**：DeepSeek API 调用服务
4. **VectorMemoryMessageListener**：消息队列监听器

## 二、MemoryRetrievalService

**职责**：提供记忆检索的业务逻辑，整合向量检索和 MySQL 查询

**代码设计**：

```java
package com.lx.governance.memory.service;

import com.lx.governance.memory.dto.MemoryItemDTO;
import com.lx.governance.memory.entity.AiGroupMemoryEntity;
import com.lx.governance.memory.mapper.AiGroupMemoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryRetrievalService {
    
    private final VectorStoreService vectorStoreService;
    private final AiGroupMemoryMapper groupMemoryMapper;
    
    /**
     * 检索群组记忆
     * 
     * @param groupId 群组ID
     * @param customerId 用户ID
     * @param query 用户问题
     * @param topK 返回Top-K个结果
     * @return 相关记忆列表
     */
    public List<MemoryItemDTO> retrieveGroupMemories(
        String groupId,
        String customerId,
        String query,
        int topK
    ) {
        long startTime = System.currentTimeMillis();
        
        try {
            log.info("开始检索群组记忆: groupId={}, customerId={}, query={}, topK={}", 
                groupId, customerId, query, topK);
            
            // 1. 向量检索
            List<Document> documents = vectorStoreService.searchGroupMemories(
                query, customerId, groupId, topK
            );
            
            if (documents.isEmpty()) {
                log.info("未检索到相关记忆");
                return Collections.emptyList();
            }
            
            // 2. 提取 embedding_id 列表
            List<String> embeddingIds = documents.stream()
                .map(Document::getId)
                .collect(Collectors.toList());
            
            // 3. 从 MySQL 查询完整信息
            List<AiGroupMemoryEntity> memories = groupMemoryMapper.selectByEmbeddingIds(embeddingIds);
            
            // 4. 转换为 DTO（保持向量检索的顺序）
            List<MemoryItemDTO> result = documents.stream()
                .map(doc -> {
                    String embeddingId = doc.getId();
                    AiGroupMemoryEntity memory = memories.stream()
                        .filter(m -> embeddingId.equals(m.getEmbeddingId()))
                        .findFirst()
                        .orElse(null);
                    
                    if (memory == null) {
                        log.warn("向量存在但MySQL中无记录: embeddingId={}", embeddingId);
                        return null;
                    }
                    
                    return toDTO(memory, doc);
                })
                .filter(dto -> dto != null)
                .collect(Collectors.toList());
            
            long cost = System.currentTimeMillis() - startTime;
            log.info("群组记忆检索完成: resultCount={}, cost={}ms", result.size(), cost);
            
            return result;
            
        } catch (Exception e) {
            log.error("检索群组记忆失败: groupId={}, customerId={}", groupId, customerId, e);
            throw new RuntimeException("检索群组记忆失败", e);
        }
    }
    
    /**
     * 检查是否存在相似记忆（用于去重）
     * 
     * @param groupId 群组ID
     * @param customerId 用户ID
     * @param content 记忆内容
     * @param threshold 相似度阈值（默认0.95）
     * @return 是否存在相似记忆
     */
    public boolean hasSimilarMemory(
        String groupId,
        String customerId,
        String content,
        double threshold
    ) {
        try {
            // 检索最相似的1条记忆
            List<Document> documents = vectorStoreService.searchGroupMemories(
                content, customerId, groupId, 1
            );
            
            if (documents.isEmpty()) {
                return false;
            }
            
            // 检查相似度（Spring AI 的 Document 包含 score）
            Document topResult = documents.get(0);
            Double score = topResult.getMetadata().get("score", Double.class);
            
            if (score != null && score >= threshold) {
                log.info("发现相似记忆: embeddingId={}, score={}", topResult.getId(), score);
                return true;
            }
            
            return false;
            
        } catch (Exception e) {
            log.error("检查相似记忆失败", e);
            return false;  // 失败时不阻塞保存
        }
    }
    
    /**
     * 转换为 DTO
     */
    private MemoryItemDTO toDTO(AiGroupMemoryEntity memory, Document document) {
        return MemoryItemDTO.builder()
            .id(memory.getId())
            .memoryType(memory.getMemoryType())
            .memoryKey(memory.getMemoryKey())
            .memoryContent(memory.getMemoryContent())
            .confidenceScore(memory.getConfidenceScore())
            .extractedAt(memory.getExtractedAt())
            .createTime(memory.getCreateTime())
            .build();
    }
}
```

---

## 三、DeepSeekService

**职责**：调用 DeepSeek API 进行信息提取

**代码设计**：

```java
package com.lx.governance.memory.service;

import com.lx.governance.memory.dto.MemoryExtractionResult;
import com.lx.governance.memory.dto.MemoryItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.ChatClient;
import org.springframework.ai.chat.ChatResponse;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionRequest.ResponseFormat;
import org.springframework.stereotype.Service;
import com.alibaba.fastjson2.JSON;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeepSeekService {
    
    private final ChatClient chatClient;  // Spring AI 自动注入
    
    /**
     * 从对话中提取记忆
     * 
     * @param userMessage 用户消息
     * @param aiResponse AI回复
     * @return 提取结果
     */
    public MemoryExtractionResult extractMemories(String userMessage, String aiResponse) {
        long startTime = System.currentTimeMillis();
        
        try {
            log.info("开始提取记忆: userMessageLength={}, aiResponseLength={}", 
                userMessage.length(), aiResponse.length());
            
            // 1. 构建提取 prompt
            String prompt = buildExtractionPrompt(userMessage, aiResponse);
            
            // 2. 调用 DeepSeek API（JSON 模式）
            Message message = new UserMessage(prompt);
            
            ChatResponse response = chatClient.call(
                new Prompt(
                    List.of(message),
                    OpenAiChatOptions.builder()
                        .withResponseFormat(new ResponseFormat(ResponseFormat.Type.JSON_OBJECT))
                        .withTemperature(0.7)
                        .withMaxTokens(2000)
                        .build()
                )
            );
            
            String jsonResult = response.getResult().getOutput().getContent();
            
            // 3. 解析 JSON
            MemoryExtractionResult result = JSON.parseObject(jsonResult, MemoryExtractionResult.class);
            
            long cost = System.currentTimeMillis() - startTime;
            log.info("记忆提取完成: extractedCount={}, cost={}ms", 
                result.getMemories().size(), cost);
            
            return result;
            
        } catch (Exception e) {
            log.error("提取记忆失败", e);
            throw new RuntimeException("提取记忆失败", e);
        }
    }
    
    /**
     * 构建提取 prompt
     */
    private String buildExtractionPrompt(String userMessage, String aiResponse) {
        return String.format("""
            你是一个医疗信息提取助手。请从以下对话中提取重要的用户信息。
            
            用户消息：%s
            AI回复：%s
            
            请提取以下类型的信息（如果存在）：
            1. 症状描述 (SYMPTOM)
            2. 过敏史 (ALLERGY)
            3. 用药信息 (MEDICATION)
            4. 饮食偏好/禁忌 (DIET)
            5. 个人偏好 (PREFERENCE)
            6. 其他重要信息 (OTHER)
            
            以JSON格式返回，格式如下：
            {
              "memories": [
                {
                  "type": "SYMPTOM",
                  "key": "胃部不适",
                  "content": "用户提到最近一周胃部不适，饭后有胀气感",
                  "confidence": 0.95
                }
              ]
            }
            
            注意事项：
            1. confidence 是置信度分数，范围 0-1
            2. key 是简短的关键词，content 是完整描述
            3. 只提取明确的、有价值的信息
            4. 如果没有提取到任何信息，返回空数组
            5. 不要编造信息，只提取对话中明确提到的内容
            
            请严格按照JSON格式返回，不要包含其他文字。
            """, userMessage, aiResponse);
    }
}
```

---

## 四、MemoryExtractionService

**职责**：记忆提取的业务逻辑，包括去重、保存到 MySQL 和 Milvus

**代码设计**：

```java
package com.lx.governance.memory.service;

import com.lx.governance.memory.dto.MemoryExtractionResult;
import com.lx.governance.memory.dto.MemoryItem;
import com.lx.governance.memory.entity.AiGroupMemoryEntity;
import com.lx.governance.memory.mapper.AiGroupMemoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryExtractionService {
    
    private final DeepSeekService deepSeekService;
    private final VectorStoreService vectorStoreService;
    private final MemoryRetrievalService memoryRetrievalService;
    private final AiGroupMemoryMapper groupMemoryMapper;
    
    /**
     * 从对话中提取并保存记忆（异步）
     * 
     * @param groupId 群组ID
     * @param customerId 用户ID
     * @param userMessage 用户消息
     * @param aiResponse AI回复
     */
    @Async
    public void extractAndSaveMemories(
        String groupId,
        String customerId,
        String userMessage,
        String aiResponse
    ) {
        long startTime = System.currentTimeMillis();
        
        try {
            log.info("开始异步提取记忆: groupId={}, customerId={}", groupId, customerId);
            
            // 1. 调用 DeepSeek 提取信息
            MemoryExtractionResult result = deepSeekService.extractMemories(userMessage, aiResponse);
            
            if (result.getMemories() == null || result.getMemories().isEmpty()) {
                log.info("未提取到任何记忆");
                return;
            }
            
            // 2. 保存每条记忆
            int savedCount = 0;
            for (MemoryItem item : result.getMemories()) {
                try {
                    // 检查置信度
                    if (item.getConfidence() < 0.7) {
                        log.debug("置信度过低，跳过: key={}, confidence={}", 
                            item.getKey(), item.getConfidence());
                        continue;
                    }
                    
                    // 去重检查
                    if (memoryRetrievalService.hasSimilarMemory(
                        groupId, customerId, item.getContent(), 0.95
                    )) {
                        log.info("发现重复记忆，跳过: key={}", item.getKey());
                        continue;
                    }
                    
                    // 保存记忆
                    saveMemory(groupId, customerId, item, userMessage);
                    savedCount++;
                    
                } catch (Exception e) {
                    log.error("保存单条记忆失败: key={}", item.getKey(), e);
                    // 继续处理其他记忆
                }
            }
            
            long cost = System.currentTimeMillis() - startTime;
            log.info("记忆提取完成: extractedCount={}, savedCount={}, cost={}ms", 
                result.getMemories().size(), savedCount, cost);
            
        } catch (Exception e) {
            log.error("提取记忆失败: groupId={}, customerId={}", groupId, customerId, e);
        }
    }
    
    /**
     * 保存单条记忆（事务）
     */
    @Transactional(rollbackFor = Exception.class)
    protected void saveMemory(
        String groupId,
        String customerId,
        MemoryItem item,
        String sourceMessage
    ) {
        // 1. 构建实体
        AiGroupMemoryEntity entity = new AiGroupMemoryEntity();
        entity.setCustomerId(customerId);
        entity.setGroupId(groupId);
        entity.setMemoryType(item.getType());
        entity.setMemoryKey(item.getKey());
        entity.setMemoryContent(item.getContent());
        entity.setConfidenceScore(item.getConfidence());
        entity.setSourceMessage(sourceMessage);
        entity.setExtractedAt(System.currentTimeMillis());
        entity.setIsDeleted(0);
        
        // 2. 保存到 MySQL
        groupMemoryMapper.insert(entity);
        
        try {
            // 3. 保存到 Milvus
            String embeddingId = vectorStoreService.saveGroupMemory(entity);
            
            // 4. 更新 MySQL 中的 embedding_id
            entity.setEmbeddingId(embeddingId);
            groupMemoryMapper.updateEmbeddingId(entity.getId(), embeddingId);
            
            log.info("记忆保存成功: id={}, embeddingId={}, key={}", 
                entity.getId(), embeddingId, item.getKey());
            
        } catch (Exception e) {
            log.error("保存到 Milvus 失败，记录到补偿队列: id={}", entity.getId(), e);
            // 这里可以记录到补偿队列，后续重试
            // compensationService.recordFailedEmbedding(entity.getId());
        }
    }
}
```

---

## 五、VectorMemoryMessageListener

**职责**：监听 RabbitMQ 消息队列，处理向量检索请求和记忆提取请求

**代码设计**：

```java
package com.lx.governance.memory.listener;

import com.lx.governance.common.trace.Trace;
import com.lx.governance.memory.dto.*;
import com.lx.governance.memory.service.MemoryRetrievalService;
import com.lx.governance.memory.service.MemoryExtractionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class VectorMemoryMessageListener {
    
    private final MemoryRetrievalService memoryRetrievalService;
    private final MemoryExtractionService memoryExtractionService;
    private final RabbitTemplate rabbitTemplate;
    
    /**
     * 监听向量检索请求
     */
    @RabbitListener(queues = "vector.memory.request")
    public void handleMemoryRequest(MemoryRequestMessage request) {
        String correlationId = request.getCorrelationId();
        
        // 设置 traceId
        Trace.setTraceId(correlationId);
        
        log.info("[{}] 收到向量检索请求: groupId={}, customerId={}, topK={}", 
            correlationId, request.getGroupId(), request.getCustomerId(), request.getTopK());
        
        MemoryResponseMessage response = new MemoryResponseMessage();
        response.setCorrelationId(correlationId);
        response.setTimestamp(System.currentTimeMillis());
        
        try {
            // 检索记忆
            List<MemoryItemDTO> memories = memoryRetrievalService.retrieveGroupMemories(
                request.getGroupId(),
                request.getCustomerId(),
                request.getQuery(),
                request.getTopK()
            );
            
            response.setSuccess(true);
            response.setData(memories);
            
        } catch (Exception e) {
            log.error("[{}] 向量检索失败", correlationId, e);
            response.setSuccess(false);
            response.setErrorMessage("向量检索失败: " + e.getMessage());
        }
        
        // 发送响应
        String responseRoutingKey = "vector.memory.response." + correlationId;
        rabbitTemplate.convertAndSend(
            "vector.memory.exchange",
            responseRoutingKey,
            response
        );
        
        log.info("[{}] 向量检索响应已发送: success={}, resultCount={}", 
            correlationId, response.getSuccess(), 
            response.getData() != null ? response.getData().size() : 0);
    }
    
    /**
     * 监听记忆提取请求（异步）
     */
    @RabbitListener(queues = "memory.extraction.request")
    public void handleExtractionRequest(ExtractionRequestMessage request) {
        log.info("收到记忆提取请求: groupId={}, customerId={}", 
            request.getGroupId(), request.getCustomerId());
        
        try {
            // 异步提取记忆
            memoryExtractionService.extractAndSaveMemories(
                request.getGroupId(),
                request.getCustomerId(),
                request.getUserMessage(),
                request.getAiResponse()
            );
            
        } catch (Exception e) {
            log.error("处理记忆提取请求失败: groupId={}, customerId={}", 
                request.getGroupId(), request.getCustomerId(), e);
        }
    }
}
```

---

## 六、DTO 定义

### 6.1 MemoryItemDTO

```java
package com.lx.governance.memory.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryItemDTO {
    private Long id;
    private String memoryType;
    private String memoryKey;
    private String memoryContent;
    private BigDecimal confidenceScore;
    private Long extractedAt;
    private LocalDateTime createTime;
}
```

### 6.2 MemoryExtractionResult

```java
package com.lx.governance.memory.dto;

import lombok.Data;
import java.util.List;

@Data
public class MemoryExtractionResult {
    private List<MemoryItem> memories;
}
```

### 6.3 MemoryItem

```java
package com.lx.governance.memory.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class MemoryItem {
    private String type;
    private String key;
    private String content;
    private BigDecimal confidence;
}
```

### 6.4 消息模型

**MemoryRequestMessage**

```java
package com.lx.governance.memory.dto;

import lombok.Data;

@Data
public class MemoryRequestMessage {
    private String correlationId;
    private String groupId;
    private String customerId;
    private String query;
    private Integer topK;
    private Long timestamp;
}
```

**MemoryResponseMessage**

```java
package com.lx.governance.memory.dto;

import lombok.Data;
import java.util.List;

@Data
public class MemoryResponseMessage {
    private String correlationId;
    private Boolean success;
    private String errorMessage;
    private List<MemoryItemDTO> data;
    private Long timestamp;
}
```

**ExtractionRequestMessage**

```java
package com.lx.governance.memory.dto;

import lombok.Data;

@Data
public class ExtractionRequestMessage {
    private String groupId;
    private String customerId;
    private String userMessage;
    private String aiResponse;
    private Long timestamp;
}
```

---

## 七、异步配置

**AsyncConfig**

```java
package com.lx.governance.memory.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.context.annotation.Bean;

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
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
```

---

## 八、总结

本文档（下）详细设计了 vector-memory-service 的业务逻辑组件：

1. **MemoryRetrievalService**：整合向量检索和 MySQL 查询
2. **DeepSeekService**：调用 DeepSeek API 进行信息提取
3. **MemoryExtractionService**：记忆提取的完整流程（去重、保存）
4. **VectorMemoryMessageListener**：处理 MQ 消息
5. **异步配置**：支持异步记忆提取

至此，vector-memory-service 的所有核心组件设计完成。下一步将设计 ai-chat-service 的组件。
