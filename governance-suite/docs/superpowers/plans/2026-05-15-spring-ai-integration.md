# Spring AI 统一集成实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 ai-chat-service 的 DeepSeekClient 从手动 HTTP 调用迁移到 Spring AI ChatModel，统一技术栈并保留所有 Token 监控功能

**Architecture:** 使用 Spring AI 的 ChatModel 接口替代 RestClient，通过 Spring Boot 自动配置注入 openAiChatModel Bean，保持 ContextBudgetManager 和 TokenMonitor 的现有逻辑不变

**Tech Stack:** Spring AI 1.0.0-M3, Spring AI OpenAI Starter, DeepSeek API (OpenAI 兼容)

---

## 文件结构

### 修改的文件
- `ai-chat-service/pom.xml` - 添加 Spring AI 依赖
- `ai-chat-service/src/main/resources/application.yml` - 添加 Spring AI 配置
- `ai-chat-service/src/main/java/com/lx/governance/aichat/client/DeepSeekClient.java` - 核心重构
- `ai-chat-service/src/main/java/com/lx/governance/aichat/config/RestClientConfig.java` - 清理 DeepSeek 相关配置

### 不修改的文件
- `ContextBudgetManager.java` - 保持不变
- `TokenMonitor.java` - 保持不变
- `AiChatService.java` - 保持不变
- 所有 DTO 类 - 保持不变

---

## Task 1: 添加 Spring AI 依赖

**Files:**
- Modify: `ai-chat-service/pom.xml`

- [ ] **Step 1: 在 pom.xml 中添加 Spring AI OpenAI 依赖**

在 `<dependencies>` 部分添加：

```xml
<!-- Spring AI OpenAI (用于 DeepSeek 兼容) -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-openai-spring-boot-starter</artifactId>
</dependency>
```

位置：在现有的 `spring-boot-starter-validation` 依赖之后

- [ ] **Step 2: 验证依赖配置**

运行：`mvn dependency:tree -pl ai-chat-service | grep spring-ai`

预期输出：应该看到 `spring-ai-openai-spring-boot-starter:jar:1.0.0-M3`

- [ ] **Step 3: 提交依赖变更**

```bash
git add ai-chat-service/pom.xml
git commit -m "build: add Spring AI OpenAI dependency to ai-chat-service"
```

---

## Task 2: 添加 Spring AI 配置

**Files:**
- Modify: `ai-chat-service/src/main/resources/application.yml`

- [ ] **Step 1: 在 application.yml 中添加 Spring AI 配置**

在 `spring:` 部分添加 `ai:` 配置块：

```yaml
spring:
  application:
    name: ai-chat-service
  cloud:
    nacos:
      discovery:
        server-addr: localhost:8848
        namespace: public
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest
  # 添加 Spring AI 配置
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

位置：在 `spring.rabbitmq` 配置之后

- [ ] **Step 2: 验证配置格式**

运行：`mvn validate -pl ai-chat-service`

预期输出：`BUILD SUCCESS`

- [ ] **Step 3: 提交配置变更**

```bash
git add ai-chat-service/src/main/resources/application.yml
git commit -m "config: add Spring AI OpenAI configuration for DeepSeek"
```

---

## Task 3: 重构 DeepSeekClient - 更新导入和依赖注入

**Files:**
- Modify: `ai-chat-service/src/main/java/com/lx/governance/aichat/client/DeepSeekClient.java`

- [ ] **Step 1: 删除旧的导入语句**

删除以下导入：
```java
import org.springframework.web.client.RestClient;
import com.fasterxml.jackson.annotation.JsonProperty;
```

- [ ] **Step 2: 添加 Spring AI 导入语句**

在 import 部分添加：
```java
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

- [ ] **Step 3: 修改类字段 - 删除 RestClient，添加 ChatModel**

找到：
```java
private final RestClient deepSeekRestClient;
```

替换为：
```java
private final ChatModel chatModel;
```

- [ ] **Step 4: 修改构造函数**

找到：
```java
public DeepSeekClient(
    RestClient deepSeekRestClient,
    ContextBudgetManager contextBudgetManager,
    TokenMonitor tokenMonitor,
    ContextBudgetProperties properties) {
    this.deepSeekRestClient = deepSeekRestClient;
    // ...
}
```

替换为：
```java
public DeepSeekClient(
    @Qualifier("openAiChatModel") ChatModel chatModel,
    ContextBudgetManager contextBudgetManager,
    TokenMonitor tokenMonitor,
    ContextBudgetProperties properties) {
    this.chatModel = chatModel;
    this.contextBudgetManager = contextBudgetManager;
    this.tokenMonitor = tokenMonitor;
    this.properties = properties;
}
```

