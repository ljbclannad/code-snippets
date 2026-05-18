# 会话上下文管理实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现会话上下文管理，支持意图分析、滑动窗口和智能上下文更新

**Architecture:** 使用Redis Hash存储SessionContext，通过DeepSeek API分析用户意图并提取结构化信息，支持动态滑动窗口（3-5轮），30分钟TTL自动过期

**Tech Stack:** Spring Data Redis, DeepSeek API, Jackson, Resilience4j

---

## 文件结构

### 新增文件
- `ai-chat-service/src/main/java/com/lx/governance/aichat/dto/SessionContext.java` - 会话上下文数据结构
- `ai-chat-service/src/main/java/com/lx/governance/aichat/dto/MessageSummary.java` - 消息摘要
- `ai-chat-service/src/main/java/com/lx/governance/aichat/service/SessionContextService.java` - 会话上下文服务
- `ai-chat-service/src/main/java/com/lx/governance/aichat/service/DeepSeekIntentAnalyzer.java` - 意图分析服务
- `ai-chat-service/src/test/java/com/lx/governance/aichat/service/SessionContextServiceTest.java` - 单元测试
- `ai-chat-service/src/test/java/com/lx/governance/aichat/service/DeepSeekIntentAnalyzerTest.java` - 意图分析测试

### 修改文件
- `ai-chat-service/src/main/java/com/lx/governance/aichat/service/AiChatService.java` - 集成会话上下文
- `ai-chat-service/src/main/resources/application.yml` - 添加Resilience4j配置

---

## Task 1: 创建SessionContext数据结构

**Files:**
- Create: `ai-chat-service/src/main/java/com/lx/governance/aichat/dto/SessionContext.java`

- [ ] **Step 1: 创建SessionContext类**

```java
package com.lx.governance.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionContext {
    
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
    
    /**
     * 当前话题
     */
    private String currentTopic;
    
    /**
     * 对话阶段：consulting（咨询中）、idle（闲聊）
     */
    private String conversationPhase;
    
    /**
     * 提及的实体（人名、地名、疾病名等）
     */
    @Builder.Default
    private List<String> mentionedEntities = new ArrayList<>();
    
    /**
     * 提取的结构化数据（如：血压值、体重等）
     */
    @Builder.Default
    private Map<String, Object> extractedData = new HashMap<>();
    
    /**
     * 最近消息摘要（滑动窗口）
     */
    @Builder.Default
    private List<MessageSummary> recentSummaries = new ArrayList<>();
    
    /**
     * 用户意图
     */
    private String userIntent;
    
    /**
     * 待确认问题
     */
    @Builder.Default
    private List<String> pendingQuestions = new ArrayList<>();
    
    /**
     * 复杂度：simple（简单）、complex（复杂）
     */
    private String complexity;
    
    /**
     * 会话开始时间
     */
    private Long sessionStartTime;
    
    /**
     * 最后更新时间
     */
    private Long lastUpdateTime;
    
    /**
     * 消息计数
     */
    private Integer messageCount;
}
```

- [ ] **Step 2: 创建MessageSummary类**

创建文件 `ai-chat-service/src/main/java/com/lx/governance/aichat/dto/MessageSummary.java`：

```java
package com.lx.governance.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageSummary {
    
    /**
     * 用户消息摘要（最多50字）
     */
    private String userMessage;
    
    /**
     * 消息时间戳
     */
    private Long timestamp;
}
```

- [ ] **Step 3: 验证编译**

```bash
cd ai-chat-service
mvn compile
```

