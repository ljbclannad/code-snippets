# 会话上下文管理详细设计

**父文档**: [AI对话流程重构设计方案](./2026-05-18-ai-chat-flow-refactor-design.md)

---

## 1. 概述

会话上下文（SessionContext）是新流程的核心组件，用于管理多轮对话的状态、意图和历史摘要。

### 1.1 设计目标

- **连贯性**：理解多轮对话的上下文关系
- **智能性**：自动提取意图、实体和关键数据
- **高效性**：Redis缓存，30分钟TTL
- **容错性**：DeepSeek失败时使用默认上下文

### 1.2 核心特性

- **滑动窗口**：动态保留3-5轮对话摘要
- **意图分析**：DeepSeek自动提取用户意图
- **复杂度判断**：AI判断对话复杂度，动态调整窗口大小
- **异步更新**：不阻塞主流程

---

## 2. 数据结构

### 2.1 SessionContext

```java
package com.lx.governance.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 会话上下文
 * 存储多轮对话的状态、意图和历史摘要
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionContext {
    
    // ========== 会话标识 ==========
    
    /**
     * 会话ID
     */
    private String conversationId;
    
    /**
     * 用户ID
     */
    private String customerId;
    
    /**
     * 群组ID
     */
    private String groupId;
    
    // ========== 当前会话状态 ==========
    
    /**
     * 当前话题
     * 例如："血糖管理"、"运动方案"、"饮食建议"
     */
    private String currentTopic;
    
    /**
     * 对话阶段
     * - consulting: 咨询中（活跃对话）
     * - idle: 空闲（长时间未回复）
     */
    private String conversationPhase;
    
    // ========== 关键信息提取 ==========
    
    /**
     * 提到的实体
     * 例如：["血糖", "运动", "饭后"]
     */
    private List<String> mentionedEntities;
    
    /**
     * 提取的数据
     * 例如：{"血糖值": "7.2", "运动时长": "30分钟"}
     */
    private Map<String, String> extractedData;
    
    // ========== 最近消息摘要 ==========
    
    /**
     * 最近消息摘要（仅用户消息）
     * 动态3-5条，根据复杂度调整
     */
    private List<MessageSummary> recentSummaries;
    
    // ========== 用户意图 ==========
    
    /**
     * 用户意图
     * 例如："咨询运动建议"、"了解饮食禁忌"
     */
    private String userIntent;
    
    /**
     * 待回答问题
     * 例如：["饭后多久运动", "能吃什么水果"]
     */
    private List<String> pendingQuestions;
    
    // ========== 复杂度判断 ==========
    
    /**
     * 对话复杂度（AI判断）
     * - simple: 简单对话（保留3轮摘要）
     * - complex: 复杂对话（保留5轮摘要）
     */
    private String complexity;
    
    // ========== 会话元数据 ==========
    
    /**
     * 会话开始时间（毫秒时间戳）
     */
    private Long sessionStartTime;
    
    /**
     * 最后更新时间（毫秒时间戳）
     */
    private Long lastUpdateTime;
    
    /**
     * 消息计数
     */
    private Integer messageCount;
}
```

### 2.2 MessageSummary

```java
package com.lx.governance.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 消息摘要
 * 仅保留用户消息的摘要（50字限制）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MessageSummary {
    
    /**
     * 用户消息摘要（50字以内）
     */
    private String userMessage;
    
    /**
     * 消息时间戳（毫秒）
     */
    private Long timestamp;
}
```

---

## 3. SessionContextService

### 3.1 核心方法

