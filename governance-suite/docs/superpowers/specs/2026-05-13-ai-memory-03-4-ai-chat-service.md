# AI 记忆功能设计文档 - 第三部分之四：ai-chat-service 组件设计

## 文档信息

- **创建日期**: 2026-05-13
- **项目**: governance-suite
- **服务**: ai-chat-service
- **端口**: 8103

## 一、服务职责

ai-chat-service 是整个 AI 记忆系统的协调者，负责：

1. 接收用户的聊天请求（REST API）
2. 通过 MQ 请求用户画像
3. 通过 MQ 请求群组记忆
4. 调用 Dify API 获取 AI 回复
5. 返回结果给用户
6. 异步触发记忆提取
7. 保存聊天历史（可选）

## 二、核心组件

### 2.1 AiChatController

**职责**：提供 REST API 接口

**代码设计**：

```java
package com.lx.governance.chat.controller;

import com.lx.governance.chat.dto.ChatRequestDTO;
import com.lx.governance.chat.dto.ChatResponseDTO;
import com.lx.governance.chat.dto.ChatHistoryQueryDTO;
import com.lx.governance.chat.service.AiChatService;
import com.lx.governance.common.response.ApiResponse;
import com.lx.governance.common.trace.Trace;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/ai/chat")
@RequiredArgsConstructor
@Validated
public class AiChatController {
    
    private final AiChatService aiChatService;
    
    /**
     * 发送聊天消息
     */
    @PostMapping("/send")
    public ApiResponse<ChatResponseDTO> chat(@Valid @RequestBody ChatRequestDTO request) {
        String traceId = Trace.getTraceId();
        
        log.info("[{}] 收到聊天请求: customerId={}, groupId={}", 
            traceId, request.getCustomerId(), request.getGroupId());
        
        try {
            ChatResponseDTO response = aiChatService.chat(request);
            return ApiResponse.success(response);
            
        } catch (Exception e) {
            log.error("[{}] 聊天请求处理失败", traceId, e);
            return ApiResponse.fail(500, "聊天请求处理失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取聊天历史
     */
    @GetMapping("/history")
    public ApiResponse<List<ChatHistoryQueryDTO>> getHistory(
        @RequestParam String customerId,
        @RequestParam String groupId,
        @RequestParam(defaultValue = "1") Integer page,
        @RequestParam(defaultValue = "20") Integer size
    ) {
        log.info("查询聊天历史: customerId={}, groupId={}, page={}, size={}", 
            customerId, groupId, page, size);
        
        try {
            List<ChatHistoryQueryDTO> history = aiChatService.getHistory(
                customerId, groupId, page, size
            );
            return ApiResponse.success(history);
            
        } catch (Exception e) {
            log.error("查询聊天历史失败", e);
            return ApiResponse.fail(500, "查询聊天历史失败");
        }
    }
}
```

---

### 2.2 AiChatService

**职责**：协调整个聊天流程

**代码设计**：

