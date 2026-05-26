# AI 记忆功能实施计划 - Plan 4: ai-chat-service (Part 2/2)

> 继续 Part 1

## Task 6: 实现 UserProfileClient

**Files:**
- Create: `ai-chat-service/src/main/java/com/lx/governance/aichat/client/UserProfileClient.java`

- [ ] **Step 1: 创建 client 目录**

```bash
mkdir -p ai-chat-service/src/main/java/com/lx/governance/aichat/client
```

- [ ] **Step 2: 创建 UserProfileClient**

```bash
cat > ai-chat-service/src/main/java/com/lx/governance/aichat/client/UserProfileClient.java << 'JAVAEOF'
package com.lx.governance.aichat.client;

import com.lx.governance.aichat.dto.UserProfileDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 用户画像客户端
 * 通过 RabbitMQ 请求-响应模式获取用户画像
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserProfileClient {
    
    private final RabbitTemplate rabbitTemplate;
    
    @Value("${timeout.user-profile:5000}")
    private long timeout;
    
    private final Map<String, CompletableFuture<UserProfileDTO>> pendingRequests = new ConcurrentHashMap<>();
    
    /**
     * 请求用户画像
     */
    public UserProfileDTO requestUserProfile(String customerId, String correlationId) {
        CompletableFuture<UserProfileDTO> future = new CompletableFuture<>();
        pendingRequests.put(correlationId, future);
        
        try {
            // 发送请求
            Map<String, Object> request = Map.of(
                "correlationId", correlationId,
                "customerId", customerId,
                "timestamp", System.currentTimeMillis()
            );
            
            rabbitTemplate.convertAndSend(
                "user.profile.exchange",
                "user.profile.request",
                request
            );
            
            log.info("[{}] 已发送用户画像请求: customerId={}", correlationId, customerId);
            
            // 等待响应
            return future.get(timeout, TimeUnit.MILLISECONDS);
            
        } catch (TimeoutException e) {
            pendingRequests.remove(correlationId);
            log.error("[{}] 获取用户画像超时: customerId={}", correlationId, customerId);
            throw new RuntimeException("获取用户画像超时");
            
        } catch (Exception e) {
            pendingRequests.remove(correlationId);
            log.error("[{}] 获取用户画像失败: customerId={}", correlationId, customerId, e);
            throw new RuntimeException("获取用户画像失败", e);
        }
    }
    
    /**
     * 处理用户画像响应
     */
    @RabbitListener(queues = "#{@userProfileResponseQueue}")
    public void handleResponse(Map<String, Object> response) {
        String correlationId = (String) response.get("correlationId");
        Boolean success = (Boolean) response.get("success");
        
        CompletableFuture<UserProfileDTO> future = pendingRequests.remove(correlationId);
        if (future != null) {
            if (Boolean.TRUE.equals(success)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) response.get("data");
                UserProfileDTO profile = convertToUserProfile(data);
                future.complete(profile);
                log.info("[{}] 收到用户画像响应", correlationId);
            } else {
                String errorMessage = (String) response.get("errorMessage");
                future.completeExceptionally(new RuntimeException(errorMessage));
                log.error("[{}] 用户画像请求失败: {}", correlationId, errorMessage);
            }
        }
    }
    
    /**
     * 创建响应队列
     */
    @org.springframework.context.annotation.Bean
    public org.springframework.amqp.core.Queue userProfileResponseQueue() {
        return org.springframework.amqp.core.QueueBuilder
            .durable("user.profile.response." + java.util.UUID.randomUUID())
            .autoDelete(true)
            .build();
    }
    
    private UserProfileDTO convertToUserProfile(Map<String, Object> data) {
        UserProfileDTO profile = new UserProfileDTO();
        profile.setBasicInfo((Map<String, Object>) data.get("basicInfo"));
        profile.setHealthProfile((Map<String, Object>) data.get("healthProfile"));
        profile.setBehaviorPreferences((Map<String, Object>) data.get("behaviorPreferences"));
        profile.setGlobalMemories((java.util.List<Map<String, Object>>) data.get("globalMemories"));
        return profile;
    }
}
JAVAEOF
```

- [ ] **Step 3: 验证编译**

```bash
mvn -pl ai-chat-service compile
```

Expected: BUILD SUCCESS

---

## Task 7: 实现 VectorMemoryClient

**Files:**
- Create: `ai-chat-service/src/main/java/com/lx/governance/aichat/client/VectorMemoryClient.java`

- [ ] **Step 1: 创建 VectorMemoryClient**