```java
package com.lx.governance.aichat.service;

import com.alibaba.fastjson.JSON;
import com.lx.governance.aichat.dto.MessageSummary;
import com.lx.governance.aichat.dto.SessionContext;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 会话上下文服务
 * 负责会话上下文的创建、获取、更新
 */
@Service
@Slf4j
public class SessionContextService {
    
    @Autowired
    private RedisTemplate<String, String> redisTemplate;
    
    @Autowired
    private DeepSeekIntentAnalyzer intentAnalyzer;
    
    private static final String CONTEXT_KEY_PREFIX = "ai:session:context:";
    private static final int TTL_MINUTES = 30;
    
    /**
     * 获取或创建会话上下文（带降级）
     * 
     * @param conversationId 会话ID
     * @param customerId 用户ID
     * @param groupId 群组ID
     * @param currentMessage 当前消息
     * @return 会话上下文
     */
    @CircuitBreaker(name = "sessionContext", fallbackMethod = "getContextFallback")
    @Retry(name = "sessionContext", fallbackMethod = "getContextFallback")
    public SessionContext getOrCreateContext(
            String conversationId, 
            String customerId,
            String groupId,
            String currentMessage) {
        
        String key = CONTEXT_KEY_PREFIX + conversationId;
        
        try {
            SessionContext context = getFromRedis(key);
            
            if (context == null) {
                log.info("会话上下文不存在，创建新上下文: conversationId={}", conversationId);
                
                // 调用DeepSeek分析意图（带超时控制）
                context = intentAnalyzer.analyzeIntentWithTimeout(
                    currentMessage, 
                    Collections.emptyList(), // 无历史
                    5000 // 5秒超时
                );
                
                if (context == null) {
                    // DeepSeek失败，使用默认上下文
                    log.warn("DeepSeek意图分析失败，使用默认上下文: conversationId={}", conversationId);
                    context = createDefaultContext(conversationId, customerId, groupId);
                }
                
                // 设置会话标识
                context.setConversationId(conversationId);
                context.setCustomerId(customerId);
                context.setGroupId(groupId);
                context.setSessionStartTime(System.currentTimeMillis());
                context.setMessageCount(1);
                
                // 保存到Redis
                saveToRedis(key, context);
            } else {
                log.debug("从Redis获取会话上下文: conversationId={}", conversationId);
            }
            
            return context;
            
        } catch (Exception e) {
            log.error("获取会话上下文失败，使用降级策略: conversationId={}", conversationId, e);
            return createDefaultContext(conversationId, customerId, groupId);
        }
    }
    
    /**
     * 更新会话上下文
     * 
     * @param conversationId 会话ID
     * @param userMessage 用户消息
     * @param aiResponse AI回复
     */
    public void updateContext(
            String conversationId,
            String userMessage,
            String aiResponse) {
        
        String key = CONTEXT_KEY_PREFIX + conversationId;
        SessionContext context = getFromRedis(key);
        
        if (context == null) {
            log.warn("上下文不存在，跳过更新: conversationId={}", conversationId);
            return;
        }
        
        try {
            // 1. 添加新消息摘要
            MessageSummary summary = new MessageSummary();
            summary.setUserMessage(truncate(userMessage, 50));
            summary.setTimestamp(System.currentTimeMillis());
            
            List<MessageSummary> summaries = context.getRecentSummaries();
            if (summaries == null) {
                summaries = new ArrayList<>();
            }
            summaries.add(summary);
            
            // 2. 调用DeepSeek重新分析意图（带历史上下文）
            SessionContext updated = intentAnalyzer.analyzeIntentWithTimeout(
                userMessage,
                summaries,
                5000 // 5秒超时
            );
            
            if (updated == null) {
                log.warn("意图分析失败，保持原上下文: conversationId={}", conversationId);
                updated = context;
            }
            
            // 3. 根据复杂度动态调整窗口大小
            int windowSize = "complex".equals(updated.getComplexity()) ? 5 : 3;
            if (summaries.size() > windowSize) {
                summaries = summaries.subList(summaries.size() - windowSize, summaries.size());
            }
            updated.setRecentSummaries(summaries);
            
            // 4. 更新元数据
            updated.setConversationId(conversationId);
            updated.setCustomerId(context.getCustomerId());
            updated.setGroupId(context.getGroupId());
            updated.setSessionStartTime(context.getSessionStartTime());
            updated.setLastUpdateTime(System.currentTimeMillis());
            updated.setMessageCount(context.getMessageCount() + 1);
            
            // 5. 保存到Redis
            saveToRedis(key, updated);
            
            log.info("会话上下文更新成功: conversationId={}, messageCount={}, complexity={}", 
                conversationId, updated.getMessageCount(), updated.getComplexity());
            
        } catch (Exception e) {
            log.error("更新会话上下文失败: conversationId={}", conversationId, e);
            // 不抛出异常，避免影响主流程
        }
    }
    
    /**
     * 异步更新上下文（不阻塞主流程）
     * 
     * @param conversationId 会话ID
     * @param userMessage 用户消息
     * @param aiResponse AI回复
     * @return CompletableFuture
     */
    @Async("contextUpdateExecutor")
    public CompletableFuture<Void> updateContextAsync(
            String conversationId,
            String userMessage,
            String aiResponse) {
        
        return CompletableFuture.runAsync(() -> {
            try {
                updateContext(conversationId, userMessage, aiResponse);
            } catch (Exception e) {
                log.error("异步更新上下文失败: conversationId={}", conversationId, e);
                // 不抛出异常，避免影响主流程
            }
        });
    }
    
    /**
     * 删除会话上下文
     * 
     * @param conversationId 会话ID
     */
    public void deleteContext(String conversationId) {
        String key = CONTEXT_KEY_PREFIX + conversationId;
        redisTemplate.delete(key);
        log.info("删除会话上下文: conversationId={}", conversationId);
    }
    
    // ========== 私有方法 ==========
    
    /**
     * 从Redis获取上下文
     */
    private SessionContext getFromRedis(String key) {
        String json = redisTemplate.opsForValue().get(key);
        if (json == null) {
            return null;
        }
        return JSON.parseObject(json, SessionContext.class);
    }
    
    /**
     * 保存到Redis
     */
    private void saveToRedis(String key, SessionContext context) {
        redisTemplate.opsForValue().set(
            key, 
            JSON.toJSONString(context),
            TTL_MINUTES,
            TimeUnit.MINUTES
        );
    }
    
    /**
     * 降级方法：返回默认上下文
     */
    private SessionContext getContextFallback(
            String conversationId, 
            String customerId,
            String groupId,
            String currentMessage,
            Throwable t) {
        
        log.warn("会话上下文服务降级: conversationId={}, error={}", 
            conversationId, t.getMessage());
        
        return createDefaultContext(conversationId, customerId, groupId);
    }
    
    /**
     * 创建默认上下文（不依赖DeepSeek）
     */
    private SessionContext createDefaultContext(
            String conversationId,
            String customerId, 
            String groupId) {
        
        return SessionContext.builder()
            .conversationId(conversationId)
            .customerId(customerId)
            .groupId(groupId)
            .currentTopic("健康咨询")
            .conversationPhase("consulting")
            .mentionedEntities(new ArrayList<>())
            .extractedData(new HashMap<>())
            .userIntent("一般咨询")
            .pendingQuestions(new ArrayList<>())
            .complexity("simple")
            .recentSummaries(new ArrayList<>())
            .sessionStartTime(System.currentTimeMillis())
            .messageCount(1)
            .build();
    }
    
    /**
     * 截断文本到指定长度
     */
    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}
```