```java
package com.lx.governance.chat.service;

import com.lx.governance.chat.dto.*;
import com.lx.governance.chat.entity.AiChatHistoryEntity;
import com.lx.governance.chat.mapper.AiChatHistoryMapper;
import com.lx.governance.common.trace.Trace;
import com.lx.governance.profile.dto.UserProfileDTO;
import com.lx.governance.memory.dto.MemoryItemDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatService {
    
    private final RabbitMQRequestService rabbitMQRequestService;
    private final DifyService difyService;
    private final AiChatHistoryMapper chatHistoryMapper;
    
    /**
     * 处理聊天请求
     */
    @Transactional(rollbackFor = Exception.class)
    public ChatResponseDTO chat(ChatRequestDTO request) {
        long startTime = System.currentTimeMillis();
        String traceId = Trace.getTraceId();
        String correlationId = UUID.randomUUID().toString();
        
        try {
            log.info("[{}] 开始处理聊天请求: correlationId={}", traceId, correlationId);
            
            // 1. 请求用户画像
            UserProfileDTO userProfile = requestUserProfile(
                request.getCustomerId(), 
                correlationId
            );
            
            if (userProfile == null) {
                log.warn("[{}] 用户画像获取失败，使用空画像", traceId);
                userProfile = UserProfileDTO.empty(request.getCustomerId());
            }
            
            // 2. 请求群组记忆
            List<MemoryItemDTO> groupMemories = requestGroupMemories(
                request.getGroupId(),
                request.getCustomerId(),
                request.getMessage(),
                correlationId
            );
            
            if (groupMemories == null) {
                log.warn("[{}] 群组记忆获取失败，使用空列表", traceId);
                groupMemories = List.of();
            }
            
            // 3. 调用 Dify API
            DifyResponseDTO difyResponse = difyService.chat(
                request.getMessage(),
                userProfile,
                groupMemories,
                request.getConversationId(),
                request.getRole()
            );
            
            // 4. 保存聊天历史
            saveChatHistory(request, difyResponse, traceId);
            
            // 5. 异步触发记忆提取
            triggerMemoryExtraction(
                request.getGroupId(),
                request.getCustomerId(),
                request.getMessage(),
                difyResponse.getAnswer()
            );
            
            // 6. 构建响应
            ChatResponseDTO response = ChatResponseDTO.builder()
                .conversationId(difyResponse.getConversationId())
                .messageId(difyResponse.getMessageId())
                .answer(difyResponse.getAnswer())
                .createdAt(difyResponse.getCreatedAt())
                .build();
            
            long cost = System.currentTimeMillis() - startTime;
            log.info("[{}] 聊天请求处理完成: cost={}ms", traceId, cost);
            
            return response;
            
        } catch (Exception e) {
            log.error("[{}] 聊天请求处理失败", traceId, e);
            throw new RuntimeException("聊天请求处理失败", e);
        }
    }
    
    /**
     * 请求用户画像
     */
    private UserProfileDTO requestUserProfile(String customerId, String correlationId) {
        long startTime = System.currentTimeMillis();
        
        try {
            log.debug("[{}] 请求用户画像: customerId={}", correlationId, customerId);
            
            UserProfileDTO profile = rabbitMQRequestService.requestUserProfile(
                customerId, 
                correlationId
            );
            
            long cost = System.currentTimeMillis() - startTime;
            log.info("[{}] 用户画像获取成功: cost={}ms", correlationId, cost);
            
            return profile;
            
        } catch (Exception e) {
            log.error("[{}] 用户画像获取失败", correlationId, e);
            return null;  // 降级：返回 null
        }
    }
    
    /**
     * 请求群组记忆
     */
    private List<MemoryItemDTO> requestGroupMemories(
        String groupId,
        String customerId,
        String query,
        String correlationId
    ) {
        long startTime = System.currentTimeMillis();
        
        try {
            log.debug("[{}] 请求群组记忆: groupId={}, customerId={}", 
                correlationId, groupId, customerId);
            
            List<MemoryItemDTO> memories = rabbitMQRequestService.requestGroupMemories(
                groupId,
                customerId,
                query,
                5,  // Top-5
                correlationId
            );
            
            long cost = System.currentTimeMillis() - startTime;
            log.info("[{}] 群组记忆获取成功: count={}, cost={}ms", 
                correlationId, memories.size(), cost);
            
            return memories;
            
        } catch (Exception e) {
            log.error("[{}] 群组记忆获取失败", correlationId, e);
            return null;  // 降级：返回 null
        }
    }
    
    /**
     * 保存聊天历史
     */
    private void saveChatHistory(
        ChatRequestDTO request,
        DifyResponseDTO difyResponse,
        String traceId
    ) {
        try {
            AiChatHistoryEntity history = new AiChatHistoryEntity();
            history.setCustomerId(request.getCustomerId());
            history.setGroupId(request.getGroupId());
            history.setConversationId(difyResponse.getConversationId());
            history.setUserMessage(request.getMessage());
            history.setAiResponse(difyResponse.getAnswer());
            history.setRole(request.getRole());
            history.setTraceId(traceId);
            history.setIsDeleted(0);
            
            chatHistoryMapper.insert(history);
            
        } catch (Exception e) {
            log.error("保存聊天历史失败", e);
            // 不影响主流程
        }
    }
    
    /**
     * 触发记忆提取
     */
    private void triggerMemoryExtraction(
        String groupId,
        String customerId,
        String userMessage,
        String aiResponse
    ) {
        try {
            rabbitMQRequestService.sendExtractionRequest(
                groupId,
                customerId,
                userMessage,
                aiResponse
            );
            
        } catch (Exception e) {
            log.error("触发记忆提取失败", e);
            // 不影响主流程
        }
    }
    
    /**
     * 查询聊天历史
     */
    public List<ChatHistoryQueryDTO> getHistory(
        String customerId,
        String groupId,
        Integer page,
        Integer size
    ) {
        int offset = (page - 1) * size;
        List<AiChatHistoryEntity> histories = chatHistoryMapper.selectByCustomerAndGroup(
            customerId, groupId, offset, size
        );
        
        return histories.stream()
            .map(this::toQueryDTO)
            .toList();
    }
    
    private ChatHistoryQueryDTO toQueryDTO(AiChatHistoryEntity entity) {
        return ChatHistoryQueryDTO.builder()
            .id(entity.getId())
            .userMessage(entity.getUserMessage())
            .aiResponse(entity.getAiResponse())
            .role(entity.getRole())
            .createTime(entity.getCreateTime())
            .build();
    }
}
```