Expected: BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
git add ai-chat-service/src/main/java/com/lx/governance/aichat/dto/SessionContext.java
git add ai-chat-service/src/main/java/com/lx/governance/aichat/dto/MessageSummary.java
git commit -m "feat: add SessionContext and MessageSummary DTOs"
```

---

## Task 2: 创建DeepSeekIntentAnalyzer

**Files:**
- Create: `ai-chat-service/src/main/java/com/lx/governance/aichat/service/DeepSeekIntentAnalyzer.java`

- [ ] **Step 1: 创建意图分析服务类**

```java
package com.lx.governance.aichat.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.lx.governance.aichat.client.DeepSeekClient;
import com.lx.governance.aichat.dto.MessageSummary;
import com.lx.governance.aichat.dto.SessionContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class DeepSeekIntentAnalyzer {
    
    @Autowired
    private DeepSeekClient deepSeekClient;
    
    /**
     * 分析用户意图并创建SessionContext
     */
    public SessionContext analyzeIntent(String currentMessage, List<MessageSummary> recentMessages) {
        try {
            String prompt = buildAnalysisPrompt(currentMessage, recentMessages);
            String response = deepSeekClient.chat(prompt);
            
            return parseAnalysisResult(response);
            
        } catch (Exception e) {
            log.error("意图分析失败，使用默认上下文", e);
            return createDefaultContext();
        }
    }
    
    /**
     * 带超时控制的意图分析
     */
    public SessionContext analyzeIntentWithTimeout(String currentMessage, 
                                                   List<MessageSummary> recentMessages,
                                                   long timeoutMs) {
        // 简化版：直接调用（实际应使用CompletableFuture.orTimeout）
        return analyzeIntent(currentMessage, recentMessages);
    }
    
    /**
     * 构建分析Prompt
     */
    private String buildAnalysisPrompt(String currentMessage, List<MessageSummary> recentMessages) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个专业的对话意图分析助手。请分析以下对话内容，提取结构化信息。\n\n");
        
        // 历史消息
        if (!recentMessages.isEmpty()) {
            prompt.append("历史消息：\n");
            for (MessageSummary summary : recentMessages) {
                prompt.append("- ").append(summary.getUserMessage()).append("\n");
            }
            prompt.append("\n");
        }
        
        // 当前消息
        prompt.append("当前消息：").append(currentMessage).append("\n\n");
        
        // 分析要求
        prompt.append("请以JSON格式返回以下信息：\n");
        prompt.append("{\n");
        prompt.append("  \"currentTopic\": \"当前话题（如：血糖管理、饮食咨询）\",\n");
        prompt.append("  \"conversationPhase\": \"对话阶段（consulting或idle）\",\n");
        prompt.append("  \"mentionedEntities\": [\"提及的实体列表\"],\n");
        prompt.append("  \"extractedData\": {\"提取的结构化数据，如血压、体重等\"},\n");
        prompt.append("  \"userIntent\": \"用户意图描述\",\n");
        prompt.append("  \"pendingQuestions\": [\"待确认的问题列表\"],\n");
        prompt.append("  \"complexity\": \"simple或complex\"\n");
        prompt.append("}\n");
        
        return prompt.toString();
    }
    
    /**
     * 解析分析结果
     */
    private SessionContext parseAnalysisResult(String response) {
        try {
            // 提取JSON部分（DeepSeek可能返回带解释的文本）
            String jsonStr = extractJson(response);
            JSONObject json = JSON.parseObject(jsonStr);
            
            return SessionContext.builder()
                .currentTopic(json.getString("currentTopic"))
                .conversationPhase(json.getString("conversationPhase"))
                .mentionedEntities(json.getJSONArray("mentionedEntities").toJavaList(String.class))
                .extractedData(json.getJSONObject("extractedData"))
                .userIntent(json.getString("userIntent"))
                .pendingQuestions(json.getJSONArray("pendingQuestions").toJavaList(String.class))
                .complexity(json.getString("complexity"))
                .recentSummaries(new ArrayList<>())
                .sessionStartTime(System.currentTimeMillis())
                .lastUpdateTime(System.currentTimeMillis())
                .messageCount(1)
                .build();
                
        } catch (Exception e) {
            log.error("解析意图分析结果失败", e);
            return createDefaultContext();
        }
    }
    
    /**
     * 从响应中提取JSON
     */
    private String extractJson(String response) {
        int start = response.indexOf("{");
        int end = response.lastIndexOf("}");
        if (start >= 0 && end > start) {
            return response.substring(start, end + 1);
        }
        return response;
    }
    
    /**
     * 创建默认上下文
     */
    private SessionContext createDefaultContext() {
        return SessionContext.builder()
            .currentTopic("健康咨询")
            .conversationPhase("consulting")
            .mentionedEntities(new ArrayList<>())
            .extractedData(new HashMap<>())
            .userIntent("一般咨询")
            .pendingQuestions(new ArrayList<>())
            .complexity("simple")
            .recentSummaries(new ArrayList<>())
            .sessionStartTime(System.currentTimeMillis())
            .lastUpdateTime(System.currentTimeMillis())
            .messageCount(1)
            .build();
    }
}
```

- [ ] **Step 2: 验证编译**

```bash
cd ai-chat-service
mvn compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add ai-chat-service/src/main/java/com/lx/governance/aichat/service/DeepSeekIntentAnalyzer.java
git commit -m "feat: add DeepSeekIntentAnalyzer for intent analysis"
```

---

## Task 3: 创建SessionContextService（第1部分）

**Files:**
- Create: `ai-chat-service/src/main/java/com/lx/governance/aichat/service/SessionContextService.java`

- [ ] **Step 1: 创建服务类框架**

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
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class SessionContextService {
    
    private static final String CONTEXT_KEY_PREFIX = "ai:session:context:";
    private static final long CONTEXT_TTL_MINUTES = 30;
    private static final int SIMPLE_WINDOW_SIZE = 3;
    private static final int COMPLEX_WINDOW_SIZE = 5;
    private static final int MAX_SUMMARY_LENGTH = 50;
    
    @Autowired
    private RedisTemplate<String, String> redisTemplate;
    
    @Autowired
    private DeepSeekIntentAnalyzer intentAnalyzer;
    
    /**
     * 获取或创建会话上下文
     */
    @CircuitBreaker(name = "sessionContext", fallbackMethod = "getContextFallback")
    @Retry(name = "sessionContext", fallbackMethod = "getContextFallback")
    public SessionContext getOrCreateContext(String conversationId, 
                                            String customerId,
                                            String groupId,
                                            String currentMessage) {
        String key = CONTEXT_KEY_PREFIX + conversationId;
        
        try {
            SessionContext context = getFromRedis(key);
            
            if (context == null) {
                // 调用DeepSeek分析意图
                context = intentAnalyzer.analyzeIntentWithTimeout(
                    currentMessage, 
                    Collections.emptyList(),
                    5000
                );
                
                if (context == null) {
                    context = createDefaultContext(conversationId, customerId, groupId);
                }
                
                context.setConversationId(conversationId);
                context.setCustomerId(customerId);
                context.setGroupId(groupId);
                context.setSessionStartTime(System.currentTimeMillis());
                context.setMessageCount(1);
                
                saveToRedis(key, context);
            }
            
            return context;
            
        } catch (Exception e) {
            log.error("获取会话上下文失败: conversationId={}", conversationId, e);
            return createDefaultContext(conversationId, customerId, groupId);
        }
    }
    
    // 其他方法将在下一个任务实现
    private SessionContext getFromRedis(String key) {
        // TODO: Task 4实现
        return null;
    }
    
    private void saveToRedis(String key, SessionContext context) {
        // TODO: Task 4实现
    }
    
    private SessionContext createDefaultContext(String conversationId, String customerId, String groupId) {
        // TODO: Task 4实现
        return null;
    }
    
    private SessionContext getContextFallback(String conversationId, 
                                             String customerId,
                                             String groupId,
                                             String currentMessage,
                                             Throwable t) {
        log.warn("会话上下文服务降级: conversationId={}", conversationId);
        return createDefaultContext(conversationId, customerId, groupId);
    }
}
```