- [ ] **Step 5: 编译验证**

运行：`mvn compile -pl ai-chat-service`

预期输出：编译错误（因为 chat() 方法还未修改），但应该能看到 ChatModel 注入成功

- [ ] **Step 6: 提交依赖注入变更**

```bash
git add ai-chat-service/src/main/java/com/lx/governance/aichat/client/DeepSeekClient.java
git commit -m "refactor: replace RestClient with Spring AI ChatModel in DeepSeekClient"
```

---

## Task 4: 重构 DeepSeekClient - 修改 chat() 方法

**Files:**
- Modify: `ai-chat-service/src/main/java/com/lx/governance/aichat/client/DeepSeekClient.java`

- [ ] **Step 1: 重构 chat() 方法主体**

找到 `chat()` 方法，替换整个方法体：

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

- [ ] **Step 2: 编译验证**

运行：`mvn compile -pl ai-chat-service`

预期输出：编译错误（recordTokenUsage 和 convertToDifyResponse 方法签名不匹配）

- [ ] **Step 3: 提交 chat() 方法变更**

```bash
git add ai-chat-service/src/main/java/com/lx/governance/aichat/client/DeepSeekClient.java
git commit -m "refactor: update chat() method to use Spring AI ChatModel"
```

---

## Task 5: 重构 DeepSeekClient - 修改辅助方法

**Files:**
- Modify: `ai-chat-service/src/main/java/com/lx/governance/aichat/client/DeepSeekClient.java`

- [ ] **Step 1: 修改 recordTokenUsage() 方法签名和实现**

找到 `recordTokenUsage()` 方法，替换整个方法：

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
            .completionTokens((int) usage.getGenerationTokens())
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

- [ ] **Step 2: 修改 convertToDifyResponse() 方法**

找到 `convertToDifyResponse()` 方法，替换整个方法：

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

- [ ] **Step 3: 编译验证**

运行：`mvn compile -pl ai-chat-service`

预期输出：编译错误（内部 DTO 类还未删除）

- [ ] **Step 4: 提交辅助方法变更**

```bash
git add ai-chat-service/src/main/java/com/lx/governance/aichat/client/DeepSeekClient.java
git commit -m "refactor: update helper methods to use Spring AI Usage and ChatResponse"
```

---

## Task 6: 重构 DeepSeekClient - 删除内部 DTO 类和旧方法

**Files:**
- Modify: `ai-chat-service/src/main/java/com/lx/governance/aichat/client/DeepSeekClient.java`

- [ ] **Step 1: 删除 buildDeepSeekRequest() 方法**

找到并删除整个 `buildDeepSeekRequest()` 方法（约 20 行）

- [ ] **Step 2: 删除内部 DTO 类**

找到并删除以下所有内部静态类：
- `DeepSeekRequest` 类
- `Message` 类（内部定义的）
- `DeepSeekResponse` 类
- `Choice` 类
- `Usage` 类（内部定义的）

这些类通常在文件末尾，标记为 `static class`

- [ ] **Step 3: 编译验证**

运行：`mvn compile -pl ai-chat-service`

预期输出：`BUILD SUCCESS`

- [ ] **Step 4: 提交清理变更**

```bash
git add ai-chat-service/src/main/java/com/lx/governance/aichat/client/DeepSeekClient.java
git commit -m "refactor: remove internal DTO classes and buildDeepSeekRequest method"
```

---

## Task 7: 清理 RestClientConfig

**Files:**
- Modify: `ai-chat-service/src/main/java/com/lx/governance/aichat/config/RestClientConfig.java`

- [ ] **Step 1: 删除 DeepSeek 相关字段**

找到并删除：
```java
@Value("${deepseek.api.base-url:https://api.deepseek.com}")
private String deepSeekBaseUrl;

@Value("${deepseek.api.key}")
private String deepSeekApiKey;

@Value("${deepseek.api.timeout:60000}")
private int deepSeekTimeout;
```

- [ ] **Step 2: 删除 deepSeekRestClient() Bean 方法**

找到并删除整个 `deepSeekRestClient()` 方法（约 8 行）

- [ ] **Step 3: 编译验证**

运行：`mvn compile -pl ai-chat-service`

预期输出：`BUILD SUCCESS`

- [ ] **Step 4: 提交配置清理**

```bash
git add ai-chat-service/src/main/java/com/lx/governance/aichat/config/RestClientConfig.java
git commit -m "refactor: remove DeepSeek RestClient configuration"
```