---

### 2.3 RabbitMQRequestService

**职责**：封装 RabbitMQ 请求-响应模式

**代码设计**：

```java
package com.lx.governance.chat.service;

import com.lx.governance.memory.dto.*;
import com.lx.governance.profile.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
@RequiredArgsConstructor
public class RabbitMQRequestService {
    
    private final RabbitTemplate rabbitTemplate;
    private final MessageConverter messageConverter;
    
    private static final long REQUEST_TIMEOUT_MS = 5000;  // 5秒超时
    
    /**
     * 请求用户画像
     */
    public UserProfileDTO requestUserProfile(String customerId, String correlationId) {
        UserProfileRequestMessage request = new UserProfileRequestMessage();
        request.setCorrelationId(correlationId);
        request.setCustomerId(customerId);
        request.setTimestamp(System.currentTimeMillis());
        
        UserProfileResponseMessage response = sendAndReceive(
            "user.profile.exchange",
            "user.profile.request",
            request,
            correlationId,
            UserProfileResponseMessage.class
        );
        
        if (response == null || !response.getSuccess()) {
            throw new RuntimeException("用户画像请求失败: " + 
                (response != null ? response.getErrorMessage() : "超时"));
        }
        
        return response.getData();
    }
    
    /**
     * 请求群组记忆
     */
    public List<MemoryItemDTO> requestGroupMemories(
        String groupId,
        String customerId,
        String query,
        int topK,
        String correlationId
    ) {
        MemoryRequestMessage request = new MemoryRequestMessage();
        request.setCorrelationId(correlationId);
        request.setGroupId(groupId);
        request.setCustomerId(customerId);
        request.setQuery(query);
        request.setTopK(topK);
        request.setTimestamp(System.currentTimeMillis());
        
        MemoryResponseMessage response = sendAndReceive(
            "vector.memory.exchange",
            "vector.memory.request",
            request,
            correlationId,
            MemoryResponseMessage.class
        );
        
        if (response == null || !response.getSuccess()) {
            throw new RuntimeException("群组记忆请求失败: " + 
                (response != null ? response.getErrorMessage() : "超时"));
        }
        
        return response.getData();
    }
    
    /**
     * 发送记忆提取请求（异步，无需等待响应）
     */
    public void sendExtractionRequest(
        String groupId,
        String customerId,
        String userMessage,
        String aiResponse
    ) {
        ExtractionRequestMessage request = new ExtractionRequestMessage();
        request.setGroupId(groupId);
        request.setCustomerId(customerId);
        request.setUserMessage(userMessage);
        request.setAiResponse(aiResponse);
        request.setTimestamp(System.currentTimeMillis());
        
        rabbitTemplate.convertAndSend(
            "vector.memory.exchange",
            "memory.extraction.request",
            request
        );
        
        log.debug("记忆提取请求已发送: groupId={}, customerId={}", groupId, customerId);
    }
    
    /**
     * 发送请求并等待响应（通用方法）
     */
    private <T> T sendAndReceive(
        String exchange,
        String routingKey,
        Object request,
        String correlationId,
        Class<T> responseType
    ) {
        try {
            // 创建临时响应队列
            String responseQueue = routingKey + ".response." + correlationId;
            
            // 发送请求
            CompletableFuture<T> future = new CompletableFuture<>();
            
            // 设置消息属性
            MessageProperties properties = new MessageProperties();
            properties.setCorrelationId(correlationId);
            properties.setReplyTo(responseQueue);
            
            Message message = messageConverter.toMessage(request, properties);
            
            // 发送并等待响应
            rabbitTemplate.sendAndReceive(exchange, routingKey, message, 
                (correlationData) -> {
                    // 这里可以添加确认逻辑
                });
            
            // 使用 RabbitTemplate 的 receiveAndConvert 等待响应
            Object response = rabbitTemplate.receiveAndConvert(
                responseQueue, 
                REQUEST_TIMEOUT_MS
            );
            
            if (response == null) {
                throw new TimeoutException("请求超时");
            }
            
            return responseType.cast(response);
            
        } catch (TimeoutException e) {
            log.error("请求超时: correlationId={}", correlationId);
            return null;
            
        } catch (Exception e) {
            log.error("请求失败: correlationId={}", correlationId, e);
            throw new RuntimeException("请求失败", e);
        }
    }
}
```

---

### 2.4 DifyService

**职责**：调用 Dify API

**代码设计**：

