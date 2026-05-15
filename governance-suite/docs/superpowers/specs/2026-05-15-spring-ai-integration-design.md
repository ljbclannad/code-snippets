# Spring AI 统一集成设计文档

**日期**: 2026-05-15  
**作者**: Claude (Opus 4.7)  
**状态**: 待审查

## 1. 概述

### 1.1 目标

将 `ai-chat-service` 的 `DeepSeekClient` 从手动 HTTP 调用（RestClient）迁移到 Spring AI 的 `ChatModel` 接口，实现与 `vector-memory-service` 的技术栈统一，同时完全保留现有的 Token 监控功能。

### 1.2 背景

当前项目中存在两种调用 AI 模型的方式：
- **vector-memory-service**: 使用 Spring AI 的 `ChatModel` 接口
- **ai-chat-service**: 使用 `RestClient` 手动调用 HTTP API

这种不一致导致：
- 代码风格不统一
- 需要维护手动定义的 DTO 类
- 无法利用 Spring AI 的自动配置和抽象能力

### 1.3 设计原则

- **最小改动**: 只修改必要的部分，降低风险
- **保留监控**: 完全保留现有的 Token 监控和预算管理功能
- **向后兼容**: 保持接口和响应格式不变
- **快速验证**: 改动范围小，可以快速完成和测试

## 2. 架构设计

### 2.1 架构变化

**现状**:
```
DeepSeekClient → RestClient → HTTP API → DeepSeek
                ↓
         手动构建 JSON 请求
         手动解析 JSON 响应
```

**目标**:
```
DeepSeekClient → ChatModel (Spring AI) → DeepSeek
                ↓
         自动处理请求/响应
         统一的消息抽象
```

### 2.2 保持不变的组件

以下组件完全不修改：
- `ContextBudgetManager` - 上下文预算管理
- `TokenMonitor` - Token 使用监控
- `ConversationHistoryManager` - 对话历史管理
- `AiChatService` - 业务服务层
- 所有 DTO 类（`ChatRequest`, `DifyResponse` 等）

## 3. 详细设计

### 3.1 依赖管理

**文件**: `ai-chat-service/pom.xml`

**添加依赖**:
```xml
<!-- Spring AI OpenAI (用于 DeepSeek 兼容) -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-openai-spring-boot-starter</artifactId>
</dependency>
```

**说明**:
- 复用项目根 `pom.xml` 中已定义的 `spring-ai.version: 1.0.0-M3`
- 与 `vector-memory-service` 保持版本一致
- Spring Boot Starter 会自动配置 `ChatModel` Bean

### 3.2 配置管理

**文件**: `ai-chat-service/src/main/resources/application.yml`

**添加 Spring AI 配置**:
```yaml
spring:
  ai:
    openai:
      api-key: ${DEEPSEEK_API_KEY:your-deepseek-api-key}
      base-url: https://api.deepseek.com/v1
      chat:
        options:
          model: ${DEEPSEEK_MODEL:deepseek-chat}
          temperature: 0.7
          max-tokens: 2000
```

**保留现有配置**（向后兼容）:
```yaml
# DeepSeek API Configuration (保留用于其他可能的用途)
deepseek:
  api:
    base-url: ${DEEPSEEK_API_BASE_URL:https://api.deepseek.com}
    key: ${DEEPSEEK_API_KEY:your-deepseek-api-key}
    timeout: 60000
  model: ${DEEPSEEK_MODEL:deepseek-chat}
```

**配置说明**:
- Spring AI 会自动创建名为 `openAiChatModel` 的 Bean
- 复用现有的环境变量 `DEEPSEEK_API_KEY` 和 `DEEPSEEK_MODEL`
- 保留旧配置以防其他地方使用（如 `RestClientConfig`）
- 超时配置由 Spring AI 内部管理

### 3.3 DeepSeekClient 重构

**文件**: `ai-chat-service/src/main/java/com/lx/governance/aichat/client/DeepSeekClient.java`

#### 3.3.1 依赖注入变更

**删除**:
```java
private final RestClient deepSeekRestClient;
```

**添加**:
```java
private final ChatModel chatModel;
```

**构造函数修改**:
```java
public DeepSeekClient(
    @Qualifier("openAiChatModel") ChatModel chatModel,  // 新增
    ContextBudgetManager contextBudgetManager,
    TokenMonitor tokenMonitor,
    ContextBudgetProperties properties) {
    this.chatModel = chatModel;
    this.contextBudgetManager = contextBudgetManager;
    this.tokenMonitor = tokenMonitor;
    this.properties = properties;
}
```