---

## 4. DeepSeekIntentAnalyzer

### 4.1 意图分析服务

```java
package com.lx.governance.aichat.service;

import com.alibaba.fastjson.JSON;
import com.lx.governance.aichat.dto.MessageSummary;
import com.lx.governance.aichat.dto.SessionContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.*;

/**
 * DeepSeek意图分析器
 * 调用DeepSeek API分析用户意图，返回结构化SessionContext
 */
@Service
@Slf4j
public class DeepSeekIntentAnalyzer {
    
    @Autowired
    private ChatClient chatClient;
    
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    
    /**
     * 分析用户意图（带超时控制）
     * 
     * @param currentMessage 当前消息
     * @param recentSummaries 最近对话摘要
     * @param timeoutMs 超时时间（毫秒）
     * @return SessionContext，超时或失败返回null
     */
    public SessionContext analyzeIntentWithTimeout(
            String currentMessage,
            List<MessageSummary> recentSummaries,
            long timeoutMs) {
        
        Future<SessionContext> future = executorService.submit(() -> 
            analyzeIntent(currentMessage, recentSummaries)
        );
        
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("意图分析超时: timeout={}ms", timeoutMs);
            future.cancel(true);
            return null;
        } catch (Exception e) {
            log.error("意图分析失败", e);
            return null;
        }
    }
    
    /**
     * 分析用户意图，返回结构化SessionContext
     * 
     * @param currentMessage 当前消息
     * @param recentSummaries 最近对话摘要
     * @return SessionContext
     */
    public SessionContext analyzeIntent(
            String currentMessage,
            List<MessageSummary> recentSummaries) {
        
        long startTime = System.currentTimeMillis();
        
        try {
            String prompt = buildIntentAnalysisPrompt(currentMessage, recentSummaries);
            
            String response = chatClient.prompt()
                .user(prompt)
                .call()
                .content();
            
            // 解析JSON响应
            SessionContext context = JSON.parseObject(response, SessionContext.class);
            
            long cost = System.currentTimeMillis() - startTime;
            log.info("意图分析完成: cost={}ms, complexity={}", cost, context.getComplexity());
            
            return context;
            
        } catch (Exception e) {
            log.error("意图分析失败", e);
            return null;
        }
    }
    
    /**
     * 构建意图分析Prompt
     */
    private String buildIntentAnalysisPrompt(
            String currentMessage,
            List<MessageSummary> recentSummaries) {
        
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个意图分析助手。请分析用户的对话意图，返回JSON格式。\n\n");
        
        // 添加历史对话摘要
        if (recentSummaries != null && !recentSummaries.isEmpty()) {
            prompt.append("## 最近对话摘要\n");
            for (MessageSummary summary : recentSummaries) {
                prompt.append("- ").append(summary.getUserMessage()).append("\n");
            }
            prompt.append("\n");
        }
        
        // 当前用户消息
        prompt.append("## 当前用户消息\n");
        prompt.append(currentMessage).append("\n\n");
        
        // 输出要求
        prompt.append("## 输出要求\n");
        prompt.append("返回JSON格式，包含以下字段：\n");
        prompt.append("{\n");
        prompt.append("  \"currentTopic\": \"当前话题（例如：血糖管理、运动方案）\",\n");
        prompt.append("  \"conversationPhase\": \"consulting或idle\",\n");
        prompt.append("  \"mentionedEntities\": [\"提到的实体列表，例如：血糖、运动、饭后\"],\n");
        prompt.append("  \"extractedData\": {\"键\":\"值\", \"例如\":\"血糖值: 7.2\"},\n");
        prompt.append("  \"userIntent\": \"用户意图描述（例如：咨询运动建议）\",\n");
        prompt.append("  \"pendingQuestions\": [\"待回答问题列表\"],\n");
        prompt.append("  \"complexity\": \"simple或complex（根据对话复杂度判断）\"\n");
        prompt.append("}\n\n");
        
        // 判断标准
        prompt.append("## 复杂度判断标准\n");
        prompt.append("- simple: 单一话题、实体少于3个、问题明确\n");
        prompt.append("- complex: 多个话题、实体多于3个、需要多轮澄清\n");
        
        return prompt.toString();
    }
}
```