```java
package com.lx.governance.chat.service;

import com.alibaba.fastjson2.JSON;
import com.lx.governance.chat.dto.DifyRequestDTO;
import com.lx.governance.chat.dto.DifyResponseDTO;
import com.lx.governance.memory.dto.MemoryItemDTO;
import com.lx.governance.profile.dto.UserProfileDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DifyService {
    
    private final RestClient restClient;
    
    @Value("${dify.api-key}")
    private String apiKey;
    
    @Value("${dify.api-url}")
    private String apiUrl;
    
    /**
     * 调用 Dify API
     */
    public DifyResponseDTO chat(
        String query,
        UserProfileDTO userProfile,
        List<MemoryItemDTO> groupMemories,
        String conversationId,
        String role
    ) {
        long startTime = System.currentTimeMillis();
        
        try {
            log.info("调用 Dify API: query={}, conversationId={}", 
                query.substring(0, Math.min(50, query.length())), conversationId);
            
            // 构建请求
            Map<String, Object> requestBody = buildRequest(
                query, userProfile, groupMemories, conversationId, role
            );
            
            // 发送请求
            DifyResponseDTO response = restClient.post()
                .uri(apiUrl + "/chat-messages")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(requestBody)
                .retrieve()
                .body(DifyResponseDTO.class);
            
            long cost = System.currentTimeMillis() - startTime;
            log.info("Dify API 调用成功: cost={}ms", cost);
            
            return response;
            
        } catch (Exception e) {
            log.error("Dify API 调用失败", e);
            throw new RuntimeException("Dify API 调用失败", e);
        }
    }
    
    /**
     * 构建 Dify 请求
     */
    private Map<String, Object> buildRequest(
        String query,
        UserProfileDTO userProfile,
        List<MemoryItemDTO> groupMemories,
        String conversationId,
        String role
    ) {
        Map<String, Object> request = new HashMap<>();
        
        // 基本参数
        request.put("query", query);
        request.put("response_mode", "blocking");
        request.put("user", "user-" + userProfile.getBasicInfo().getCustomerId());
        
        // 会话ID（可选）
        if (conversationId != null && !conversationId.isEmpty()) {
            request.put("conversation_id", conversationId);
        }
        
        // inputs：注入用户画像和群组记忆
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("user_profile", JSON.toJSONString(userProfile));
        inputs.put("group_context", JSON.toJSONString(groupMemories));
        inputs.put("role", role != null ? role : "doctor");
        request.put("inputs", inputs);
        
        return request;
    }
}
```

---

## 三、DTO 定义

### 3.1 ChatRequestDTO

```java
package com.lx.governance.chat.dto;

import lombok.Data;
import javax.validation.constraints.NotBlank;

@Data
public class ChatRequestDTO {
    
    @NotBlank(message = "用户ID不能为空")
    private String customerId;
    
    @NotBlank(message = "群组ID不能为空")
    private String groupId;
    
    @NotBlank(message = "消息内容不能为空")
    private String message;
    
    private String conversationId;  // 可选，首次为空
    
    private String role;  // doctor/nutritionist/customer
}
```

### 3.2 ChatResponseDTO

```java
package com.lx.governance.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponseDTO {
    private String conversationId;
    private String messageId;
    private String answer;
    private Long createdAt;
}
```

### 3.3 DifyResponseDTO

```java
package com.lx.governance.chat.dto;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

@Data
public class DifyResponseDTO {
    
    @JSONField(name = "conversation_id")
    private String conversationId;
    
    @JSONField(name = "message_id")
    private String messageId;
    
    private String answer;
    
    @JSONField(name = "created_at")
    private Long createdAt;
}
```

### 3.4 ChatHistoryQueryDTO

```java
package com.lx.governance.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatHistoryQueryDTO {
    private Long id;
    private String userMessage;
    private String aiResponse;
    private String role;
    private LocalDateTime createTime;
}
```

---

## 四、配置类

### 4.1 RestClientConfig

```java
package com.lx.governance.chat.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class RestClientConfig {
    
    @Value("${dify.timeout:30000}")
    private long timeout;
    
    @Bean
    public RestClient restClient() {
        return RestClient.builder()
            .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(timeout))
                    .build()
            ))
            .build();
    }
}
```

---

## 五、总结

本文档详细设计了 ai-chat-service 的核心组件：

1. **AiChatController**：提供 REST API 接口
2. **AiChatService**：协调整个聊天流程
3. **RabbitMQRequestService**：封装 MQ 请求-响应模式
4. **DifyService**：调用 Dify API
5. **DTO 定义**：完整的数据传输对象
6. **配置类**：RestClient 配置

至此，三个微服务的所有核心组件设计完成。下一步将编写第四、五、六部分的设计文档。