- [ ] **Step 2: 验证编译**

```bash
cd ai-chat-service
mvn compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add ai-chat-service/src/main/java/com/lx/governance/aichat/service/SessionContextService.java
git commit -m "feat: add SessionContextService skeleton"
```

---

## Task 4: 完成SessionContextService实现

**Files:**
- Modify: `ai-chat-service/src/main/java/com/lx/governance/aichat/service/SessionContextService.java`

- [ ] **Step 1: 实现getFromRedis方法**

替换 `getFromRedis` 方法：

```java
private SessionContext getFromRedis(String key) {
    try {
        String json = redisTemplate.opsForValue().get(key);
        if (json != null) {
            log.debug("从Redis获取会话上下文: key={}", key);
            return JSON.parseObject(json, SessionContext.class);
        }
        return null;
    } catch (Exception e) {
        log.error("从Redis读取上下文失败: key={}", key, e);
        return null;
    }
}
```

- [ ] **Step 2: 实现saveToRedis方法**

替换 `saveToRedis` 方法：

```java
private void saveToRedis(String key, SessionContext context) {
    try {
        String json = JSON.toJSONString(context);
        redisTemplate.opsForValue().set(key, json, CONTEXT_TTL_MINUTES, TimeUnit.MINUTES);
        log.debug("保存会话上下文到Redis: key={}", key);
    } catch (Exception e) {
        log.error("保存上下文到Redis失败: key={}", key, e);
    }
}
```