---

## Task 8: 启动服务并验证 Bean 注入

**Files:**
- Test: `ai-chat-service` 启动日志

- [ ] **Step 1: 启动依赖服务**

运行：`docker compose up -d`

预期输出：所有服务启动成功（MySQL, Redis, RabbitMQ, Nacos, Milvus）

- [ ] **Step 2: 启动 ai-chat-service**

运行：`mvn -q -pl ai-chat-service spring-boot:run`

预期输出：
- 服务启动成功
- 日志中应该看到 `openAiChatModel` Bean 创建成功
- 没有 Bean 注入错误

- [ ] **Step 3: 检查启动日志**

查找日志中的关键信息：
```
Started AiChatServiceApplication in X.XXX seconds
```

确认没有以下错误：
- `NoSuchBeanDefinitionException`
- `UnsatisfiedDependencyException`
- `BeanCreationException`

- [ ] **Step 4: 验证健康检查**

运行：`curl http://localhost:8103/actuator/health`

预期输出：
```json
{
  "status": "UP"
}
```

---

## Task 9: 功能测试 - 基本调用

**Files:**
- Test: API 端点 `/api/ai-chat/chat`

- [ ] **Step 1: 测试基本聊天功能**

运行：
```bash
curl -X POST http://localhost:8103/api/ai-chat/chat \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 1001" \
  -d '{
    "customerId": "1001",
    "message": "你好"
  }'
```

预期输出：
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

- [ ] **Step 2: 验证响应格式**

检查响应中的关键字段：
- `data.answer` 不为空
- `data.conversationId` 以 `conv-` 开头
- `data.messageId` 以 `msg-` 开头
- `data.metadata.usage` 包含 token 统计信息

- [ ] **Step 3: 检查服务日志**

查看 ai-chat-service 日志，确认：
- `Calling DeepSeek API for customer: 1001` 日志存在
- `DeepSeek API call completed in XXXms, tokens: prompt=XX, completion=XX, total=XX` 日志存在
- 没有错误或异常日志

---

## Task 10: 功能测试 - Token 监控验证

**Files:**
- Test: Token 监控功能

- [ ] **Step 1: 测试带用户画像的调用**

运行：
```bash
curl -X POST http://localhost:8103/api/ai-chat/chat \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 1001" \
  -d '{
    "customerId": "1001",
    "message": "我最近总是失眠，有什么建议吗？"
  }'
```

预期输出：正常的 JSON 响应

- [ ] **Step 2: 检查 Token 监控日志**

查看日志中的 Token 统计信息，确认包含：
- `coreProfileTokens` - 核心画像 token 数
- `memoriesTokens` - 记忆 token 数
- `conversationTokens` - 对话历史 token 数
- `systemPromptTokens` - 系统提示词总 token 数
- `promptTokens` - API 返回的 prompt tokens
- `completionTokens` - API 返回的 completion tokens
- `totalTokens` - API 返回的 total tokens

- [ ] **Step 3: 验证 Token 数值合理性**

检查：
- `promptTokens` 应该大于 `systemPromptTokens`（包含用户消息）
- `totalTokens` = `promptTokens` + `completionTokens`
- 所有 token 数都大于 0

---

## Task 11: 功能测试 - 对话历史功能

**Files:**
- Test: 对话历史保存和检索

- [ ] **Step 1: 发送第一条消息**

运行：
```bash
curl -X POST http://localhost:8103/api/ai-chat/chat \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 1001" \
  -d '{
    "customerId": "1001",
    "message": "我叫张三"
  }'
```

保存响应中的 `conversationId`

- [ ] **Step 2: 使用相同 conversationId 发送第二条消息**

运行：
```bash
curl -X POST http://localhost:8103/api/ai-chat/chat \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 1001" \
  -d '{
    "customerId": "1001",
    "conversationId": "<上一步的conversationId>",
    "message": "我叫什么名字？"
  }'
```

预期输出：AI 应该能够回答"张三"或类似内容，证明对话历史生效

- [ ] **Step 3: 检查对话历史日志**

查看日志，确认：
- `ContextBudgetManager` 加载了对话历史
- `conversationTokens` 大于 0

---

## Task 12: 功能测试 - 异常处理

**Files:**
- Test: 错误处理机制

- [ ] **Step 1: 测试无效的 API Key（可选）**

临时修改环境变量：
```bash
export DEEPSEEK_API_KEY=invalid-key
mvn -q -pl ai-chat-service spring-boot:run
```