---

## 5. 配置

### 5.1 线程池配置

```java
package com.lx.governance.aichat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {
    
    /**
     * 上下文更新线程池
     */
    @Bean("contextUpdateExecutor")
    public Executor contextUpdateExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("context-update-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
```

### 5.2 Resilience4j配置

```yaml
resilience4j:
  circuitbreaker:
    instances:
      sessionContext:
        failure-rate-threshold: 50
        slow-call-rate-threshold: 50
        slow-call-duration-threshold: 5s
        wait-duration-in-open-state: 60s
        permitted-number-of-calls-in-half-open-state: 3
        sliding-window-size: 10
        minimum-number-of-calls: 5
  
  retry:
    instances:
      sessionContext:
        max-attempts: 3
        wait-duration: 500ms
        exponential-backoff-multiplier: 2
```

---

## 6. 使用示例

### 6.1 创建会话上下文

```java
// 首次对话
SessionContext context = sessionContextService.getOrCreateContext(
    "conv-001",
    "user-001",
    "group-001",
    "我最近血糖有点高"
);

// 输出：
// {
//   "conversationId": "conv-001",
//   "currentTopic": "血糖管理",
//   "conversationPhase": "consulting",
//   "mentionedEntities": ["血糖"],
//   "extractedData": {},
//   "userIntent": "咨询血糖问题",
//   "pendingQuestions": [],
//   "complexity": "simple",
//   "recentSummaries": [],
//   "messageCount": 1
// }
```

