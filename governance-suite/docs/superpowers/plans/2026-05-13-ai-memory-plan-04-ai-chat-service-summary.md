# AI 记忆功能实施计划 - Plan 4: ai-chat-service (概要)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 实现 ai-chat-service 微服务，协调用户画像、向量记忆和 Dify API，提供完整的 AI 聊天功能

**Architecture:** 接收聊天请求 → 并行获取用户画像和向量记忆 → 调用 Dify API → 异步触发记忆提取 → 返回响应

**Tech Stack:** Spring Boot 3.2.5, RestClient, RabbitMQ, CompletableFuture

**预计时间**: 4-6 小时

---

## 任务概览

### Task 1: 创建模块基础结构
- 创建 pom.xml
- 创建 Application 主类
- 创建 application.yml（配置 Dify API）

### Task 2: 创建 DTO
- ChatRequest / ChatResponse
- DifyRequest / DifyResponse
- UserProfileDTO（复用）
- MemoryItemDTO（复用）

### Task 3: 配置类
- RestClientConfig（配置 Dify RestClient）
- RabbitMQConfig
- AsyncConfig

### Task 4: 实现 UserProfileClient
- 封装 RabbitMQ 请求-响应模式
- requestUserProfile() - 发送请求并等待响应
- 使用 CompletableFuture 实现超时控制

### Task 5: 实现 VectorMemoryClient
- 封装 RabbitMQ 请求-响应模式
- requestGroupMemories() - 请求群组记忆
- requestUserMemories() - 请求用户记忆

### Task 6: 实现 DifyService
- 封装 RestClient 调用 Dify API
- chat() - 发送聊天请求
- buildDifyRequest() - 构建请求体
- 处理 Dify 响应

### Task 7: 实现 AiChatService
- handleChatRequest() - 主流程编排
- 并行获取用户画像和向量记忆
- 调用 Dify API
- 异步触发记忆提取

### Task 8: 实现 REST Controller
- AiChatController
- POST /api/chat - 聊天接口
- 参数验证
- 异常处理

### Task 9: 添加日志配置
- logback-spring.xml

### Task 10: 构建和测试
- 编译验证
- 启动测试
- 端到端测试

---

## 关键实现要点

### 并行获取上下文

```java
public ChatResponse handleChatRequest(ChatRequest request) {
    String correlationId = UUID.randomUUID().toString();
    
    // 并行获取用户画像和向量记忆
    CompletableFuture<UserProfileDTO> profileFuture = 
        CompletableFuture.supplyAsync(() -> 
            userProfileClient.requestUserProfile(request.getCustomerId(), correlationId)
        );
    
    CompletableFuture<List<MemoryItemDTO>> memoriesFuture = 
        CompletableFuture.supplyAsync(() -> 
            vectorMemoryClient.requestGroupMemories(
                request.getGroupId(), 
                request.getCustomerId(), 
                request.getMessage(),
                correlationId
            )
        );
    
    // 等待两个结果
    CompletableFuture.allOf(profileFuture, memoriesFuture).join();
    
    UserProfileDTO profile = profileFuture.get();
    List<MemoryItemDTO> memories = memoriesFuture.get();
    
    // 调用 Dify
    DifyResponse difyResponse = difyService.chat(request, profile, memories);
    
    // 异步触发记忆提取
    triggerMemoryExtraction(request, difyResponse);
    
    return buildChatResponse(difyResponse);
}
```

### Dify API 调用

```java
@Service
@RequiredArgsConstructor
public class DifyService {
    
    private final RestClient difyRestClient;
    
    public DifyResponse chat(ChatRequest request, UserProfileDTO profile, List<MemoryItemDTO> memories) {
        DifyRequest difyRequest = DifyRequest.builder()
            .query(request.getMessage())
            .user(request.getCustomerId())
            .conversationId(request.getGroupId())
            .inputs(buildInputs(profile, memories))
            .build();
        
        return difyRestClient.post()
            .uri("/chat-messages")
            .body(difyRequest)
            .retrieve()
            .body(DifyResponse.class);
    }
    
    private Map<String, Object> buildInputs(UserProfileDTO profile, List<MemoryItemDTO> memories) {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("user_profile", profile);
        inputs.put("memories", memories);
        return inputs;
    }
}
```

### RabbitMQ 请求-响应模式

```java
@Service
@RequiredArgsConstructor
public class UserProfileClient {
    
    private final RabbitTemplate rabbitTemplate;
    private final Map<String, CompletableFuture<UserProfileDTO>> pendingRequests = new ConcurrentHashMap<>();
    
    public UserProfileDTO requestUserProfile(String customerId, String correlationId) {
        CompletableFuture<UserProfileDTO> future = new CompletableFuture<>();
        pendingRequests.put(correlationId, future);
        
        // 发送请求
        UserProfileRequestMessage request = new UserProfileRequestMessage();
        request.setCorrelationId(correlationId);
        request.setCustomerId(customerId);
        request.setTimestamp(System.currentTimeMillis());
        
        rabbitTemplate.convertAndSend(
            "user.profile.exchange",
            "user.profile.request",
            request
        );
        
        // 等待响应（超时 5 秒）
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            pendingRequests.remove(correlationId);
            throw new RuntimeException("获取用户画像超时");
        }
    }
    
    @RabbitListener(queues = "user.profile.response.#{T(java.util.UUID).randomUUID().toString()}")
    public void handleResponse(UserProfileResponseMessage response) {
        CompletableFuture<UserProfileDTO> future = pendingRequests.remove(response.getCorrelationId());
        if (future != null) {
            if (response.getSuccess()) {
                future.complete(response.getData());
            } else {
                future.completeExceptionally(new RuntimeException(response.getErrorMessage()));
            }
        }
    }
}
```

### REST API

```java
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@Slf4j
public class AiChatController {
    
    private final AiChatService aiChatService;
    
    @PostMapping
    public ResponseEntity<ChatResponse> chat(@RequestBody @Valid ChatRequest request) {
        log.info("收到聊天请求: groupId={}, customerId={}", 
            request.getGroupId(), request.getCustomerId());
        
        try {
            ChatResponse response = aiChatService.handleChatRequest(request);
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("处理聊天请求失败", e);
            return ResponseEntity.status(500)
                .body(ChatResponse.error("处理失败: " + e.getMessage()));
        }
    }
}
```

---

## 验证清单

- [ ] 模块结构完整
- [ ] RestClient 配置正确
- [ ] 用户画像获取功能正常
- [ ] 向量记忆获取功能正常
- [ ] Dify API 调用成功
- [ ] 记忆提取触发正常
- [ ] REST API 可访问
- [ ] 端到端流程正常
- [ ] 服务注册到 Nacos
- [ ] 所有更改已提交

---

## 下一步

**Plan 5: 集成测试和部署** - 完整的端到端测试和部署验证