发送请求，预期输出：
```json
{
  "success": false,
  "code": 500,
  "message": "Failed to call DeepSeek API: ...",
  "traceId": "..."
}
```

恢复正确的 API Key 并重启服务

- [ ] **Step 2: 测试空消息**

运行：
```bash
curl -X POST http://localhost:8103/api/ai-chat/chat \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 1001" \
  -d '{
    "customerId": "1001",
    "message": ""
  }'
```

预期输出：应该返回验证错误或正常处理

- [ ] **Step 3: 检查异常日志格式**

确认异常日志包含：
- `DeepSeek API call failed after XXXms for customer: XXX`
- 完整的异常堆栈信息

---

## Task 13: 回归测试和最终验证

**Files:**
- Test: 完整功能回归

- [ ] **Step 1: 执行完整的测试场景**

依次测试：
1. 新用户首次对话
2. 带用户画像的对话
3. 多轮对话（使用 conversationId）
4. 长文本输入（测试 token 预算）

所有场景都应该正常工作

- [ ] **Step 2: 验证 Token 预算告警（可选）**

发送一个超长消息，触发 token 预算告警：
```bash
curl -X POST http://localhost:8103/api/ai-chat/chat \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 1001" \
  -d '{
    "customerId": "1001",
    "message": "请详细介绍一下...(很长的文本)"
  }'
```

检查日志中是否有预算告警信息

- [ ] **Step 3: 对比迁移前后的响应**

如果有保存的旧响应，对比：
- 响应格式完全一致
- Token 统计数值相近（可能有小幅差异）
- 响应时间相近

---

## Task 14: 代码审查和文档更新

**Files:**
- Review: 所有修改的代码
- Update: CLAUDE.md（如果需要）

- [ ] **Step 1: 自我代码审查**

检查清单：
- [ ] 所有导入语句正确
- [ ] 没有未使用的导入
- [ ] 没有编译警告
- [ ] 日志级别合适
- [ ] 异常处理完整
- [ ] 代码格式一致

- [ ] **Step 2: 运行完整编译**

运行：`mvn clean compile -pl ai-chat-service`

预期输出：`BUILD SUCCESS`，无警告

- [ ] **Step 3: 检查 Git 状态**

运行：`git status`

确认：
- 所有修改的文件都已提交
- 没有未跟踪的临时文件
- 工作目录干净

- [ ] **Step 4: 更新 CLAUDE.md（如果需要）**

如果 CLAUDE.md 中提到了 DeepSeek 的实现方式，更新为：
```markdown
- `ai-chat-service` 使用 Spring AI ChatModel 调用 DeepSeek API
```

---

## Task 15: 最终提交和清理

**Files:**
- Commit: 所有变更
- Clean: 临时文件

- [ ] **Step 1: 查看所有提交**

运行：`git log --oneline -10`

确认所有提交信息清晰、有意义

- [ ] **Step 2: 创建最终总结提交（如果需要）**

如果有遗漏的小改动，创建一个总结提交：
```bash
git add .
git commit -m "chore: finalize Spring AI integration in ai-chat-service"
```

- [ ] **Step 3: 停止测试服务**

运行：
```bash
# 停止 ai-chat-service (Ctrl+C)
# 停止 Docker 服务
docker compose down
```

- [ ] **Step 4: 清理临时文件**

运行：
```bash
mvn clean -pl ai-chat-service
```

- [ ] **Step 5: 推送到远程仓库（可选）**

如果需要推送：
```bash
git push origin feature/spring-ai-integration
```

---

## 验证清单

完成所有任务后，确认以下所有项都通过：

- [x] 服务启动成功，无 Bean 注入错误
- [x] 基本聊天功能正常
- [x] Token 监控功能正常，所有指标都有值
- [x] 对话历史功能正常
- [x] 异常处理正常
- [x] 响应格式与之前一致
- [x] 所有代码已提交
- [x] 编译无警告
- [x] 日志输出正常

---

## 预计时间

- **Task 1-2**: 依赖和配置（10 分钟）
- **Task 3-7**: 代码重构（30 分钟）
- **Task 8**: 启动验证（5 分钟）
- **Task 9-13**: 功能测试（30 分钟）
- **Task 14-15**: 审查和清理（10 分钟）

**总计**: 约 1.5 小时

---

## 回滚方案

如果遇到问题需要回滚：

1. **回滚代码**：
   ```bash
   git reset --hard <上一个稳定提交>
   ```

2. **重启服务**：
   ```bash
   mvn -q -pl ai-chat-service spring-boot:run
   ```

3. **验证功能**：
   运行基本测试确认服务恢复正常