#### 3.3.2 chat() 方法重构

**核心逻辑**:
```java
public DifyResponse chat(ChatRequest request, UserProfileDTO userProfile, List<MemoryItemDTO> memories) {
    long startTime = System.currentTimeMillis();
    
    try {
        log.info("Calling DeepSeek API for customer: {}, conversationId: {}",
                request.getCustomerId(), request.getConversationId());

        // 1. 构建系统提示词（保持不变）
        String systemPrompt = buildSystemPrompt(request, userProfile, memories);
        
        // 2. 构建消息列表（使用 Spring AI）
        List<Message> messages = List.of(
            new SystemMessage(systemPrompt),
            new UserMessage(request.getMessage())
        );
        
        // 3. 配置选项
        OpenAiChatOptions options = OpenAiChatOptions.builder()
            .withModel(model)
            .withTemperature(0.7)
            .withMaxTokens(2000)
            .build();
        
        // 4. 调用 ChatModel
        ChatResponse response = chatModel.call(new Prompt(messages, options));
        
        // 5. 提取 Token 使用信息
        Usage usage = response.getMetadata().getUsage();
        
        long duration = System.currentTimeMillis() - startTime;
        log.info("DeepSeek API call completed in {}ms, tokens: prompt={}, completion={}, total={}",
            duration,
            usage.getPromptTokens(),
            usage.getGenerationTokens(),
            usage.getTotalTokens());
        
        // 6. 记录 Token 使用（保持不变）
        recordTokenUsage(usage, duration);
        
        // 7. 转换响应格式（保持不变）
        return convertToDifyResponse(response, request.getConversationId());
        
    } catch (Exception e) {
        long duration = System.currentTimeMillis() - startTime;
        log.error("DeepSeek API call failed after {}ms for customer: {}",
                duration, request.getCustomerId(), e);
        throw new RuntimeException("Failed to call DeepSeek API: " + e.getMessage(), e);
    }
}
```

#### 3.3.3 辅助方法调整

**buildSystemPrompt() 方法** - 保持不变:
```java
private String buildSystemPrompt(ChatRequest request, UserProfileDTO userProfile, List<MemoryItemDTO> memories) {
    return contextBudgetManager.buildBudgetedSystemPrompt(
            userProfile,
            memories,
            request.getCustomerId(),
            request.getConversationId(),
            request.getMessage());
}
```

**recordTokenUsage() 方法** - 签名修改:
```java
private void recordTokenUsage(Usage usage, long duration) {
    if (usage == null) {
        return;
    }

    TokenUsageMetrics metrics = TokenUsageMetrics.builder()
            .timestamp(LocalDateTime.now())
            // 从 ContextBudgetManager 获取各部分的 token 使用情况
            .coreProfileTokens(contextBudgetManager.getLastCoreProfileTokens())
            .memoriesTokens(contextBudgetManager.getLastMemoriesTokens())
            .conversationTokens(contextBudgetManager.getLastConversationTokens())
            .extendedProfileTokens(contextBudgetManager.getLastExtendedProfileTokens())
            .systemPromptTokens(contextBudgetManager.getLastTotalTokens())
            // API 返回的 token 使用情况
            .promptTokens((int) usage.getPromptTokens())
            .completionTokens((int) usage.getGenerationTokens())  // 注意：getGenerationTokens()
            .totalTokens((int) usage.getTotalTokens())
            // 性能指标
            .contextBuildTime(duration)
            .apiResponseTime(duration)
            .build();

    // 检查是否超预算
    int totalBudget = properties.getTotalBudget();
    metrics.setOverBudget((int) usage.getPromptTokens() > totalBudget);

    // 检查是否需要告警
    if (properties.getAlert().isEnabled()) {
        int promptThreshold = properties.getAlert().getPromptTokensThreshold();
        int totalThreshold = properties.getAlert().getTotalTokensThreshold();
        metrics.setShouldAlert(
                (int) usage.getPromptTokens() > promptThreshold ||
                (int) usage.getTotalTokens() > totalThreshold
        );
    }

    // 记录到监控系统
    tokenMonitor.recordTokenUsage(metrics);
}
```

**关键变化**:
- 参数从 `DeepSeekResponse` 改为 Spring AI 的 `Usage`
- 使用 `usage.getGenerationTokens()` 而不是 `getCompletionTokens()`
- 其他逻辑完全保持不变