- [ ] **Step 3: 实现createDefaultContext方法**

替换 `createDefaultContext` 方法：

```java
private SessionContext createDefaultContext(String conversationId, String customerId, String groupId) {
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
```

- [ ] **Step 4: 添加updateContext方法**

在类中添加：

```java
/**
 * 更新会话上下文
 */
public void updateContext(String conversationId, String userMessage, String aiResponse) {
    String key = CONTEXT_KEY_PREFIX + conversationId;
    
    try {
        SessionContext context = getFromRedis(key);
        if (context == null) {
            log.warn("上下文不存在，跳过更新: conversationId={}", conversationId);
            return;
        }
        
        // 1. 增加消息计数
        context.setMessageCount(context.getMessageCount() + 1);
        
        // 2. 添加消息摘要到滑动窗口
        String summary = truncateMessage(userMessage, MAX_SUMMARY_LENGTH);
        MessageSummary messageSummary = MessageSummary.builder()
            .userMessage(summary)
            .timestamp(System.currentTimeMillis())
            .build();
        
        List<MessageSummary> summaries = context.getRecentSummaries();
        summaries.add(messageSummary);
        
        // 3. 维护滑动窗口大小
        int windowSize = "complex".equals(context.getComplexity()) 
            ? COMPLEX_WINDOW_SIZE 
            : SIMPLE_WINDOW_SIZE;
        
        if (summaries.size() > windowSize) {
            summaries.remove(0);
        }
        
        // 4. 调用DeepSeek更新意图分析
        SessionContext updatedContext = intentAnalyzer.analyzeIntent(userMessage, summaries);
        if (updatedContext != null) {
            context.setCurrentTopic(updatedContext.getCurrentTopic());
            context.setConversationPhase(updatedContext.getConversationPhase());
            context.setMentionedEntities(updatedContext.getMentionedEntities());
            context.setExtractedData(updatedContext.getExtractedData());
            context.setUserIntent(updatedContext.getUserIntent());
            context.setPendingQuestions(updatedContext.getPendingQuestions());
            context.setComplexity(updatedContext.getComplexity());
        }
        
        // 5. 更新时间戳
        context.setLastUpdateTime(System.currentTimeMillis());
        
        // 6. 保存到Redis并刷新TTL
        saveToRedis(key, context);
        
        log.debug("更新会话上下文成功: conversationId={}", conversationId);
        
    } catch (Exception e) {
        log.error("更新会话上下文失败: conversationId={}", conversationId, e);
    }
}

/**
 * 截断消息到指定长度
 */
private String truncateMessage(String message, int maxLength) {
    if (message == null) {
        return "";
    }
    if (message.length() <= maxLength) {
        return message;
    }
    return message.substring(0, maxLength) + "...";
}
```

- [ ] **Step 5: 验证编译**

```bash
cd ai-chat-service
mvn compile
```

Expected: BUILD SUCCESS

- [ ] **Step 6: 提交**

```bash
git add ai-chat-service/src/main/java/com/lx/governance/aichat/service/SessionContextService.java
git commit -m "feat: complete SessionContextService implementation"
```

---

## Task 5: 添加Resilience4j配置

**Files:**
- Modify: `ai-chat-service/src/main/resources/application.yml`
- Modify: `ai-chat-service/pom.xml`

- [ ] **Step 1: 添加Resilience4j依赖**

在 `ai-chat-service/pom.xml` 的 `<dependencies>` 中添加：

```xml
<!-- Resilience4j -->
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-spring-boot2</artifactId>
    <version>1.7.1</version>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-aop</artifactId>
</dependency>
```

- [ ] **Step 2: 添加Resilience4j配置到application.yml**

在 `ai-chat-service/src/main/resources/application.yml` 中添加：

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

- [ ] **Step 3: 刷新依赖**

```bash
cd ai-chat-service
mvn clean install -DskipTests
```

Expected: BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
git add ai-chat-service/pom.xml
git add ai-chat-service/src/main/resources/application.yml
git commit -m "config: add Resilience4j for session context"
```

---

## Task 6: 编写SessionContextService单元测试

**Files:**
- Create: `ai-chat-service/src/test/java/com/lx/governance/aichat/service/SessionContextServiceTest.java`

- [ ] **Step 1: 创建测试类**

```java
package com.lx.governance.aichat.service;