```bash
cat > ai-chat-service/src/main/java/com/lx/governance/aichat/client/VectorMemoryClient.java << 'JAVAEOF'
package com.lx.governance.aichat.client;

import com.lx.governance.aichat.dto.MemoryItemDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 向量记忆客户端
 * 通过 RabbitMQ 请求-响应模式获取向量记忆
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorMemoryClient {
    
    private final RabbitTemplate rabbitTemplate;
    
    @Value("${timeout.vector-memory:5000}")
    private long timeout;
    
    private final Map<String, CompletableFuture<List<MemoryItemDTO>>> pendingRequests = new ConcurrentHashMap<>();
    
    /**
     * 请求群组记忆
     */
    public List<MemoryItemDTO> requestGroupMemories(
            String groupId,
            String customerId,
            String query,
            String correlationId) {
        
        CompletableFuture<List<MemoryItemDTO>> future = new CompletableFuture<>();
        pendingRequests.put(correlationId, future);
        
        try {
            // 发送请求
            Map<String, Object> request = Map.of(
                "correlationId", correlationId,
                "groupId", groupId,
                "customerId", customerId,
                "query", query,
                "topK", 5,
                "timestamp", System.currentTimeMillis()
            );
            
            rabbitTemplate.convertAndSend(
                "vector.memory.exchange",
                "vector.memory.request",
                request
            );
            
            log.info("[{}] 已发送向量记忆请求: groupId={}, customerId={}, query={}", 
                correlationId, groupId, customerId, query);
            
            // 等待响应
            return future.get(timeout, TimeUnit.MILLISECONDS);
            
        } catch (TimeoutException e) {
            pendingRequests.remove(correlationId);
            log.error("[{}] 获取向量记忆超时: groupId={}, customerId={}", 
                correlationId, groupId, customerId);
            return List.of();
            
        } catch (Exception e) {
            pendingRequests.remove(correlationId);
            log.error("[{}] 获取向量记忆失败: groupId={}, customerId={}", 
                correlationId, groupId, customerId, e);
            return List.of();
        }
    }
    
    /**
     * 处理向量记忆响应
     */
    @RabbitListener(queues = "#{@vectorMemoryResponseQueue}")
    public void handleResponse(Map<String, Object> response) {
        String correlationId = (String) response.get("correlationId");
        Boolean success = (Boolean) response.get("success");
        
        CompletableFuture<List<MemoryItemDTO>> future = pendingRequests.remove(correlationId);
        if (future != null) {
            if (Boolean.TRUE.equals(success)) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
                List<MemoryItemDTO> memories = convertToMemories(data);
                future.complete(memories);
                log.info("[{}] 收到向量记忆响应: count={}", correlationId, memories.size());
            } else {
                String errorMessage = (String) response.get("errorMessage");
                future.completeExceptionally(new RuntimeException(errorMessage));
                log.error("[{}] 向量记忆请求失败: {}", correlationId, errorMessage);
            }
        }
    }
    
    /**
     * 触发记忆提取（异步，不等待响应）
     */
    public void triggerMemoryExtraction(
            String groupId,
            String customerId,
            String userMessage,
            String aiResponse,
            String correlationId) {
        
        try {
            Map<String, Object> request = Map.of(
                "correlationId", correlationId,
                "groupId", groupId,
                "customerId", customerId,
                "userMessage", userMessage,
                "aiResponse", aiResponse,
                "timestamp", System.currentTimeMillis()
            );
            
            rabbitTemplate.convertAndSend(
                "memory.extraction.exchange",
                "memory.extraction",
                request
            );
            
            log.info("[{}] 已触发记忆提取: groupId={}, customerId={}", 
                correlationId, groupId, customerId);
            
        } catch (Exception e) {
            log.error("[{}] 触发记忆提取失败: groupId={}, customerId={}", 
                correlationId, groupId, customerId, e);
        }
    }
    
    /**
     * 创建响应队列
     */
    @org.springframework.context.annotation.Bean
    public org.springframework.amqp.core.Queue vectorMemoryResponseQueue() {
        return org.springframework.amqp.core.QueueBuilder
            .durable("vector.memory.response." + java.util.UUID.randomUUID())
            .autoDelete(true)
            .build();
    }
    
    private List<MemoryItemDTO> convertToMemories(List<Map<String, Object>> data) {
        if (data == null) {
            return List.of();
        }
        
        return data.stream()
            .map(item -> {
                MemoryItemDTO memory = new MemoryItemDTO();
                memory.setId(((Number) item.get("id")).longValue());
                memory.setContent((String) item.get("content"));
                memory.setSimilarity(((Number) item.get("similarity")).doubleValue());
                memory.setConfidenceScore(((Number) item.get("confidenceScore")).doubleValue());
                memory.setVerified((Boolean) item.get("verified"));
                memory.setSource((String) item.get("source"));
                return memory;
            })
            .collect(Collectors.toList());
    }
}
JAVAEOF
```

- [ ] **Step 2: 验证编译**

```bash
mvn -pl ai-chat-service compile
```

Expected: BUILD SUCCESS