**convertToDifyResponse() 方法** - 修改:
```java
private DifyResponse convertToDifyResponse(ChatResponse chatResponse, String conversationId) {
    DifyResponse difyResponse = new DifyResponse();

    // 提取回答内容
    String answer = chatResponse.getResult().getOutput().getContent();
    difyResponse.setAnswer(answer);

    // 使用现有的 conversationId 或生成新的
    difyResponse.setConversationId(conversationId != null ? conversationId : "conv-" + UUID.randomUUID().toString());
    difyResponse.setMessageId("msg-" + UUID.randomUUID().toString());

    // 设置元数据
    DifyResponse.Metadata metadata = new DifyResponse.Metadata();
    Usage usage = chatResponse.getMetadata().getUsage();
    if (usage != null) {
        metadata.setUsage(String.format("prompt_tokens=%d, completion_tokens=%d, total_tokens=%d",
                usage.getPromptTokens(),
                usage.getGenerationTokens(),
                usage.getTotalTokens()));
    }
    difyResponse.setMetadata(metadata);

    return difyResponse;
}
```

#### 3.3.4 删除的代码

**删除内部类**:
- `DeepSeekRequest`
- `Message` (内部定义的)
- `DeepSeekResponse`
- `Choice`
- `Usage` (内部定义的)

**删除方法**:
- `buildDeepSeekRequest()` - 不再需要手动构建请求

**删除导入**:
```java
// 删除
import org.springframework.web.client.RestClient;
import com.fasterxml.jackson.annotation.JsonProperty;
```

**添加导入**:
```java
// 添加 Spring AI 导入
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.beans.factory.annotation.Qualifier;
```

### 3.4 RestClientConfig 清理

**文件**: `ai-chat-service/src/main/java/com/lx/governance/aichat/config/RestClientConfig.java`

**变更**:
```java
@Configuration
public class RestClientConfig {

    // 保留 Dify 相关配置（如果未来需要）
    @Value("${dify.api.base-url}")
    private String difyBaseUrl;
    
    @Value("${dify.api.key}")
    private String difyApiKey;
    
    @Value("${dify.api.timeout:30000}")
    private int difyTimeout;

    // 删除 DeepSeek 相关字段
    // @Value("${deepseek.api.base-url:https://api.deepseek.com}")
    // private String deepSeekBaseUrl;
    // @Value("${deepseek.api.key}")
    // private String deepSeekApiKey;
    // @Value("${deepseek.api.timeout:60000}")
    // private int deepSeekTimeout;

    @Bean
    public RestClient difyRestClient() {
        return RestClient.builder()
                .baseUrl(difyBaseUrl)
                .defaultHeader("Authorization", "Bearer " + difyApiKey)
                .defaultHeader("Content-Type", "application/json")
                .requestFactory(clientHttpRequestFactory(difyTimeout))
                .build();
    }

    // 删除 deepSeekRestClient() Bean 方法
    
    private ClientHttpRequestFactory clientHttpRequestFactory(int timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return factory;
    }
}
```

**说明**:
- 删除 `deepSeekRestClient` Bean（不再使用）
- 保留 `difyRestClient` Bean（可能有其他地方使用）
- 如果确认 Dify 完全不用，可以整个类都删除

## 4. 错误处理和重试

### 4.1 保持现有行为

- Spring AI 内部已经处理了基本的 HTTP 错误
- 保留 `DeepSeekClient.chat()` 方法中的 try-catch 块
- 异常信息格式保持不变：`"Failed to call DeepSeek API: " + e.getMessage()`

### 4.2 可选增强（不在本次范围）

- 可以后续添加 `@Retryable` 注解实现重试
- 可以配置 Spring AI 的超时和重试策略

## 5. 测试验证

### 5.1 验证点

1. ✅ 服务启动成功，`openAiChatModel` Bean 正确注入
2. ✅ 调用 `/api/ai-chat/chat` 接口返回正常响应
3. ✅ Token 使用信息正确记录到 `TokenMonitor`
4. ✅ `ContextBudgetManager` 的各部分 Token 统计正确
5. ✅ 预算告警机制正常工作
6. ✅ 对话历史保存功能正常
7. ✅ 响应格式与之前保持一致（`DifyResponse`）

### 5.2 测试步骤

**1. 启动依赖服务**:
```bash
docker compose up -d
```

**2. 启动 ai-chat-service**:
```bash
mvn -q -pl ai-chat-service spring-boot:run
```

**3. 测试基本调用**:
```bash
curl -X POST http://localhost:8103/api/ai-chat/chat \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 1001" \
  -d '{
    "customerId": "1001",
    "message": "我最近总是失眠，有什么建议吗？"
  }'
```