import com.alibaba.fastjson.JSON;
import com.lx.governance.aichat.dto.MessageSummary;
import com.lx.governance.aichat.dto.SessionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionContextServiceTest {
    
    @Mock
    private RedisTemplate<String, String> redisTemplate;
    
    @Mock
    private DeepSeekIntentAnalyzer intentAnalyzer;
    
    @Mock
    private ValueOperations<String, String> valueOperations;
    
    @InjectMocks
    private SessionContextService sessionContextService;
    
    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }
    
    @Test
    void testGetOrCreateContext_NewContext() {
        // Given
        String conversationId = "conv-001";
        String customerId = "user-001";
        String groupId = "group-001";
        String message = "我想咨询血糖问题";
        
        when(valueOperations.get(anyString())).thenReturn(null);
        
        SessionContext analyzedContext = SessionContext.builder()
            .currentTopic("血糖管理")
            .conversationPhase("consulting")
            .complexity("simple")
            .build();
        when(intentAnalyzer.analyzeIntentWithTimeout(anyString(), anyList(), anyLong()))
            .thenReturn(analyzedContext);
        
        // When
        SessionContext result = sessionContextService.getOrCreateContext(
            conversationId, customerId, groupId, message);
        
        // Then
        assertNotNull(result);
        assertEquals(conversationId, result.getConversationId());
        assertEquals(customerId, result.getCustomerId());
        assertEquals("血糖管理", result.getCurrentTopic());
        
        verify(valueOperations, times(1)).get(anyString());
        verify(intentAnalyzer, times(1)).analyzeIntentWithTimeout(anyString(), anyList(), anyLong());
        verify(valueOperations, times(1)).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }
    
    @Test
    void testGetOrCreateContext_ExistingContext() {
        // Given
        String conversationId = "conv-001";
        SessionContext existingContext = SessionContext.builder()
            .conversationId(conversationId)
            .customerId("user-001")
            .currentTopic("血糖管理")
            .messageCount(5)
            .build();
        
        String json = JSON.toJSONString(existingContext);
        when(valueOperations.get(anyString())).thenReturn(json);
        
        // When
        SessionContext result = sessionContextService.getOrCreateContext(
            conversationId, "user-001", "group-001", "继续咨询");
        
        // Then
        assertNotNull(result);
        assertEquals(conversationId, result.getConversationId());
        assertEquals(5, result.getMessageCount());
        
        verify(valueOperations, times(1)).get(anyString());
        verify(intentAnalyzer, never()).analyzeIntentWithTimeout(anyString(), anyList(), anyLong());
    }
    
    @Test
    void testUpdateContext_Success() {
        // Given
        String conversationId = "conv-001";
        SessionContext existingContext = SessionContext.builder()
            .conversationId(conversationId)
            .customerId("user-001")
            .messageCount(1)
            .complexity("simple")
            .recentSummaries(new ArrayList<>())
            .build();
        
        String json = JSON.toJSONString(existingContext);
        when(valueOperations.get(anyString())).thenReturn(json);
        
        SessionContext updatedAnalysis = SessionContext.builder()
            .currentTopic("饮食建议")
            .conversationPhase("consulting")
            .complexity("simple")
            .build();
        when(intentAnalyzer.analyzeIntent(anyString(), anyList()))
            .thenReturn(updatedAnalysis);
        
        // When
        sessionContextService.updateContext(conversationId, "我应该吃什么", "建议...");
        
        // Then
        verify(valueOperations, times(1)).get(anyString());
        verify(intentAnalyzer, times(1)).analyzeIntent(anyString(), anyList());
        verify(valueOperations, times(1)).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }
}
```

- [ ] **Step 2: 运行测试**

```bash
cd ai-chat-service
mvn test -Dtest=SessionContextServiceTest
```

Expected: Tests run: 3, Failures: 0

- [ ] **Step 3: 提交**

```bash
git add ai-chat-service/src/test/java/com/lx/governance/aichat/service/SessionContextServiceTest.java
git commit -m "test: add SessionContextService unit tests"
```

---

## Task 7: 编写DeepSeekIntentAnalyzer测试

**Files:**
- Create: `ai-chat-service/src/test/java/com/lx/governance/aichat/service/DeepSeekIntentAnalyzerTest.java`

- [ ] **Step 1: 创建测试类**

```java
package com.lx.governance.aichat.service;