---

## Task 8: 实现 DifyClient

**Files:**
- Create: `ai-chat-service/src/main/java/com/lx/governance/aichat/client/DifyClient.java`

- [ ] **Step 1: 创建 DifyClient**

```bash
cat > ai-chat-service/src/main/java/com/lx/governance/aichat/client/DifyClient.java << 'JAVAEOF'
package com.lx.governance.aichat.client;

import com.lx.governance.aichat.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Dify API 客户端
 * 调用 Dify API 进行 AI 对话
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DifyClient {
    
    private final RestClient difyRestClient;
    
    /**
     * 发送聊天请求
     */
    public DifyResponse chat(
            ChatRequest request,
            UserProfileDTO profile,
            List<MemoryItemDTO> memories) {
        
        long startTime = System.currentTimeMillis();
        
        try {
            DifyRequest difyRequest = buildDifyRequest(request, profile, memories);
            
            DifyResponse response = difyRestClient.post()
                .uri("/chat-messages")
                .body(difyRequest)
                .retrieve()
                .body(DifyResponse.class);
            
            long cost = System.currentTimeMillis() - startTime;
            log.info("Dify API 调用成功: conversationId={}, cost={}ms", 
                response.getConversationId(), cost);
            
            return response;
            
        } catch (Exception e) {
            log.error("Dify API 调用失败", e);
            throw new RuntimeException("Dify API 调用失败", e);
        }
    }
    
    /**
     * 构建 Dify 请求
     */
    private DifyRequest buildDifyRequest(
            ChatRequest request,
            UserProfileDTO profile,
            List<MemoryItemDTO> memories) {
        
        Map<String, Object> inputs = new HashMap<>();
        
        // 添加用户画像
        if (profile != null) {
            inputs.put("user_profile", profile);
        }
        
        // 添加向量记忆
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
            .responseMode(false)
            .build();
    }
}
JAVAEOF
```

- [ ] **Step 2: 验证编译**

```bash
mvn -pl ai-chat-service compile
```

Expected: BUILD SUCCESS

---

## Task 9: 实现 AiChatService

**Files:**
- Create: `ai-chat-service/src/main/java/com/lx/governance/aichat/service/AiChatService.java`

- [ ] **Step 1: 创建 service 目录**

```bash
mkdir -p ai-chat-service/src/main/java/com/lx/governance/aichat/service
```

- [ ] **Step 2: 创建 AiChatService**

```bash
cat > ai-chat-service/src/main/java/com/lx/governance/aichat/service/AiChatService.java << 'JAVAEOF'
package com.lx.governance.aichat.service;

import com.lx.governance.aichat.client.*;
import com.lx.governance.aichat.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * AI 聊天服务
 * 协调用户画像、向量记忆和 Dify API
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatService {
    
    private final UserProfileClient userProfileClient;
    private final VectorMemoryClient vectorMemoryClient;
    private final DifyClient difyClient;
    
    /**
     * 处理聊天请求
     */
    public ChatResponse handleChatRequest(ChatRequest request) {
        String correlationId = UUID.randomUUID().toString();
        long startTime = System.currentTimeMillis();
        
        try {
            log.info("[{}] 开始处理聊天请求: groupId={}, customerId={}, message={}", 
                correlationId, request.getGroupId(), request.getCustomerId(), 
                truncate(request.getMessage(), 50));
            
            // 1. 并行获取用户画像和向量记忆
            CompletableFuture<UserProfileDTO> profileFuture = CompletableFuture.supplyAsync(() -> 
                userProfileClient.requestUserProfile(request.getCustomerId(), correlationId + "-profile")
            );
            
            CompletableFuture<List<MemoryItemDTO>> memoriesFuture = CompletableFuture.supplyAsync(() -> 
                vectorMemoryClient.requestGroupMemories(
                    request.getGroupId(),
                    request.getCustomerId(),
                    request.getMessage(),
                    correlationId + "-memory"
                )
            );
            
            // 2. 等待两个结果
            CompletableFuture.allOf(profileFuture, memoriesFuture).join();
            
            UserProfileDTO profile = profileFuture.get();
            List<MemoryItemDTO> memories = memoriesFuture.get();
            
            log.info("[{}] 上下文获取完成: profile={}, memories={}", 
                correlationId, profile != null, memories.size());
            
            // 3. 调用 Dify API
            DifyResponse difyResponse = difyClient.chat(request, profile, memories);
            
            // 4. 异步触发记忆提取
            vectorMemoryClient.triggerMemoryExtraction(
                request.getGroupId(),
                request.getCustomerId(),
                request.getMessage(),
                difyResponse.getAnswer(),
                correlationId + "-extraction"
            );
            
            long cost = System.currentTimeMillis() - startTime;
            log.info("[{}] 聊天请求处理完成: cost={}ms", correlationId, cost);
            
            return ChatResponse.success(
                difyResponse.getAnswer(),
                difyResponse.getConversationId()
            );
            
        } catch (Exception e) {
            log.error("[{}] 聊天请求处理失败", correlationId, e);
            return ChatResponse.error("处理失败: " + e.getMessage());
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

- [ ] **Step 3: 验证编译**

```bash
mvn -pl ai-chat-service compile
```

Expected: BUILD SUCCESS

---

## Task 10: 实现 REST Controller

**Files:**
- Create: `ai-chat-service/src/main/java/com/lx/governance/aichat/controller/AiChatController.java`

- [ ] **Step 1: 创建 controller 目录**

```bash
mkdir -p ai-chat-service/src/main/java/com/lx/governance/aichat/controller
```

- [ ] **Step 2: 创建 AiChatController**

```bash
cat > ai-chat-service/src/main/java/com/lx/governance/aichat/controller/AiChatController.java << 'JAVAEOF'
package com.lx.governance.aichat.controller;