### 6.2 更新会话上下文

```java
// 第二轮对话
sessionContextService.updateContextAsync(
    "conv-001",
    "那我应该怎么控制饮食呢",
    "建议您采用低GI饮食..."
);

// 更新后：
// {
//   "currentTopic": "血糖管理",
//   "userIntent": "咨询饮食控制方案",
//   "mentionedEntities": ["血糖", "饮食"],
//   "recentSummaries": [
//     {"userMessage": "我最近血糖有点高", "timestamp": 1234567890},
//     {"userMessage": "那我应该怎么控制饮食呢", "timestamp": 1234567900}
//   ],
//   "complexity": "simple",
//   "messageCount": 2
// }
```

---

## 7. 测试用例

### 7.1 单元测试

```java
@SpringBootTest
class SessionContextServiceTest {
    
    @Autowired
    private SessionContextService sessionContextService;
    
    @Test
    void testCreateContext() {
        SessionContext context = sessionContextService.getOrCreateContext(
            "test-conv-001",
            "test-user-001",
            "test-group-001",
            "我想了解如何控制血糖"
        );
        
        assertNotNull(context);
        assertEquals("test-conv-001", context.getConversationId());
        assertEquals("consulting", context.getConversationPhase());
        assertNotNull(context.getCurrentTopic());
    }
    
    @Test
    void testUpdateContext() {
        // 创建上下文
        SessionContext context = sessionContextService.getOrCreateContext(
            "test-conv-002",
            "test-user-002",
            "test-group-002",
            "我想减肥"
        );
        
        // 更新上下文
        sessionContextService.updateContext(
            "test-conv-002",
            "那我应该怎么运动",
            "建议您每周进行3-5次有氧运动..."
        );
        
        // 验证更新
        SessionContext updated = sessionContextService.getOrCreateContext(
            "test-conv-002",
            "test-user-002",
            "test-group-002",
            "那我应该怎么运动"
        );
        
        assertEquals(2, updated.getMessageCount());
        assertEquals(2, updated.getRecentSummaries().size());
    }
}
```

---

## 8. 监控指标

```java
// 上下文缓存命中率
ai.context.cache.hit / (ai.context.cache.hit + ai.context.cache.miss)

// 意图分析耗时
ai.context.analysis.time (P50/P95/P99)

// 上下文更新失败率
ai.context.update.errors / ai.context.update.total
```

---

## 9. 常见问题

**Q1: DeepSeek意图分析失败怎么办？**  
A: 使用默认上下文降级，不影响主流程。默认上下文包含基本字段，可以正常完成对话。

**Q2: 会话上下文的TTL为什么是30分钟？**  
A: 基于用户行为分析，30分钟内的对话属于同一会话。超过30分钟视为新会话。

**Q3: 如何处理高并发场景下的Redis竞争？**  
A: 使用Redis的原子操作（SET NX），避免并发写入冲突。

**Q4: 滑动窗口的大小如何确定？**  
A: 由AI判断复杂度：simple保留3轮，complex保留5轮。可根据实际效果调整。