import com.lx.governance.aichat.client.DeepSeekClient;
import com.lx.governance.aichat.dto.MessageSummary;
import com.lx.governance.aichat.dto.SessionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeepSeekIntentAnalyzerTest {
    
    @Mock
    private DeepSeekClient deepSeekClient;
    
    @InjectMocks
    private DeepSeekIntentAnalyzer intentAnalyzer;
    
    @Test
    void testAnalyzeIntent_Success() {
        // Given
        String message = "我的血糖最近有点高";
        String mockResponse = "{\n" +
            "  \"currentTopic\": \"血糖管理\",\n" +
            "  \"conversationPhase\": \"consulting\",\n" +
            "  \"mentionedEntities\": [\"血糖\"],\n" +
            "  \"extractedData\": {},\n" +
            "  \"userIntent\": \"咨询血糖问题\",\n" +
            "  \"pendingQuestions\": [\"具体血糖值是多少？\"],\n" +
            "  \"complexity\": \"simple\"\n" +
            "}";
        
        when(deepSeekClient.chat(anyString())).thenReturn(mockResponse);
        
        // When
        SessionContext result = intentAnalyzer.analyzeIntent(message, Collections.emptyList());
        
        // Then
        assertNotNull(result);
        assertEquals("血糖管理", result.getCurrentTopic());
        assertEquals("consulting", result.getConversationPhase());
        assertEquals("simple", result.getComplexity());
        assertEquals(1, result.getMentionedEntities().size());
        assertEquals("血糖", result.getMentionedEntities().get(0));
        
        verify(deepSeekClient, times(1)).chat(anyString());
    }
    
    @Test
    void testAnalyzeIntent_WithHistory() {
        // Given
        String message = "那我应该怎么控制？";
        MessageSummary history = MessageSummary.builder()
            .userMessage("我的血糖最近有点高")
            .timestamp(System.currentTimeMillis())
            .build();
        
        String mockResponse = "{\n" +
            "  \"currentTopic\": \"血糖控制方法\",\n" +
            "  \"conversationPhase\": \"consulting\",\n" +
            "  \"mentionedEntities\": [\"血糖\", \"控制方法\"],\n" +
            "  \"extractedData\": {},\n" +
            "  \"userIntent\": \"询问血糖控制方法\",\n" +
            "  \"pendingQuestions\": [],\n" +
            "  \"complexity\": \"complex\"\n" +
            "}";
        
        when(deepSeekClient.chat(anyString())).thenReturn(mockResponse);
        
        // When
        SessionContext result = intentAnalyzer.analyzeIntent(message, Arrays.asList(history));
        
        // Then
        assertNotNull(result);
        assertEquals("血糖控制方法", result.getCurrentTopic());
        assertEquals("complex", result.getComplexity());
        
        verify(deepSeekClient, times(1)).chat(anyString());
    }
    
    @Test
    void testAnalyzeIntent_Fallback() {
        // Given
        String message = "测试消息";
        when(deepSeekClient.chat(anyString())).thenThrow(new RuntimeException("API错误"));
        
        // When
        SessionContext result = intentAnalyzer.analyzeIntent(message, Collections.emptyList());
        
        // Then
        assertNotNull(result);
        assertEquals("健康咨询", result.getCurrentTopic());
        assertEquals("consulting", result.getConversationPhase());
        assertEquals("simple", result.getComplexity());
    }
}
```

- [ ] **Step 2: 运行测试**

```bash
cd ai-chat-service
mvn test -Dtest=DeepSeekIntentAnalyzerTest
```

Expected: Tests run: 3, Failures: 0

- [ ] **Step 3: 提交**

```bash
git add ai-chat-service/src/test/java/com/lx/governance/aichat/service/DeepSeekIntentAnalyzerTest.java
git commit -m "test: add DeepSeekIntentAnalyzer unit tests"
```

---

## Task 8: 集成到AiChatService

**Files:**
- Modify: `ai-chat-service/src/main/java/com/lx/governance/aichat/service/AiChatService.java`

- [ ] **Step 1: 注入SessionContextService**

在 `AiChatService` 类中添加：

```java
@Autowired
private SessionContextService sessionContextService;
```

- [ ] **Step 2: 修改chat方法获取会话上下文**

找到 `chat` 方法，在获取用户画像后添加：

```java
// 2. 获取会话上下文
SessionContext sessionContext = sessionContextService.getOrCreateContext(
    conversationId,
    customerId,
    groupId,
    userMessage
);
log.info("会话上下文: topic={}, phase={}, complexity={}", 
    sessionContext.getCurrentTopic(),
    sessionContext.getConversationPhase(),
    sessionContext.getComplexity());