import com.lx.governance.aichat.dto.ChatRequest;
import com.lx.governance.aichat.dto.ChatResponse;
import com.lx.governance.aichat.service.AiChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * AI 聊天控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class AiChatController {
    
    private final AiChatService aiChatService;
    
    /**
     * 聊天接口
     */
    @PostMapping
    public ResponseEntity<ChatResponse> chat(@RequestBody @Valid ChatRequest request) {
        log.info("收到聊天请求: groupId={}, customerId={}", 
            request.getGroupId(), request.getCustomerId());
        
        try {
            ChatResponse response = aiChatService.handleChatRequest(request);
            
            if (Boolean.TRUE.equals(response.getSuccess())) {
                return ResponseEntity.ok(response);
            } else {
                return ResponseEntity.status(500).body(response);
            }
            
        } catch (Exception e) {
            log.error("处理聊天请求失败", e);
            return ResponseEntity.status(500)
                .body(ChatResponse.error("系统错误: " + e.getMessage()));
        }
    }
    
    /**
     * 健康检查
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }
}
JAVAEOF
```

- [ ] **Step 3: 验证编译**

```bash
mvn -pl ai-chat-service compile
```

Expected: BUILD SUCCESS

---

## Task 11: 添加日志配置

**Files:**
- Create: `ai-chat-service/src/main/resources/logback-spring.xml`

- [ ] **Step 1: 创建 logback-spring.xml**

```bash
cat > ai-chat-service/src/main/resources/logback-spring.xml << 'XMLEOF'
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <property name="LOG_PATH" value="logs"/>
    <property name="APP_NAME" value="ai-chat-service"/>
    
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
    
    <logger name="com.lx.governance.aichat" level="INFO"/>
    
    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="FILE"/>
    </root>
</configuration>
XMLEOF
```

---

## Task 12: 构建和启动测试

**Files:**
- None (verification only)

- [ ] **Step 1: 完整构建模块**

```bash
mvn -pl ai-chat-service clean package -DskipTests
```

Expected: BUILD SUCCESS

- [ ] **Step 2: 启动服务**

```bash
mvn -pl ai-chat-service spring-boot:run &
```

- [ ] **Step 3: 等待服务启动**

```bash
sleep 30
```

- [ ] **Step 4: 检查服务注册到 Nacos**

```bash
curl -s http://localhost:8848/nacos/v1/ns/instance/list?serviceName=ai-chat-service | grep -q "ai-chat-service"
```

Expected: 命令成功（退出码 0）

- [ ] **Step 5: 检查健康状态**

```bash
curl -s http://localhost:8103/actuator/health | grep -q "UP"
```

Expected: 输出包含 "UP"

- [ ] **Step 6: 测试聊天接口**

```bash
curl -X POST http://localhost:8103/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "groupId": "test-group-001",
    "customerId": "test-customer-001",
    "message": "你好"
  }' | jq .
```

Expected: 返回 JSON 响应

- [ ] **Step 7: 停止服务**

```bash
pkill -f ai-chat-service
```

---

## 验证清单

完成所有任务后，验证以下内容：

- [ ] ai-chat-service 模块结构完整
- [ ] 所有 DTO、Config、Client 类已创建
- [ ] UserProfileClient 实现完整
- [ ] VectorMemoryClient 实现完整
- [ ] DifyClient 实现完整
- [ ] AiChatService 实现完整
- [ ] AiChatController 实现完整
- [ ] 服务可以成功启动
- [ ] 服务注册到 Nacos
- [ ] REST API 可访问
- [ ] 健康检查通过
- [ ] 所有更改已提交到 Git

---

## 下一步

ai-chat-service 完成后，继续执行：

**Plan 5: 集成测试和部署** - 完整的端到端测试和部署验证
