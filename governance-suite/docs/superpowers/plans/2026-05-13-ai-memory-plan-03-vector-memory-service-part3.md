# AI 记忆功能实施计划 - Plan 3: vector-memory-service (Part 3/4)

> 继续 Part 2

## Task 10: 实现 DeepSeekService

**Files:**
- Create: `vector-memory-service/src/main/java/com/lx/governance/vectormemory/service/DeepSeekService.java`

- [ ] **Step 1: 创建 DeepSeekService**

```bash
cat > vector-memory-service/src/main/java/com/lx/governance/vectormemory/service/DeepSeekService.java << 'JAVAEOF'
package com.lx.governance.vectormemory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.vectormemory.dto.MemoryExtractionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionRequest.ResponseFormat;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

/**
 * DeepSeek 服务
 * 使用 Spring AI 的 ChatModel 调用 DeepSeek API 进行记忆提取
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeepSeekService {
    
    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    
    /**
     * 从对话中提取记忆
     */
    @Retryable(
        maxAttempts = 3,
        backoff = @Backoff(delay = 2000),
        retryFor = {Exception.class}
    )
    public MemoryExtractionResult extractMemories(String userMessage, String aiResponse) {
        long startTime = System.currentTimeMillis();
        
        try {
            String prompt = buildExtractionPrompt(userMessage, aiResponse);
            
            ChatResponse response = chatModel.call(
                new Prompt(
                    prompt,
                    OpenAiChatOptions.builder()
                        .withResponseFormat(new ResponseFormat(ResponseFormat.Type.JSON_OBJECT))
                        .withTemperature(0.7)
                        .withMaxTokens(2000)
                        .build()
                )
            );
            
            String jsonResult = response.getResult().getOutput().getContent();
            MemoryExtractionResult result = objectMapper.readValue(
                jsonResult, 
                MemoryExtractionResult.class
            );
            
            long cost = System.currentTimeMillis() - startTime;
            log.info("记忆提取成功: extractedCount={}, cost={}ms", 
                result.getMemories().size(), cost);
            
            return result;
            
        } catch (Exception e) {
            log.error("记忆提取失败", e);
            throw new RuntimeException("记忆提取失败", e);
        }
    }
    
    /**
     * 构建记忆提取 Prompt
     */
    private String buildExtractionPrompt(String userMessage, String aiResponse) {
        return String.format("""
            你是一个专业的对话记忆提取助手。请从以下用户和AI的对话中提取值得记住的关键信息。
            
            用户消息：
            %s
            
            AI回复：
            %s
            
            请提取以下类型的记忆：
            1. 用户偏好（如饮食偏好、运动习惯等）
            2. 健康状况（如过敏、疾病、症状等）
            3. 个人目标（如减重目标、健康目标等）
            4. 重要事实（如家庭情况、工作情况等）
            
            要求：
            - 每条记忆应该简洁明确，不超过50字
            - 为每条记忆评估置信度（0.0-1.0）
            - 将记忆分类到合适的类别
            - 只提取明确的、有价值的信息，不要推测
            
            请以 JSON 格式返回结果：
            {
              "memories": [
                {
                  "content": "记忆内容",
                  "confidence": 0.95,
                  "category": "preference|health_condition|goal|fact"
                }
              ]
            }
            """, userMessage, aiResponse);
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

## Task 11: 实现 MemoryRetrievalService

**Files:**
- Create: `vector-memory-service/src/main/java/com/lx/governance/vectormemory/service/MemoryRetrievalService.java`

- [ ] **Step 1: 创建 MemoryRetrievalService**

```bash
cat > vector-memory-service/src/main/java/com/lx/governance/vectormemory/service/MemoryRetrievalService.java << 'JAVAEOF'
package com.lx.governance.vectormemory.service;