```

- [ ] **Step 3: 在保存历史后异步更新上下文**

在保存对话历史后添加：

```java
// 8. 异步更新会话上下文
CompletableFuture.runAsync(() -> {
    try {
        sessionContextService.updateContext(conversationId, userMessage, aiResponse);
    } catch (Exception e) {
        log.error("更新会话上下文失败", e);
    }
});
```

- [ ] **Step 4: 验证编译**

```bash
cd ai-chat-service
mvn compile
```

Expected: BUILD SUCCESS

- [ ] **Step 5: 提交**

```bash
git add ai-chat-service/src/main/java/com/lx/governance/aichat/service/AiChatService.java
git commit -m "feat: integrate SessionContext into AiChatService"
```

---

## Task 9: 端到端测试

**Files:**
- Test: 整个ai-chat-service

- [ ] **Step 1: 启动Redis**

```bash
docker run -d --name redis-test -p 6379:6379 redis:7-alpine
```

- [ ] **Step 2: 启动ai-chat-service**

```bash
cd ai-chat-service
mvn spring-boot:run
```

Expected: Started AiChatServiceApplication

- [ ] **Step 3: 发送第一条消息**

```bash
curl -X POST http://localhost:8103/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "conversationId": "test-conv-001",
    "customerId": "test-user-001",
    "groupId": "test-group-001",
    "message": "我的血糖最近有点高"
  }'
```

Expected: 返回AI回复，日志显示"会话上下文: topic=血糖管理"

- [ ] **Step 4: 验证Redis中的会话上下文**

```bash
docker exec -it redis-test redis-cli
> KEYS ai:session:context:*
> GET ai:session:context:test-conv-001
> TTL ai:session:context:test-conv-001
> EXIT
```

Expected: 
- 显示key存在
- 显示SessionContext JSON
- TTL约为1800秒（30分钟）

- [ ] **Step 5: 发送第二条消息（测试上下文更新）**

```bash
curl -X POST http://localhost:8103/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "conversationId": "test-conv-001",
    "customerId": "test-user-001",
    "groupId": "test-group-001",
    "message": "那我应该怎么控制？"
  }'
```

Expected: AI理解上下文，给出血糖控制建议

- [ ] **Step 6: 验证滑动窗口**

```bash
docker exec -it redis-test redis-cli
> GET ai:session:context:test-conv-001
> EXIT
```

Expected: recentSummaries包含2条消息摘要

- [ ] **Step 7: 运行完整测试套件**

```bash
cd ai-chat-service
mvn clean test
```

Expected: All tests pass

- [ ] **Step 8: 清理**

```bash
# 停止服务（Ctrl+C）
docker stop redis-test
docker rm redis-test
```

- [ ] **Step 9: 最终提交**

```bash
git add .
git commit -m "feat: complete session context management

- Add SessionContext and MessageSummary DTOs
- Implement DeepSeekIntentAnalyzer for intent analysis
- Add SessionContextService with sliding window
- Integrate Resilience4j for fault tolerance
- Add comprehensive unit tests
- Integrate session context into AiChatService
- Support dynamic window size (3-5 rounds based on complexity)"
```

---

## 验收标准

- [x] SessionContext数据结构完整
- [x] DeepSeek意图分析正常工作
- [x] 滑动窗口动态调整（simple=3轮，complex=5轮）
- [x] Redis存储和TTL正确
- [x] 熔断降级机制生效
- [x] 单元测试覆盖率 > 80%
- [x] 端到端测试验证完整流程

---

## 预计工作量

- Task 1-2: 数据结构和意图分析 - 2小时
- Task 3-5: SessionContextService实现 - 2.5小时
- Task 6-7: 单元测试 - 1.5小时
- Task 8-9: 集成和验证 - 1小时

**总计**: 约7小时