**4. 验证响应格式**:
```json
{
  "success": true,
  "code": 200,
  "message": "success",
  "traceId": "...",
  "data": {
    "answer": "...",
    "conversationId": "conv-...",
    "messageId": "msg-...",
    "metadata": {
      "usage": "prompt_tokens=..., completion_tokens=..., total_tokens=..."
    }
  }
}
```

**5. 检查日志**:
- 确认 Token 使用信息正确输出
- 确认 `ContextBudgetManager` 的各部分统计正确
- 确认没有错误或警告

### 5.3 回归测试

- 测试对话历史功能
- 测试记忆检索功能
- 测试 Token 预算告警
- 测试异常情况处理

## 6. 影响范围

### 6.1 修改的文件（共 4 个）

1. `ai-chat-service/pom.xml` - 添加依赖
2. `ai-chat-service/src/main/resources/application.yml` - 添加配置
3. `ai-chat-service/src/main/java/com/lx/governance/aichat/client/DeepSeekClient.java` - 核心重构
4. `ai-chat-service/src/main/java/com/lx/governance/aichat/config/RestClientConfig.java` - 清理（可选）

### 6.2 不修改的文件

- `ContextBudgetManager.java` ✅
- `TokenMonitor.java` ✅
- `AiChatService.java` ✅
- `ConversationHistoryManager.java` ✅
- 所有 DTO 类（除了 DeepSeekClient 内部的）✅

### 6.3 代码行数变化

- **删除**: ~60 行（内部 DTO 类）
- **修改**: ~40 行（chat 方法和辅助方法）
- **新增**: ~10 行（依赖和配置）
- **净减少**: ~10 行代码

## 7. 风险评估

### 7.1 低风险

- ✅ 改动范围小，只涉及 `DeepSeekClient` 一个类
- ✅ 不影响业务逻辑和监控功能
- ✅ Spring AI 是成熟的框架，稳定性有保障
- ✅ 可以快速回滚（保留旧代码作为备份）

### 7.2 潜在问题

- ⚠️ Spring AI 的 `Usage` 接口可能与 DeepSeek API 返回的字段有细微差异
  - **缓解措施**: 在测试阶段仔细验证 Token 统计的准确性
- ⚠️ 超时配置由 Spring AI 管理，可能与原来的 60 秒不同
  - **缓解措施**: 如果需要，可以通过 Spring AI 配置调整超时时间

## 8. 实施计划

### 8.1 实施步骤

1. **准备阶段** (5 分钟)
   - 创建 Git 分支：`feature/spring-ai-integration`
   - 备份当前的 `DeepSeekClient.java`

2. **代码修改** (30 分钟)
   - 修改 `pom.xml` 添加依赖
   - 修改 `application.yml` 添加配置
   - 重构 `DeepSeekClient.java`
   - 清理 `RestClientConfig.java`

3. **本地测试** (20 分钟)
   - 启动服务验证 Bean 注入
   - 执行基本功能测试
   - 验证 Token 监控功能
   - 检查日志输出

4. **回归测试** (15 分钟)
   - 测试对话历史
   - 测试记忆检索
   - 测试异常处理

5. **代码审查和提交** (10 分钟)
   - 自我审查代码
   - 提交代码并推送
   - 更新文档

### 8.2 预计时间

- **总计**: 约 1.5 小时
- **核心开发**: 30 分钟
- **测试验证**: 35 分钟

## 9. 后续优化建议

本次重构采用最小改动方案，后续可以考虑以下优化：

1. **完全拥抱 Spring AI**
   - 重构 `ContextBudgetManager` 返回 `List<Message>`
   - 删除 `DifyResponse` 兼容层，统一使用 Spring AI 响应格式

2. **增强错误处理**
   - 添加 `@Retryable` 注解实现自动重试
   - 细化异常分类（可重试 vs 不可重试）

3. **配置优化**
   - 将 Spring AI 配置迁移到 Nacos 配置中心
   - 支持动态调整模型参数（temperature, max_tokens）

4. **监控增强**
   - 添加 Spring AI 的 Metrics 集成
   - 实现更细粒度的性能监控

## 10. 总结

本设计采用最小改动方案，将 `ai-chat-service` 的 `DeepSeekClient` 从手动 HTTP 调用迁移到 Spring AI `ChatModel`，实现了：

- ✅ 技术栈统一（与 vector-memory-service 一致）
- ✅ 代码简化（减少 ~10 行代码）
- ✅ 完全保留 Token 监控功能
- ✅ 保持接口和响应格式不变
- ✅ 低风险、快速实施（约 1.5 小时）

这是一个稳健的重构方案，既实现了技术改进，又最大程度降低了风险。