import com.lx.governance.vectormemory.dto.MemoryItemDTO;
import com.lx.governance.vectormemory.entity.AiGroupMemoryEntity;
import com.lx.governance.vectormemory.mapper.AiGroupMemoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 记忆检索服务
 * 整合向量检索和 MySQL 查询，返回完整的记忆信息
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryRetrievalService {
    
    private final VectorStoreService vectorStoreService;
    private final AiGroupMemoryMapper memoryMapper;
    
    /**
     * 检索群组记忆
     */
    public List<MemoryItemDTO> retrieveGroupMemories(
            String query,
            String groupId,
            String customerId,
            int topK) {
        
        long startTime = System.currentTimeMillis();
        
        try {
            // 1. 向量检索
            List<Document> documents = vectorStoreService.searchGroupMemories(
                query, groupId, customerId, topK
            );
            
            if (documents.isEmpty()) {
                log.info("未检索到相关记忆: groupId={}, customerId={}, query={}", 
                    groupId, customerId, query);
                return List.of();
            }
            
            // 2. 提取 embedding_id
            List<String> embeddingIds = documents.stream()
                .map(Document::getId)
                .collect(Collectors.toList());
            
            // 3. 从 MySQL 查询完整信息
            List<AiGroupMemoryEntity> memories = memoryMapper.selectByEmbeddingIds(embeddingIds);
            
            // 4. 构建 embedding_id -> similarity 映射
            Map<String, Double> similarityMap = documents.stream()
                .collect(Collectors.toMap(
                    Document::getId,
                    doc -> doc.getMetadata().get("distance") != null 
                        ? 1.0 - ((Number) doc.getMetadata().get("distance")).doubleValue()
                        : 0.0
                ));
            
            // 5. 转换为 DTO
            List<MemoryItemDTO> result = memories.stream()
                .map(memory -> MemoryItemDTO.builder()
                    .id(memory.getId())
                    .content(memory.getMemoryContent())
                    .similarity(similarityMap.getOrDefault(memory.getEmbeddingId(), 0.0))
                    .confidenceScore(memory.getConfidenceScore().doubleValue())
                    .verified(memory.getVerified() == 1)
                    .source(memory.getSource())
                    .build())
                .collect(Collectors.toList());
            
            long cost = System.currentTimeMillis() - startTime;
            log.info("检索群组记忆完成: groupId={}, customerId={}, count={}, cost={}ms", 
                groupId, customerId, result.size(), cost);
            
            return result;
            
        } catch (Exception e) {
            log.error("检索群组记忆失败: groupId={}, customerId={}, query={}", 
                groupId, customerId, query, e);
            throw new RuntimeException("检索记忆失败", e);
        }
    }
    
    /**
     * 检查是否存在相似记忆（去重）
     */
    public boolean hasSimilarMemory(String groupId, String customerId, String content) {
        try {
            int count = memoryMapper.countSimilarMemory(groupId, customerId, content);
            return count > 0;
            
        } catch (Exception e) {
            log.error("检查相似记忆失败: groupId={}, customerId={}", groupId, customerId, e);
            return false;
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

## Task 12: 实现 MemoryExtractionService

**Files:**
- Create: `vector-memory-service/src/main/java/com/lx/governance/vectormemory/service/MemoryExtractionService.java`

- [ ] **Step 1: 创建 MemoryExtractionService**

```bash
cat > vector-memory-service/src/main/java/com/lx/governance/vectormemory/service/MemoryExtractionService.java << 'JAVAEOF'
package com.lx.governance.vectormemory.service;

import com.lx.governance.vectormemory.dto.MemoryExtractionResult;
import com.lx.governance.vectormemory.entity.AiGroupMemoryEntity;
import com.lx.governance.vectormemory.mapper.AiGroupMemoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;

/**
 * 记忆提取服务
 * 从对话中异步提取记忆，保存到 MySQL 和 Milvus
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryExtractionService {
    
    private final DeepSeekService deepSeekService;
    private final VectorStoreService vectorStoreService;
    private final MemoryRetrievalService memoryRetrievalService;
    private final AiGroupMemoryMapper memoryMapper;
    
    /**
     * 异步提取并保存记忆
     */
    @Async("memoryExtractionExecutor")
    public void extractAndSaveMemories(
            String groupId,
            String customerId,
            String userMessage,
            String aiResponse) {
        
        long startTime = System.currentTimeMillis();
        
        try {
            log.info("开始提取记忆: groupId={}, customerId={}", groupId, customerId);
            
            // 1. 调用 DeepSeek 提取记忆
            MemoryExtractionResult result = deepSeekService.extractMemories(
                userMessage, aiResponse
            );
            
            if (result.getMemories() == null || result.getMemories().isEmpty()) {
                log.info("未提取到记忆: groupId={}, customerId={}", groupId, customerId);
                return;
            }
            
            // 2. 过滤低置信度记忆
            int savedCount = 0;
            for (MemoryExtractionResult.ExtractedMemory extracted : result.getMemories()) {
                if (extracted.getConfidence() < 0.7) {
                    log.debug("跳过低置信度记忆: content={}, confidence={}", 
                        extracted.getContent(), extracted.getConfidence());
                    continue;
                }
                
                // 3. 去重检查
                if (memoryRetrievalService.hasSimilarMemory(groupId, customerId, extracted.getContent())) {
                    log.debug("跳过重复记忆: content={}", extracted.getContent());
                    continue;
                }
                
                // 4. 保存记忆
                saveMemory(groupId, customerId, extracted, userMessage, aiResponse);
                savedCount++;
            }
            
            long cost = System.currentTimeMillis() - startTime;
            log.info("记忆提取完成: groupId={}, customerId={}, extracted={}, saved={}, cost={}ms",
                groupId, customerId, result.getMemories().size(), savedCount, cost);
            
        } catch (Exception e) {
            log.error("记忆提取失败: groupId={}, customerId={}", groupId, customerId, e);
        }
    }
    
    /**
     * 保存单条记忆（MySQL + Milvus 双写）
     */
    @Transactional
    public void saveMemory(
            String groupId,
            String customerId,
            MemoryExtractionResult.ExtractedMemory extracted,
            String userMessage,
            String aiResponse) {
        
        try {
            // 1. 构建实体
            AiGroupMemoryEntity memory = new AiGroupMemoryEntity();
            memory.setGroupId(groupId);
            memory.setCustomerId(customerId);
            memory.setMemoryContent(extracted.getContent());
            memory.setConfidenceScore(BigDecimal.valueOf(extracted.getConfidence()));
            memory.setSource("deepseek");
            memory.setExtractedFrom(String.format("User: %s\nAI: %s", 
                truncate(userMessage, 100), truncate(aiResponse, 100)));
            memory.setVerified(0);
            memory.setIsDeleted(0);
            
            // 2. 先写 MySQL
            memoryMapper.insert(memory);
            log.info("记忆已保存到 MySQL: id={}, content={}", 
                memory.getId(), memory.getMemoryContent());
            
            try {
                // 3. 再写 Milvus
                String embeddingId = vectorStoreService.saveGroupMemory(memory);
                
                // 4. 更新 embedding_id
                memoryMapper.updateEmbeddingId(memory.getId(), embeddingId);
                log.info("记忆已保存到 Milvus: id={}, embeddingId={}", 
                    memory.getId(), embeddingId);
                
            } catch (Exception e) {
                log.error("保存到 Milvus 失败，记录到补偿队列: id={}", memory.getId(), e);
                // TODO: 发送到补偿队列
            }
            
        } catch (Exception e) {
            log.error("保存记忆失败: groupId={}, customerId={}, content={}", 
                groupId, customerId, extracted.getContent(), e);
            throw new RuntimeException("保存记忆失败", e);
        }
    }
    
    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() > maxLength 
            ? text.substring(0, maxLength) + "..." 
            : text;
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

继续 Part 4...
