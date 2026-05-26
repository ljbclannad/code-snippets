# AI 上下文管理优化实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 优化 AI 聊天服务的上下文管理，确保发送给 DeepSeek API 的 prompt tokens 控制在 4000 以内，同时保持 AI 回复质量。

**Architecture:** 采用渐进式优化方案（方案 A），在现有 ContextBudgetManager 基础上增强功能。分两个阶段实施：阶段 1 实现配置化预算管理和 Token 监控；阶段 2 实现记忆分层检索和对话历史管理。

**Tech Stack:** Spring Boot, MyBatis-Plus, Milvus (向量数据库), DeepSeek API, MySQL

---

## 阶段 1：配置化和监控

### Task 1: 创建配置属性类

**Files:**
- Create: `ai-chat-service/src/main/java/com/lx/governance/aichat/config/ContextBudgetProperties.java`
- Create: `ai-chat-service/src/test/java/com/lx/governance/aichat/config/ContextBudgetPropertiesTest.java`

- [ ] **Step 1: 编写配置属性测试**

创建测试文件验证配置加载：

```java
package com.lx.governance.aichat.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
    "ai.context.total-budget=4000",
    "ai.context.user-message-reserve=500",
    "ai.context.core-profile-budget=800",
    "ai.context.relevant-memories-budget=1200",
    "ai.context.conversation-history-budget=600",
    "ai.context.extended-profile-budget=400",
    "ai.context.memory.max-count=10",
    "ai.context.memory.similarity-threshold=0.7",
    "ai.context.memory.short-term-days=3",
    "ai.context.memory.mid-term-days=30",
    "ai.context.memory.enable-deduplication=true",
    "ai.context.conversation.max-rounds=5",
    "ai.context.conversation.enabled=true",
    "ai.context.alert.enabled=true",
    "ai.context.alert.prompt-tokens-threshold=6000",
    "ai.context.alert.total-tokens-threshold=28000"
})
class ContextBudgetPropertiesTest {

    @Autowired
    private ContextBudgetProperties properties;

    @Test
    void shouldLoadBudgetConfiguration() {
        assertThat(properties.getTotalBudget()).isEqualTo(4000);
        assertThat(properties.getUserMessageReserve()).isEqualTo(500);
        assertThat(properties.getCoreProfileBudget()).isEqualTo(800);
        assertThat(properties.getRelevantMemoriesBudget()).isEqualTo(1200);
        assertThat(properties.getConversationHistoryBudget()).isEqualTo(600);
        assertThat(properties.getExtendedProfileBudget()).isEqualTo(400);
    }

    @Test
    void shouldLoadMemoryConfiguration() {
        ContextBudgetProperties.MemoryConfig memory = properties.getMemory();
        assertThat(memory.getMaxCount()).isEqualTo(10);
        assertThat(memory.getSimilarityThreshold()).isEqualTo(0.7);
        assertThat(memory.getShortTermDays()).isEqualTo(3);
        assertThat(memory.getMidTermDays()).isEqualTo(30);
        assertThat(memory.isEnableDeduplication()).isTrue();
    }

    @Test
    void shouldLoadConversationConfiguration() {
        ContextBudgetProperties.ConversationConfig conversation = properties.getConversation();
        assertThat(conversation.getMaxRounds()).isEqualTo(5);
        assertThat(conversation.isEnabled()).isTrue();
    }

    @Test
    void shouldLoadAlertConfiguration() {
        ContextBudgetProperties.AlertConfig alert = properties.getAlert();
        assertThat(alert.isEnabled()).isTrue();
        assertThat(alert.getPromptTokensThreshold()).isEqualTo(6000);
        assertThat(alert.getTotalTokensThreshold()).isEqualTo(28000);
    }

    @Test
    void shouldUseDefaultValues() {
        ContextBudgetProperties defaultProps = new ContextBudgetProperties();
        assertThat(defaultProps.getTotalBudget()).isEqualTo(4000);
        assertThat(defaultProps.getMemory().getMaxCount()).isEqualTo(10);
        assertThat(defaultProps.getConversation().getMaxRounds()).isEqualTo(5);
        assertThat(defaultProps.getAlert().isEnabled()).isTrue();
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

```bash
cd ai-chat-service
mvn test -Dtest=ContextBudgetPropertiesTest
```

预期输出：测试失败，提示找不到 ContextBudgetProperties 类

- [ ] **Step 3: 实现配置属性类**

```java
package com.lx.governance.aichat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 上下文预算配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.context")
public class ContextBudgetProperties {
    
    /** 总预算（tokens） */
    private int totalBudget = 4000;
    
    /** 为用户消息预留的 tokens */
    private int userMessageReserve = 500;
    
    /** 核心画像预算 */
    private int coreProfileBudget = 800;
    
    /** 相关记忆预算 */
    private int relevantMemoriesBudget = 1200;
    
    /** 对话历史预算 */
    private int conversationHistoryBudget = 600;
    
    /** 扩展画像预算 */
    private int extendedProfileBudget = 400;
    
    /** 记忆配置 */
    private MemoryConfig memory = new MemoryConfig();
    
    /** 对话配置 */
    private ConversationConfig conversation = new ConversationConfig();
    
    /** 告警配置 */
    private AlertConfig alert = new AlertConfig();
    
    /**
     * 记忆检索配置
     */
    @Data
    public static class MemoryConfig {
        /** 最多检索数量 */
        private int maxCount = 10;
        
        /** 相似度阈值 */
        private double similarityThreshold = 0.7;
        
        /** 短期记忆天数 */
        private int shortTermDays = 3;
        
        /** 中期记忆天数 */
        private int midTermDays = 30;
        
        /** 启用去重 */
        private boolean enableDeduplication = true;
    }
    
    /**
     * 对话历史配置
     */
    @Data
    public static class ConversationConfig {
        /** 最多保留轮数 */
        private int maxRounds = 5;
        
        /** 是否启用对话历史 */
        private boolean enabled = true;
    }
    
    /**
     * 告警配置
     */
    @Data
    public static class AlertConfig {
        /** 是否启用告警 */
        private boolean enabled = true;
        
        /** Prompt tokens 告警阈值 */
        private int promptTokensThreshold = 6000;
        
        /** Total tokens 告警阈值 */
        private int totalTokensThreshold = 28000;
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

```bash
mvn test -Dtest=ContextBudgetPropertiesTest
```

预期输出：所有测试通过

- [ ] **Step 5: 提交代码**

```bash
git add ai-chat-service/src/main/java/com/lx/governance/aichat/config/ContextBudgetProperties.java
git add ai-chat-service/src/test/java/com/lx/governance/aichat/config/ContextBudgetPropertiesTest.java
git commit -m "feat: add context budget configuration properties"
```

---

### Task 2: 添加配置文件

**Files:**
- Modify: `ai-chat-service/src/main/resources/application.yml`

- [ ] **Step 1: 添加配置项到 application.yml**

在 application.yml 文件末尾添加：

```yaml
# AI 上下文管理配置
ai:
  context:
    # Token 预算配置
    total-budget: 4000              # 总预算
    user-message-reserve: 500       # 为用户消息预留
    
    # 各部分预算分配（按优先级）
    core-profile-budget: 800        # 核心画像（必须）
    relevant-memories-budget: 1200  # 相关记忆（重要）
    conversation-history-budget: 600 # 对话历史（重要）
    extended-profile-budget: 400    # 扩展画像（可选）
    
    # 记忆检索配置
    memory:
      max-count: 10                 # 最多检索数量
      similarity-threshold: 0.7     # 相似度阈值
      short-term-days: 3            # 短期记忆天数
      mid-term-days: 30             # 中期记忆天数
      enable-deduplication: true    # 启用去重
    
    # 对话历史配置
    conversation:
      max-rounds: 5                 # 最多保留轮数
      enabled: true                 # 是否启用对话历史
      
    # 告警配置
    alert:
      enabled: true                 # 是否启用告警
      prompt-tokens-threshold: 6000  # Prompt tokens 告警阈值
      total-tokens-threshold: 28000  # Total tokens 告警阈值
```

- [ ] **Step 2: 验证配置加载**

启动应用并检查日志，确认配置正确加载：

```bash
mvn spring-boot:run
```

预期输出：应用正常启动，无配置错误

- [ ] **Step 3: 提交代码**

```bash
git add ai-chat-service/src/main/resources/application.yml
git commit -m "config: add AI context budget configuration"
```

---

### Task 3: 创建 Token 监控组件

**Files:**
- Create: `ai-chat-service/src/main/java/com/lx/governance/aichat/monitor/TokenUsageMetrics.java`
- Create: `ai-chat-service/src/main/java/com/lx/governance/aichat/monitor/TokenMonitor.java`
- Create: `ai-chat-service/src/test/java/com/lx/governance/aichat/monitor/TokenMonitorTest.java`

- [ ] **Step 1: 创建 TokenUsageMetrics 数据模型**

```java
package com.lx.governance.aichat.monitor;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Token 使用指标
 */
@Data
@Builder
public class TokenUsageMetrics {
    
    // 基本信息
    private String customerId;
    private String conversationId;
    private LocalDateTime timestamp;
    
    // 各部分 token 使用
    private int coreProfileTokens;      // 核心画像
    private int memoriesTokens;         // 记忆
    private int conversationTokens;     // 对话历史
    private int extendedProfileTokens;  // 扩展画像
    private int systemPromptTokens;     // 系统提示词总计
    
    // API 返回的 token 使用
    private int promptTokens;           // Prompt tokens（API 返回）
    private int completionTokens;       // Completion tokens
    private int totalTokens;            // Total tokens
    
    // 性能指标
    private long contextBuildTime;      // 上下文构建耗时（毫秒）
    private long apiResponseTime;       // API 响应时间（毫秒）
    
    // 告警标记
    private boolean overBudget;         // 是否超预算
    private boolean shouldAlert;        // 是否需要告警
}
```

- [ ] **Step 2: 编写 TokenMonitor 测试**

```java
package com.lx.governance.aichat.monitor;

import com.lx.governance.aichat.config.ContextBudgetProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenMonitorTest {

    @Mock
    private ContextBudgetProperties properties;

    @InjectMocks
    private TokenMonitor tokenMonitor;

    @BeforeEach
    void setUp() {
        ContextBudgetProperties.AlertConfig alertConfig = new ContextBudgetProperties.AlertConfig();
        alertConfig.setEnabled(true);
        alertConfig.setPromptTokensThreshold(6000);
        alertConfig.setTotalTokensThreshold(28000);

        when(properties.getTotalBudget()).thenReturn(4000);
        when(properties.getUserMessageReserve()).thenReturn(500);
        when(properties.getAlert()).thenReturn(alertConfig);
    }

    @Test
    void shouldDetectOverBudget() {
        TokenUsageMetrics metrics = TokenUsageMetrics.builder()
                .customerId("user123")
                .conversationId("conv-abc")
                .timestamp(LocalDateTime.now())
                .systemPromptTokens(4000)  // 超过预算 (4000 - 500 = 3500)
                .promptTokens(4500)
                .completionTokens(300)
                .totalTokens(4800)
                .build();

        tokenMonitor.recordTokenUsage(metrics);

        assertThat(metrics.isOverBudget()).isTrue();
    }

    @Test
    void shouldNotDetectOverBudgetWhenWithinLimit() {
        TokenUsageMetrics metrics = TokenUsageMetrics.builder()
                .customerId("user123")
                .conversationId("conv-abc")
                .timestamp(LocalDateTime.now())
                .systemPromptTokens(3000)  // 未超预算
                .promptTokens(3500)
                .completionTokens(300)
                .totalTokens(3800)
                .build();

        tokenMonitor.recordTokenUsage(metrics);

        assertThat(metrics.isOverBudget()).isFalse();
    }

    @Test
    void shouldTriggerAlertWhenExceedingThreshold() {
        TokenUsageMetrics metrics = TokenUsageMetrics.builder()
                .customerId("user123")
                .conversationId("conv-abc")
                .timestamp(LocalDateTime.now())
                .systemPromptTokens(3000)
                .promptTokens(6500)  // 超过告警阈值 6000
                .completionTokens(300)
                .totalTokens(6800)
                .build();

        tokenMonitor.recordTokenUsage(metrics);

        assertThat(metrics.isShouldAlert()).isTrue();
    }

    @Test
    void shouldNotTriggerAlertWhenBelowThreshold() {
        TokenUsageMetrics metrics = TokenUsageMetrics.builder()
                .customerId("user123")
                .conversationId("conv-abc")
                .timestamp(LocalDateTime.now())
                .systemPromptTokens(3000)
                .promptTokens(5500)  // 未超过告警阈值
                .completionTokens(300)
                .totalTokens(5800)
                .build();

        tokenMonitor.recordTokenUsage(metrics);

        assertThat(metrics.isShouldAlert()).isFalse();
    }

    @Test
    void shouldNotTriggerAlertWhenDisabled() {
        ContextBudgetProperties.AlertConfig alertConfig = new ContextBudgetProperties.AlertConfig();
        alertConfig.setEnabled(false);
        when(properties.getAlert()).thenReturn(alertConfig);

        TokenUsageMetrics metrics = TokenUsageMetrics.builder()
                .customerId("user123")
                .conversationId("conv-abc")
                .timestamp(LocalDateTime.now())
                .systemPromptTokens(3000)
                .promptTokens(7000)  // 超过阈值但告警已禁用
                .completionTokens(300)
                .totalTokens(7300)
                .build();

        tokenMonitor.recordTokenUsage(metrics);

        assertThat(metrics.isShouldAlert()).isFalse();
    }
}
```

- [ ] **Step 3: 运行测试验证失败**

```bash
mvn test -Dtest=TokenMonitorTest
```

预期输出：测试失败，提示找不到 TokenMonitor 类

- [ ] **Step 4: 实现 TokenMonitor**

```java
package com.lx.governance.aichat.monitor;

import com.lx.governance.aichat.config.ContextBudgetProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Token 使用监控器
 */
@Slf4j
@Component
public class TokenMonitor {
    
    @Autowired
    private ContextBudgetProperties properties;
    
    /**
     * 记录 token 使用
     */
    public void recordTokenUsage(TokenUsageMetrics metrics) {
        // 检查是否超预算
        int budget = properties.getTotalBudget() - properties.getUserMessageReserve();
        metrics.setOverBudget(metrics.getSystemPromptTokens() > budget);
        
        // 检查是否需要告警
        if (properties.getAlert().isEnabled()) {
            int threshold = properties.getAlert().getPromptTokensThreshold();
            metrics.setShouldAlert(metrics.getPromptTokens() > threshold);
        }
        
        // 记录详细日志
        logTokenUsage(metrics);
        
        // 如果需要告警，记录警告日志
        if (metrics.isShouldAlert()) {
            logAlert(metrics);
        }
    }
    
    /**
     * 记录详细日志
     */
    private void logTokenUsage(TokenUsageMetrics metrics) {
        log.info("Token 使用详情 - customerId: {}, conversationId: {}, " +
                "核心画像: {}, 记忆: {}, 对话: {}, 扩展: {}, " +
                "系统提示词总计: {}, API返回: prompt={}, completion={}, total={}, " +
                "上下文构建: {}ms, API响应: {}ms",
                metrics.getCustomerId(),
                metrics.getConversationId(),
                metrics.getCoreProfileTokens(),
                metrics.getMemoriesTokens(),
                metrics.getConversationTokens(),
                metrics.getExtendedProfileTokens(),
                metrics.getSystemPromptTokens(),
                metrics.getPromptTokens(),
                metrics.getCompletionTokens(),
                metrics.getTotalTokens(),
                metrics.getContextBuildTime(),
                metrics.getApiResponseTime());
    }
    
    /**
     * 记录告警日志
     */
    private void logAlert(TokenUsageMetrics metrics) {
        int threshold = properties.getAlert().getPromptTokensThreshold();
        log.warn("⚠️ Token 使用超过告警阈值 - customerId: {}, " +
                "prompt_tokens: {} (阈值: {}), " +
                "建议检查上下文配置或优化内容",
                metrics.getCustomerId(),
                metrics.getPromptTokens(),
                threshold);
    }
}
```

- [ ] **Step 5: 运行测试验证通过**

```bash
mvn test -Dtest=TokenMonitorTest
```

预期输出：所有测试通过

- [ ] **Step 6: 提交代码**

```bash
git add ai-chat-service/src/main/java/com/lx/governance/aichat/monitor/
git add ai-chat-service/src/test/java/com/lx/governance/aichat/monitor/
git commit -m "feat: add token usage monitoring"
```

---

### Task 4: 修改 ContextBudgetManager 使用配置化预算

**Files:**
- Modify: `ai-chat-service/src/main/java/com/lx/governance/aichat/context/ContextBudgetManager.java`
- Modify: `ai-chat-service/src/test/java/com/lx/governance/aichat/context/ContextBudgetManagerTest.java`

- [ ] **Step 1: 添加测试验证配置化预算**

在现有测试类中添加新测试：

```java
@Test
void shouldUseBudgetFromConfiguration() {
    // 模拟配置
    when(properties.getCoreProfileBudget()).thenReturn(800);
    when(properties.getRelevantMemoriesBudget()).thenReturn(1200);
    when(properties.getExtendedProfileBudget()).thenReturn(400);
    
    UserProfileDTO profile = createTestProfile();
    List<MemoryItemDTO> memories = createTestMemories(15);
    
    String systemPrompt = contextBudgetManager.buildBudgetedSystemPrompt(
            profile, memories, "健康咨询问题");
    
    // 验证各部分 token 使用符合配置
    int coreTokens = contextBudgetManager.getLastCoreProfileTokens();
    int memoryTokens = contextBudgetManager.getLastMemoriesTokens();
    int extendedTokens = contextBudgetManager.getLastExtendedProfileTokens();
    
    assertThat(coreTokens).isLessThanOrEqualTo(800);
    assertThat(memoryTokens).isLessThanOrEqualTo(1200);
    assertThat(extendedTokens).isLessThanOrEqualTo(400);
}

@Test
void shouldTrackTokenUsageBySection() {
    UserProfileDTO profile = createTestProfile();
    List<MemoryItemDTO> memories = createTestMemories(5);
    
    String systemPrompt = contextBudgetManager.buildBudgetedSystemPrompt(
            profile, memories, "饮食建议");
    
    // 验证可以获取各部分的 token 使用
    assertThat(contextBudgetManager.getLastCoreProfileTokens()).isGreaterThan(0);
    assertThat(contextBudgetManager.getLastMemoriesTokens()).isGreaterThan(0);
    assertThat(contextBudgetManager.getLastExtendedProfileTokens()).isGreaterThan(0);
    assertThat(contextBudgetManager.getLastTotalTokens()).isGreaterThan(0);
}
```

- [ ] **Step 2: 运行测试验证失败**

```bash
mvn test -Dtest=ContextBudgetManagerTest
```

预期输出：新增测试失败，提示方法不存在

- [ ] **Step 3: 修改 ContextBudgetManager 注入配置**

在 ContextBudgetManager 类中添加配置注入和 token 跟踪：

```java
@Slf4j
@Component
public class ContextBudgetManager {
    
    @Autowired
    private ContextBudgetProperties properties;
    
    // Token 使用跟踪
    private int lastCoreProfileTokens = 0;
    private int lastMemoriesTokens = 0;
    private int lastConversationTokens = 0;
    private int lastExtendedProfileTokens = 0;
    private int lastTotalTokens = 0;
    
    /**
     * 构建受预算约束的系统提示词
     */
    public String buildBudgetedSystemPrompt(
            UserProfileDTO profile,
            List<MemoryItemDTO> memories,
            String userQuestion) {
        
        long startTime = System.currentTimeMillis();
        
        // 重置 token 计数
        resetTokenCounters();
        
        StringBuilder prompt = new StringBuilder();
        
        // 1. 核心画像（必须，优先级最高）
        String coreProfile = extractCoreProfile(profile);
        coreProfile = ensureWithinBudget(coreProfile, properties.getCoreProfileBudget());
        lastCoreProfileTokens = estimateTokens(coreProfile);
        prompt.append("# 用户核心画像\n").append(coreProfile).append("\n\n");
        
        // 2. 相关记忆（重要）
        String memoriesText = formatMemories(memories, properties.getRelevantMemoriesBudget());
        lastMemoriesTokens = estimateTokens(memoriesText);
        if (!memoriesText.isEmpty()) {
            prompt.append("# 相关记忆\n").append(memoriesText).append("\n\n");
        }
        
        // 3. 扩展画像（可选，根据问题类型）
        String extendedProfile = extractExtendedProfile(profile, userQuestion);
        extendedProfile = ensureWithinBudget(extendedProfile, properties.getExtendedProfileBudget());
        lastExtendedProfileTokens = estimateTokens(extendedProfile);
        if (!extendedProfile.isEmpty()) {
            prompt.append("# 扩展信息\n").append(extendedProfile).append("\n\n");
        }
        
        lastTotalTokens = lastCoreProfileTokens + lastMemoriesTokens + 
                         lastConversationTokens + lastExtendedProfileTokens;
        
        long buildTime = System.currentTimeMillis() - startTime;
        log.debug("上下文构建完成 - 耗时: {}ms, tokens: {}", buildTime, lastTotalTokens);
        
        return prompt.toString();
    }
    
    /**
     * 重置 token 计数器
     */
    private void resetTokenCounters() {
        lastCoreProfileTokens = 0;
        lastMemoriesTokens = 0;
        lastConversationTokens = 0;
        lastExtendedProfileTokens = 0;
        lastTotalTokens = 0;
    }
    
    // Getter 方法
    public int getLastCoreProfileTokens() {
        return lastCoreProfileTokens;
    }
    
    public int getLastMemoriesTokens() {
        return lastMemoriesTokens;
    }
    
    public int getLastConversationTokens() {
        return lastConversationTokens;
    }
    
    public int getLastExtendedProfileTokens() {
        return lastExtendedProfileTokens;
    }
    
    public int getLastTotalTokens() {
        return lastTotalTokens;
    }
    
    // ... 保留现有的其他方法 ...
}
```

- [ ] **Step 4: 运行测试验证通过**

```bash
mvn test -Dtest=ContextBudgetManagerTest
```

预期输出：所有测试通过

- [ ] **Step 5: 提交代码**

```bash
git add ai-chat-service/src/main/java/com/lx/governance/aichat/context/ContextBudgetManager.java
git add ai-chat-service/src/test/java/com/lx/governance/aichat/context/ContextBudgetManagerTest.java
git commit -m "refactor: use configurable budget in ContextBudgetManager"
```

---

### Task 5: 集成 TokenMonitor 到 DeepSeekClient

**Files:**
- Modify: `ai-chat-service/src/main/java/com/lx/governance/aichat/client/DeepSeekClient.java`
- Modify: `ai-chat-service/src/test/java/com/lx/governance/aichat/client/DeepSeekClientTest.java`

- [ ] **Step 1: 添加测试验证 token 监控集成**

```java
@Test
void shouldRecordTokenUsageAfterApiCall() {
    // 模拟 API 响应
    ChatCompletionResponse response = new ChatCompletionResponse();
    ChatCompletionResponse.Usage usage = new ChatCompletionResponse.Usage();
    usage.setPromptTokens(3500);
    usage.setCompletionTokens(400);
    usage.setTotalTokens(3900);
    response.setUsage(usage);
    
    when(restTemplate.postForObject(anyString(), any(), eq(ChatCompletionResponse.class)))
            .thenReturn(response);
    
    // 调用 chat 方法
    ChatRequest request = ChatRequest.builder()
            .customerId("user123")
            .conversationId("conv-abc")
            .userMessage("测试消息")
            .systemPrompt("系统提示词")
            .build();
    
    ChatResponse result = deepSeekClient.chat(request);
    
    // 验证 TokenMonitor 被调用
    verify(tokenMonitor, times(1)).recordTokenUsage(any(TokenUsageMetrics.class));
    
    // 验证返回结果包含 token 使用信息
    assertThat(result.getPromptTokens()).isEqualTo(3500);
    assertThat(result.getCompletionTokens()).isEqualTo(400);
    assertThat(result.getTotalTokens()).isEqualTo(3900);
}
```

- [ ] **Step 2: 运行测试验证失败**

```bash
mvn test -Dtest=DeepSeekClientTest
```

预期输出：测试失败，提示 tokenMonitor 未注入

- [ ] **Step 3: 修改 DeepSeekClient 集成监控**

```java
@Slf4j
@Component
public class DeepSeekClient {
    
    @Autowired
    private RestTemplate restTemplate;
    
    @Autowired
    private TokenMonitor tokenMonitor;
    
    @Autowired
    private ContextBudgetManager contextBudgetManager;
    
    @Value("${deepseek.api.url}")
    private String apiUrl;
    
    @Value("${deepseek.api.key}")
    private String apiKey;
    
    /**
     * 调用 DeepSeek API
     */
    public ChatResponse chat(ChatRequest request) {
        long apiStartTime = System.currentTimeMillis();
        
        try {
            // 构建 API 请求
            DeepSeekApiRequest apiRequest = buildApiRequest(request);
            
            // 调用 API
            ChatCompletionResponse response = restTemplate.postForObject(
                    apiUrl, apiRequest, ChatCompletionResponse.class);
            
            long apiResponseTime = System.currentTimeMillis() - apiStartTime;
            
            // 记录 token 使用
            recordTokenUsage(request, response, apiResponseTime);
            
            // 构建响应
            return buildChatResponse(response);
            
        } catch (Exception e) {
            log.error("DeepSeek API 调用失败", e);
            throw new RuntimeException("AI 服务调用失败", e);
        }
    }
    
    /**
     * 记录 token 使用
     */
    private void recordTokenUsage(
            ChatRequest request, 
            ChatCompletionResponse response,
            long apiResponseTime) {
        
        ChatCompletionResponse.Usage usage = response.getUsage();
        if (usage == null) {
            log.warn("API 响应中缺少 usage 信息");
            return;
        }
        
        TokenUsageMetrics metrics = TokenUsageMetrics.builder()
                .customerId(request.getCustomerId())
                .conversationId(request.getConversationId())
                .timestamp(LocalDateTime.now())
                .coreProfileTokens(contextBudgetManager.getLastCoreProfileTokens())
                .memoriesTokens(contextBudgetManager.getLastMemoriesTokens())
                .conversationTokens(contextBudgetManager.getLastConversationTokens())
                .extendedProfileTokens(contextBudgetManager.getLastExtendedProfileTokens())
                .systemPromptTokens(contextBudgetManager.getLastTotalTokens())
                .promptTokens(usage.getPromptTokens())
                .completionTokens(usage.getCompletionTokens())
                .totalTokens(usage.getTotalTokens())
                .contextBuildTime(0)  // 由 AiChatService 设置
                .apiResponseTime(apiResponseTime)
                .build();
        
        tokenMonitor.recordTokenUsage(metrics);
    }
    
    // ... 保留现有的其他方法 ...
}
```

- [ ] **Step 4: 运行测试验证通过**

```bash
mvn test -Dtest=DeepSeekClientTest
```

预期输出：所有测试通过

- [ ] **Step 5: 提交代码**

```bash
git add ai-chat-service/src/main/java/com/lx/governance/aichat/client/DeepSeekClient.java
git add ai-chat-service/src/test/java/com/lx/governance/aichat/client/DeepSeekClientTest.java
git commit -m "feat: integrate token monitoring in DeepSeekClient"
```

---

## 阶段 2：记忆分层和对话历史

### Task 6: 创建对话历史数据模型

**Files:**
- Create: `ai-chat-service/src/main/java/com/lx/governance/aichat/dto/ConversationMessage.java`
- Create: `ai-chat-service/src/test/java/com/lx/governance/aichat/dto/ConversationMessageTest.java`

- [ ] **Step 1: 编写数据模型测试**

```java
package com.lx.governance.aichat.dto;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationMessageTest {

    @Test
    void shouldBuildConversationMessage() {
        LocalDateTime now = LocalDateTime.now();
        
        ConversationMessage message = ConversationMessage.builder()
                .role("user")
                .content("测试消息")
                .time(now)
                .build();
        
        assertThat(message.getRole()).isEqualTo("user");
        assertThat(message.getContent()).isEqualTo("测试消息");
        assertThat(message.getTime()).isEqualTo(now);
    }

    @Test
    void shouldSupportUserRole() {
        ConversationMessage message = ConversationMessage.builder()
                .role("user")
                .content("用户消息")
                .time(LocalDateTime.now())
                .build();
        
        assertThat(message.getRole()).isEqualTo("user");
    }

    @Test
    void shouldSupportAssistantRole() {
        ConversationMessage message = ConversationMessage.builder()
                .role("assistant")
                .content("助手回复")
                .time(LocalDateTime.now())
                .build();
        
        assertThat(message.getRole()).isEqualTo("assistant");
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

```bash
mvn test -Dtest=ConversationMessageTest
```

预期输出：测试失败，提示找不到 ConversationMessage 类

- [ ] **Step 3: 实现 ConversationMessage**

```java
package com.lx.governance.aichat.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对话消息
 */
@Data
@Builder
public class ConversationMessage {
    
    /** 角色：user 或 assistant */
    private String role;
    
    /** 消息内容 */
    private String content;
    
    /** 消息时间 */
    private LocalDateTime time;
}
```

- [ ] **Step 4: 运行测试验证通过**

```bash
mvn test -Dtest=ConversationMessageTest
```

预期输出：所有测试通过

- [ ] **Step 5: 提交代码**

```bash
git add ai-chat-service/src/main/java/com/lx/governance/aichat/dto/ConversationMessage.java
git add ai-chat-service/src/test/java/com/lx/governance/aichat/dto/ConversationMessageTest.java
git commit -m "feat: add ConversationMessage data model"
```

---

### Task 7: 创建对话历史管理器

**Files:**
- Create: `ai-chat-service/src/main/java/com/lx/governance/aichat/manager/ConversationHistoryManager.java`
- Create: `ai-chat-service/src/test/java/com/lx/governance/aichat/manager/ConversationHistoryManagerTest.java`

- [ ] **Step 1: 编写对话历史管理器测试**

```java
package com.lx.governance.aichat.manager;

import com.lx.governance.aichat.config.ContextBudgetProperties;
import com.lx.governance.aichat.dto.ConversationMessage;
import com.lx.governance.aichat.entity.AiChatHistory;
import com.lx.governance.aichat.mapper.AiChatHistoryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationHistoryManagerTest {

    @Mock
    private AiChatHistoryMapper chatHistoryMapper;

    @Mock
    private ContextBudgetProperties properties;

    @InjectMocks
    private ConversationHistoryManager conversationHistoryManager;

    @BeforeEach
    void setUp() {
        ContextBudgetProperties.ConversationConfig config = new ContextBudgetProperties.ConversationConfig();
        config.setMaxRounds(5);
        config.setEnabled(true);
        when(properties.getConversation()).thenReturn(config);
    }

    @Test
    void shouldGetRecentHistory() {
        // 模拟数据库返回
        List<AiChatHistory> historyRecords = Arrays.asList(
                createHistoryRecord("你好", "你好！有什么可以帮助你的吗？", LocalDateTime.now().minusMinutes(10)),
                createHistoryRecord("我想了解健康饮食", "健康饮食很重要...", LocalDateTime.now().minusMinutes(5))
        );
        
        when(chatHistoryMapper.selectList(any())).thenReturn(historyRecords);
        
        List<ConversationMessage> messages = conversationHistoryManager.getRecentHistory(
                "user123", "conv-abc", 5);
        
        // 验证返回 4 条消息（2 轮对话 = 2 个用户消息 + 2 个助手回复）
        assertThat(messages).hasSize(4);
        assertThat(messages.get(0).getRole()).isEqualTo("user");
        assertThat(messages.get(0).getContent()).isEqualTo("你好");
        assertThat(messages.get(1).getRole()).isEqualTo("assistant");
        assertThat(messages.get(1).getContent()).isEqualTo("你好！有什么可以帮助你的吗？");
    }

    @Test
    void shouldFormatHistoryWithinBudget() {
        List<ConversationMessage> messages = Arrays.asList(
                ConversationMessage.builder()
                        .role("user")
                        .content("第一条消息")
                        .time(LocalDateTime.now().minusMinutes(10))
                        .build(),
                ConversationMessage.builder()
                        .role("assistant")
                        .content("第一条回复")
                        .time(LocalDateTime.now().minusMinutes(9))
                        .build(),
                ConversationMessage.builder()
                        .role("user")
                        .content("第二条消息")
                        .time(LocalDateTime.now().minusMinutes(5))
                        .build(),
                ConversationMessage.builder()
                        .role("assistant")
                        .content("第二条回复")
                        .time(LocalDateTime.now().minusMinutes(4))
                        .build()
        );
        
        String formatted = conversationHistoryManager.formatHistory(messages, 600);
        
        assertThat(formatted).contains("用户: 第一条消息");
        assertThat(formatted).contains("助手: 第一条回复");
        assertThat(formatted).contains("用户: 第二条消息");
        assertThat(formatted).contains("助手: 第二条回复");
    }

    @Test
    void shouldTruncateHistoryWhenExceedingBudget() {
        List<ConversationMessage> messages = Arrays.asList(
                ConversationMessage.builder()
                        .role("user")
                        .content("很长的消息".repeat(100))
                        .time(LocalDateTime.now().minusMinutes(10))
                        .build(),
                ConversationMessage.builder()
                        .role("assistant")
                        .content("很长的回复".repeat(100))
                        .time(LocalDateTime.now().minusMinutes(9))
                        .build(),
                ConversationMessage.builder()
                        .role("user")
                        .content("最新消息")
                        .time(LocalDateTime.now().minusMinutes(1))
                        .build()
        );
        
        String formatted = conversationHistoryManager.formatHistory(messages, 100);
        
        // 应该只包含最新的消息（因为预算有限）
        assertThat(formatted).contains("最新消息");
        assertThat(formatted.length()).isLessThan(500);
    }

    @Test
    void shouldReturnEmptyWhenHistoryDisabled() {
        ContextBudgetProperties.ConversationConfig config = new ContextBudgetProperties.ConversationConfig();
        config.setEnabled(false);
        when(properties.getConversation()).thenReturn(config);
        
        List<ConversationMessage> messages = conversationHistoryManager.getRecentHistory(
                "user123", "conv-abc", 5);
        
        assertThat(messages).isEmpty();
    }

    private AiChatHistory createHistoryRecord(String userMsg, String aiResponse, LocalDateTime time) {
        AiChatHistory history = new AiChatHistory();
        history.setUserMessage(userMsg);
        history.setAiResponse(aiResponse);
        history.setCreateTime(time);
        return history;
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

```bash
mvn test -Dtest=ConversationHistoryManagerTest
```

预期输出：测试失败，提示找不到 ConversationHistoryManager 类

- [ ] **Step 3: 实现 ConversationHistoryManager**

```java
package com.lx.governance.aichat.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lx.governance.aichat.config.ContextBudgetProperties;
import com.lx.governance.aichat.dto.ConversationMessage;
import com.lx.governance.aichat.entity.AiChatHistory;
import com.lx.governance.aichat.mapper.AiChatHistoryMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 对话历史管理器
 */
@Slf4j
@Component
public class ConversationHistoryManager {
    
    @Autowired
    private AiChatHistoryMapper chatHistoryMapper;
    
    @Autowired
    private ContextBudgetProperties properties;
    
    /**
     * 获取最近的对话历史
     */
    public List<ConversationMessage> getRecentHistory(
            String customerId, 
            String conversationId, 
            int maxRounds) {
        
        // 检查是否启用对话历史
        if (!properties.getConversation().isEnabled()) {
            log.debug("对话历史功能已禁用");
            return Collections.emptyList();
        }
        
        // 从数据库查询最近的对话记录
        LambdaQueryWrapper<AiChatHistory> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(AiChatHistory::getCustomerId, customerId)
                .eq(AiChatHistory::getConversationId, conversationId)
                .eq(AiChatHistory::getIsDeleted, 0)
                .orderByDesc(AiChatHistory::getCreateTime)
                .last("LIMIT " + maxRounds);
        
        List<AiChatHistory> records = chatHistoryMapper.selectList(queryWrapper);
        
        if (records == null || records.isEmpty()) {
            log.debug("未找到对话历史");
            return Collections.emptyList();
        }
        
        // 转换为 ConversationMessage 列表
        List<ConversationMessage> messages = new ArrayList<>();
        
        for (AiChatHistory record : records) {
            // 用户消息
            messages.add(ConversationMessage.builder()
                    .role("user")
                    .content(record.getUserMessage())
                    .time(record.getCreateTime())
                    .build());
            
            // AI 回复
            if (record.getAiResponse() != null && !record.getAiResponse().isEmpty()) {
                messages.add(ConversationMessage.builder()
                        .role("assistant")
                        .content(record.getAiResponse())
                        .time(record.getCreateTime())
                        .build());
            }
        }
        
        // 按时间正序排列（最早的在前）
        Collections.reverse(messages);
        
        log.debug("获取对话历史 - 轮数: {}, 消息数: {}", records.size(), messages.size());
        
        return messages;
    }
    
    /**
     * 格式化对话历史为文本
     */
    public String formatHistory(List<ConversationMessage> history, int budget) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        
        StringBuilder formatted = new StringBuilder();
        int usedTokens = 0;
        
        // 从最近的对话开始（倒序）
        for (int i = history.size() - 1; i >= 0; i--) {
            ConversationMessage msg = history.get(i);
            
            String msgText = String.format("%s: %s\n", 
                    msg.getRole().equals("user") ? "用户" : "助手", 
                    msg.getContent());
            
            int tokens = estimateTokens(msgText);
            
            if (usedTokens + tokens <= budget) {
                // 插入到开头（保持时间顺序）
                formatted.insert(0, msgText);
                usedTokens += tokens;
            } else {
                log.debug("对话历史预算用完，已选择 {} 条消息", history.size() - i - 1);
                break;
            }
        }
        
        log.debug("对话历史格式化完成 - tokens: {}/{}", usedTokens, budget);
        
        return formatted.toString();
    }
    
    /**
     * 估算文本的 token 数量
     */
    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        
        // 中文：1 字符 ≈ 2 tokens
        // 英文：1 单词 ≈ 1.3 tokens
        int chineseChars = 0;
        int otherChars = 0;
        
        for (char c : text.toCharArray()) {
            if (c >= 0x4E00 && c <= 0x9FA5) {
                chineseChars++;
            } else {
                otherChars++;
            }
        }
        
        return (int) (chineseChars * 2 + otherChars * 0.5);
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

```bash
mvn test -Dtest=ConversationHistoryManagerTest
```

预期输出：所有测试通过

- [ ] **Step 5: 提交代码**

```bash
git add ai-chat-service/src/main/java/com/lx/governance/aichat/manager/ConversationHistoryManager.java
git add ai-chat-service/src/test/java/com/lx/governance/aichat/manager/ConversationHistoryManagerTest.java
git commit -m "feat: add conversation history manager"
```

---

### Task 8: 创建记忆分层管理器

**Files:**
- Create: `ai-chat-service/src/main/java/com/lx/governance/aichat/manager/HierarchicalMemoryManager.java`
- Create: `ai-chat-service/src/test/java/com/lx/governance/aichat/manager/HierarchicalMemoryManagerTest.java`

- [ ] **Step 1: 编写记忆分层管理器测试**

```java
package com.lx.governance.aichat.manager;

import com.lx.governance.aichat.config.ContextBudgetProperties;
import com.lx.governance.aichat.dto.MemoryItemDTO;
import com.lx.governance.aichat.service.MemoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HierarchicalMemoryManagerTest {

    @Mock
    private MemoryService memoryService;

    @Mock
    private ContextBudgetProperties properties;

    @InjectMocks
    private HierarchicalMemoryManager hierarchicalMemoryManager;

    @BeforeEach
    void setUp() {
        ContextBudgetProperties.MemoryConfig config = new ContextBudgetProperties.MemoryConfig();
        config.setMaxCount(10);
        config.setSimilarityThreshold(0.7);
        config.setShortTermDays(3);
        config.setMidTermDays(30);
        config.setEnableDeduplication(true);
        when(properties.getMemory()).thenReturn(config);
    }

    @Test
    void shouldRetrieveShortTermMemories() {
        // 模拟短期记忆
        List<MemoryItemDTO> shortTermMemories = Arrays.asList(
                createMemory("短期记忆1", LocalDateTime.now().minusDays(1), 0.9),
                createMemory("短期记忆2", LocalDateTime.now().minusDays(2), 0.85)
        );
        
        when(memoryService.getRecentMemories(anyString(), anyString(), eq(3)))
                .thenReturn(shortTermMemories);
        
        List<MemoryItemDTO> result = hierarchicalMemoryManager.retrieveHierarchicalMemories(
                "user123", "group1", "测试问题", 10);
        
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getContent()).isEqualTo("短期记忆1");
    }

    @Test
    void shouldRetrieveMidTermMemoriesWhenShortTermNotEnough() {
        // 模拟短期记忆（只有 1 条）
        List<MemoryItemDTO> shortTermMemories = Arrays.asList(
                createMemory("短期记忆", LocalDateTime.now().minusDays(1), 0.9)
        );
        
        // 模拟中期记忆
        List<MemoryItemDTO> midTermMemories = Arrays.asList(
                createMemory("中期记忆1", LocalDateTime.now().minusDays(10), 0.85),
                createMemory("中期记忆2", LocalDateTime.now().minusDays(15), 0.8)
        );
        
        when(memoryService.getRecentMemories(anyString(), anyString(), eq(3)))
                .thenReturn(shortTermMemories);
        when(memoryService.searchMemories(anyString(), anyString(), anyString(), anyInt(), eq(30)))
                .thenReturn(midTermMemories);
        
        List<MemoryItemDTO> result = hierarchicalMemoryManager.retrieveHierarchicalMemories(
                "user123", "group1", "测试问题", 10);
        
        // 应该包含短期 + 中期记忆
        assertThat(result).hasSize(3);
        assertThat(result.get(0).getContent()).isEqualTo("短期记忆");
    }

    @Test
    void shouldRetrieveLongTermMemoriesForHistoricalQuestions() {
        // 模拟短期和中期记忆（共 5 条）
        List<MemoryItemDTO> shortTermMemories = Arrays.asList(
                createMemory("短期记忆", LocalDateTime.now().minusDays(1), 0.9)
        );
        
        List<MemoryItemDTO> midTermMemories = Arrays.asList(
                createMemory("中期记忆1", LocalDateTime.now().minusDays(10), 0.85),
                createMemory("中期记忆2", LocalDateTime.now().minusDays(15), 0.8)
        );
        
        // 模拟长期记忆
        List<MemoryItemDTO> longTermMemories = Arrays.asList(
                createMemory("长期记忆", LocalDateTime.now().minusDays(60), 0.75)
        );
        
        when(memoryService.getRecentMemories(anyString(), anyString(), eq(3)))
                .thenReturn(shortTermMemories);
        when(memoryService.searchMemories(anyString(), anyString(), anyString(), anyInt(), eq(30)))
                .thenReturn(midTermMemories);
        when(memoryService.searchMemories(anyString(), anyString(), anyString(), anyInt(), isNull()))
                .thenReturn(longTermMemories);
        
        // 包含历史关键词的问题
        List<MemoryItemDTO> result = hierarchicalMemoryManager.retrieveHierarchicalMemories(
                "user123", "group1", "我以前的健康趋势如何？", 10);
        
        // 应该包含短期 + 中期 + 长期记忆
        assertThat(result).hasSize(4);
    }

    @Test
    void shouldNotRetrieveLongTermMemoriesForNormalQuestions() {
        List<MemoryItemDTO> shortTermMemories = Arrays.asList(
                createMemory("短期记忆", LocalDateTime.now().minusDays(1), 0.9)
        );
        
        when(memoryService.getRecentMemories(anyString(), anyString(), eq(3)))
                .thenReturn(shortTermMemories);
        
        // 普通问题（不包含历史关键词）
        List<MemoryItemDTO> result = hierarchicalMemoryManager.retrieveHierarchicalMemories(
                "user123", "group1", "今天吃什么好？", 10);
        
        // 不应该检索长期记忆
        assertThat(result).hasSize(1);
    }

    @Test
    void shouldDeduplicateMemories() {
        // 模拟重复的记忆
        List<MemoryItemDTO> shortTermMemories = Arrays.asList(
                createMemory("血压偏高", LocalDateTime.now().minusDays(1), 0.9),
                createMemory("血压偏高", LocalDateTime.now().minusDays(2), 0.85)
        );
        
        when(memoryService.getRecentMemories(anyString(), anyString(), eq(3)))
                .thenReturn(shortTermMemories);
        
        List<MemoryItemDTO> result = hierarchicalMemoryManager.retrieveHierarchicalMemories(
                "user123", "group1", "测试问题", 10);
        
        // 应该去重，只保留 1 条
        assertThat(result).hasSize(1);
    }

    @Test
    void shouldNotDeduplicateWhenDisabled() {
        ContextBudgetProperties.MemoryConfig config = new ContextBudgetProperties.MemoryConfig();
        config.setMaxCount(10);
        config.setEnableDeduplication(false);
        when(properties.getMemory()).thenReturn(config);
        
        List<MemoryItemDTO> shortTermMemories = Arrays.asList(
                createMemory("血压偏高", LocalDateTime.now().minusDays(1), 0.9),
                createMemory("血压偏高", LocalDateTime.now().minusDays(2), 0.85)
        );
        
        when(memoryService.getRecentMemories(anyString(), anyString(), eq(3)))
                .thenReturn(shortTermMemories);
        
        List<MemoryItemDTO> result = hierarchicalMemoryManager.retrieveHierarchicalMemories(
                "user123", "group1", "测试问题", 10);
        
        // 不去重，保留 2 条
        assertThat(result).hasSize(2);
    }

    private MemoryItemDTO createMemory(String content, LocalDateTime time, double similarity) {
        MemoryItemDTO memory = new MemoryItemDTO();
        memory.setId(String.valueOf(System.nanoTime()));
        memory.setContent(content);
        memory.setCreateTime(time);
        memory.setSimilarity(similarity);
        return memory;
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

```bash
mvn test -Dtest=HierarchicalMemoryManagerTest
```

预期输出：测试失败，提示找不到 HierarchicalMemoryManager 类

- [ ] **Step 3: 实现 HierarchicalMemoryManager（第一部分）**

```java
package com.lx.governance.aichat.manager;

import com.lx.governance.aichat.config.ContextBudgetProperties;
import com.lx.governance.aichat.dto.MemoryItemDTO;
import com.lx.governance.aichat.service.MemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆分层管理器
 */
@Slf4j
@Component
public class HierarchicalMemoryManager {
    
    @Autowired
    private MemoryService memoryService;
    
    @Autowired
    private ContextBudgetProperties properties;
    
    /**
     * 分层检索记忆
     */
    public List<MemoryItemDTO> retrieveHierarchicalMemories(
            String customerId,
            String groupId,
            String userQuestion,
            int maxMemories) {
        
        List<MemoryItemDTO> result = new ArrayList<>();
        int maxCount = properties.getMemory().getMaxCount();
        
        // 1. 短期记忆（最近 3 天，直接查询）
        List<MemoryItemDTO> shortTerm = getShortTermMemories(customerId, groupId);
        result.addAll(shortTerm);
        log.debug("短期记忆: {} 条", shortTerm.size());
        
        // 2. 中期记忆（最近 30 天，向量检索）
        if (result.size() < maxCount) {
            int remaining = maxCount - result.size();
            List<MemoryItemDTO> midTerm = getMidTermMemories(
                    customerId, groupId, userQuestion, remaining);
            result.addAll(midTerm);
            log.debug("中期记忆: {} 条", midTerm.size());
        }
        
        // 3. 长期记忆（>30 天，仅特定问题）
        if (result.size() < maxCount && needsLongTermMemory(userQuestion)) {
            int remaining = maxCount - result.size();
            List<MemoryItemDTO> longTerm = getLongTermMemories(
                    customerId, groupId, userQuestion, remaining);
            result.addAll(longTerm);
            log.debug("长期记忆: {} 条", longTerm.size());
        }
        
        // 4. 去重和排序
        result = deduplicateMemories(result);
        result.sort((a, b) -> Double.compare(b.getSimilarity(), a.getSimilarity()));
        
        log.info("记忆检索完成 - 总计: {} 条", result.size());
        return result;
    }
    
    /**
     * 获取短期记忆
     */
    private List<MemoryItemDTO> getShortTermMemories(String customerId, String groupId) {
        int shortTermDays = properties.getMemory().getShortTermDays();
        return memoryService.getRecentMemories(customerId, groupId, shortTermDays);
    }
    
    /**
     * 获取中期记忆
     */
    private List<MemoryItemDTO> getMidTermMemories(
            String customerId, String groupId, String question, int limit) {
        int midTermDays = properties.getMemory().getMidTermDays();
        return memoryService.searchMemories(customerId, groupId, question, limit, midTermDays);
    }
    
    /**
     * 获取长期记忆
     */
    private List<MemoryItemDTO> getLongTermMemories(
            String customerId, String groupId, String question, int limit) {
        return memoryService.searchMemories(customerId, groupId, question, limit, null);
    }
    
    /**
     * 判断是否需要长期记忆
     */
    private boolean needsLongTermMemory(String question) {
        if (question == null) {
            return false;
        }
        
        String q = question.toLowerCase();
        
        // 包含历史、趋势相关关键词
        return q.contains("以前") || 
               q.contains("历史") || 
               q.contains("变化") ||
               q.contains("趋势") ||
               q.contains("之前") ||
               q.contains("过去");
    }
    
    /**
     * 记忆去重
     */
    private List<MemoryItemDTO> deduplicateMemories(List<MemoryItemDTO> memories) {
        if (!properties.getMemory().isEnableDeduplication()) {
            return memories;
        }
        
        Map<String, MemoryItemDTO> uniqueMemories = new LinkedHashMap<>();
        
        for (MemoryItemDTO memory : memories) {
            String content = memory.getContent();
            
            // 检查是否已存在相似内容
            boolean isDuplicate = uniqueMemories.values().stream()
                    .anyMatch(existing -> isSimilarContent(existing.getContent(), content));
            
            if (!isDuplicate) {
                uniqueMemories.put(memory.getId(), memory);
            } else {
                log.debug("去重: 跳过重复记忆 - {}", truncate(content, 50));
            }
        }
        
        return new ArrayList<>(uniqueMemories.values());
    }
    
    /**
     * 判断内容是否相似
     */
    private boolean isSimilarContent(String content1, String content2) {
        // 完全相同
        if (content1.equals(content2)) {
            return true;
        }
        
        // 包含关系
        if (content1.contains(content2) || content2.contains(content1)) {
            return true;
        }
        
        return false;
    }
    
    /**
     * 截断文本
     */
    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

```bash
mvn test -Dtest=HierarchicalMemoryManagerTest
```

预期输出：所有测试通过

- [ ] **Step 5: 提交代码**

```bash
git add ai-chat-service/src/main/java/com/lx/governance/aichat/manager/HierarchicalMemoryManager.java
git add ai-chat-service/src/test/java/com/lx/governance/aichat/manager/HierarchicalMemoryManagerTest.java
git commit -m "feat: add hierarchical memory manager"
```

---

### Task 9: 在 ContextBudgetManager 中集成对话历史

**Files:**
- Modify: `ai-chat-service/src/main/java/com/lx/governance/aichat/context/ContextBudgetManager.java`
- Modify: `ai-chat-service/src/test/java/com/lx/governance/aichat/context/ContextBudgetManagerTest.java`

- [ ] **Step 1: 添加测试验证对话历史集成**

在现有测试类中添加新测试：

```java
@Test
void shouldIncludeConversationHistoryInSystemPrompt() {
    // 模拟对话历史
    List<ConversationMessage> history = Arrays.asList(
            ConversationMessage.builder()
                    .role("user")
                    .content("我的血压是多少？")
                    .time(LocalDateTime.now().minusMinutes(10))
                    .build(),
            ConversationMessage.builder()
                    .role("assistant")
                    .content("您的血压是 120/80 mmHg，属于正常范围。")
                    .time(LocalDateTime.now().minusMinutes(9))
                    .build()
    );
    
    when(conversationHistoryManager.getRecentHistory(anyString(), anyString(), anyInt()))
            .thenReturn(history);
    when(conversationHistoryManager.formatHistory(any(), anyInt()))
            .thenReturn("用户: 我的血压是多少？\n助手: 您的血压是 120/80 mmHg，属于正常范围。\n");
    
    UserProfileDTO profile = createTestProfile();
    List<MemoryItemDTO> memories = createTestMemories(5);
    
    String systemPrompt = contextBudgetManager.buildBudgetedSystemPrompt(
            profile, memories, "user123", "conv-abc", "今天吃什么好？");
    
    // 验证包含对话历史
    assertThat(systemPrompt).contains("# 对话历史");
    assertThat(systemPrompt).contains("我的血压是多少？");
    assertThat(systemPrompt).contains("您的血压是 120/80 mmHg");
    
    // 验证 token 计数
    assertThat(contextBudgetManager.getLastConversationTokens()).isGreaterThan(0);
}

@Test
void shouldSkipConversationHistoryWhenEmpty() {
    when(conversationHistoryManager.getRecentHistory(anyString(), anyString(), anyInt()))
            .thenReturn(Collections.emptyList());
    
    UserProfileDTO profile = createTestProfile();
    List<MemoryItemDTO> memories = createTestMemories(5);
    
    String systemPrompt = contextBudgetManager.buildBudgetedSystemPrompt(
            profile, memories, "user123", "conv-abc", "测试问题");
    
    // 不应该包含对话历史部分
    assertThat(systemPrompt).doesNotContain("# 对话历史");
    assertThat(contextBudgetManager.getLastConversationTokens()).isEqualTo(0);
}

@Test
void shouldRespectConversationHistoryBudget() {
    // 模拟很长的对话历史
    List<ConversationMessage> longHistory = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
        longHistory.add(ConversationMessage.builder()
                .role("user")
                .content("很长的消息内容".repeat(50))
                .time(LocalDateTime.now().minusMinutes(i * 2))
                .build());
    }
    
    when(conversationHistoryManager.getRecentHistory(anyString(), anyString(), anyInt()))
            .thenReturn(longHistory);
    when(conversationHistoryManager.formatHistory(any(), anyInt()))
            .thenAnswer(invocation -> {
                int budget = invocation.getArgument(1);
                // 模拟格式化逻辑，返回受预算约束的文本
                return "用户: 测试\n".repeat(Math.min(budget / 20, 10));
            });
    when(properties.getConversationHistoryBudget()).thenReturn(600);
    
    UserProfileDTO profile = createTestProfile();
    List<MemoryItemDTO> memories = createTestMemories(5);
    
    String systemPrompt = contextBudgetManager.buildBudgetedSystemPrompt(
            profile, memories, "user123", "conv-abc", "测试问题");
    
    // 验证对话历史 token 不超过预算
    assertThat(contextBudgetManager.getLastConversationTokens()).isLessThanOrEqualTo(600);
}
```

- [ ] **Step 2: 运行测试验证失败**

```bash
mvn test -Dtest=ContextBudgetManagerTest
```

预期输出：新增测试失败，提示方法签名不匹配或缺少依赖

- [ ] **Step 3: 修改 ContextBudgetManager 集成对话历史**

在 ContextBudgetManager 类中添加对话历史管理器注入和集成逻辑：

```java
@Slf4j
@Component
public class ContextBudgetManager {
    
    @Autowired
    private ContextBudgetProperties properties;
    
    @Autowired
    private ConversationHistoryManager conversationHistoryManager;
    
    // Token 使用跟踪
    private int lastCoreProfileTokens = 0;
    private int lastMemoriesTokens = 0;
    private int lastConversationTokens = 0;
    private int lastExtendedProfileTokens = 0;
    private int lastTotalTokens = 0;
    
    /**
     * 构建受预算约束的系统提示词（增强版，包含对话历史）
     */
    public String buildBudgetedSystemPrompt(
            UserProfileDTO profile,
            List<MemoryItemDTO> memories,
            String customerId,
            String conversationId,
            String userQuestion) {
        
        long startTime = System.currentTimeMillis();
        
        // 重置 token 计数
        resetTokenCounters();
        
        StringBuilder prompt = new StringBuilder();
        
        // 1. 核心画像（必须，优先级最高）
        String coreProfile = extractCoreProfile(profile);
        coreProfile = ensureWithinBudget(coreProfile, properties.getCoreProfileBudget());
        lastCoreProfileTokens = estimateTokens(coreProfile);
        prompt.append("# 用户核心画像\n").append(coreProfile).append("\n\n");
        
        // 2. 相关记忆（重要）
        String memoriesText = formatMemories(memories, properties.getRelevantMemoriesBudget());
        lastMemoriesTokens = estimateTokens(memoriesText);
        if (!memoriesText.isEmpty()) {
            prompt.append("# 相关记忆\n").append(memoriesText).append("\n\n");
        }
        
        // 3. 对话历史（重要，新增）
        String conversationHistory = buildConversationHistory(
                customerId, conversationId, properties.getConversationHistoryBudget());
        lastConversationTokens = estimateTokens(conversationHistory);
        if (!conversationHistory.isEmpty()) {
            prompt.append("# 对话历史\n").append(conversationHistory).append("\n\n");
        }
        
        // 4. 扩展画像（可选，根据问题类型）
        String extendedProfile = extractExtendedProfile(profile, userQuestion);
        extendedProfile = ensureWithinBudget(extendedProfile, properties.getExtendedProfileBudget());
        lastExtendedProfileTokens = estimateTokens(extendedProfile);
        if (!extendedProfile.isEmpty()) {
            prompt.append("# 扩展信息\n").append(extendedProfile).append("\n\n");
        }
        
        lastTotalTokens = lastCoreProfileTokens + lastMemoriesTokens + 
                         lastConversationTokens + lastExtendedProfileTokens;
        
        long buildTime = System.currentTimeMillis() - startTime;
        log.debug("上下文构建完成 - 耗时: {}ms, tokens: {}", buildTime, lastTotalTokens);
        
        return prompt.toString();
    }
    
    /**
     * 构建对话历史文本
     */
    private String buildConversationHistory(String customerId, String conversationId, int budget) {
        try {
            // 获取最近的对话历史
            int maxRounds = properties.getConversation().getMaxRounds();
            List<ConversationMessage> history = conversationHistoryManager.getRecentHistory(
                    customerId, conversationId, maxRounds);
            
            if (history.isEmpty()) {
                log.debug("无对话历史");
                return "";
            }
            
            // 格式化对话历史（受预算约束）
            String formatted = conversationHistoryManager.formatHistory(history, budget);
            
            log.debug("对话历史构建完成 - 轮数: {}, tokens: {}", 
                    history.size() / 2, estimateTokens(formatted));
            
            return formatted;
            
        } catch (Exception e) {
            log.error("构建对话历史失败", e);
            return "";
        }
    }
    
    /**
     * 重置 token 计数器
     */
    private void resetTokenCounters() {
        lastCoreProfileTokens = 0;
        lastMemoriesTokens = 0;
        lastConversationTokens = 0;
        lastExtendedProfileTokens = 0;
        lastTotalTokens = 0;
    }
    
    // Getter 方法
    public int getLastCoreProfileTokens() {
        return lastCoreProfileTokens;
    }
    
    public int getLastMemoriesTokens() {
        return lastMemoriesTokens;
    }
    
    public int getLastConversationTokens() {
        return lastConversationTokens;
    }
    
    public int getLastExtendedProfileTokens() {
        return lastExtendedProfileTokens;
    }
    
    public int getLastTotalTokens() {
        return lastTotalTokens;
    }
    
    // ... 保留现有的其他方法 ...
}
```

- [ ] **Step 4: 运行测试验证通过**

```bash
mvn test -Dtest=ContextBudgetManagerTest
```

预期输出：所有测试通过

- [ ] **Step 5: 提交代码**

```bash
git add ai-chat-service/src/main/java/com/lx/governance/aichat/context/ContextBudgetManager.java
git add ai-chat-service/src/test/java/com/lx/governance/aichat/context/ContextBudgetManagerTest.java
git commit -m "feat: integrate conversation history in ContextBudgetManager"
```

---

### Task 10: 在 AiChatService 中使用分层记忆检索

**Files:**
- Modify: `ai-chat-service/src/main/java/com/lx/governance/aichat/service/AiChatService.java`
- Modify: `ai-chat-service/src/test/java/com/lx/governance/aichat/service/AiChatServiceTest.java`

- [ ] **Step 1: 添加测试验证分层记忆检索集成**

在现有测试类中添加新测试：

```java
@Test
void shouldUseHierarchicalMemoryRetrieval() {
    // 模拟分层记忆检索
    List<MemoryItemDTO> hierarchicalMemories = Arrays.asList(
            createMemory("短期记忆", LocalDateTime.now().minusDays(1), 0.9),
            createMemory("中期记忆", LocalDateTime.now().minusDays(10), 0.85),
            createMemory("长期记忆", LocalDateTime.now().minusDays(60), 0.75)
    );
    
    when(hierarchicalMemoryManager.retrieveHierarchicalMemories(
            anyString(), anyString(), anyString(), anyInt()))
            .thenReturn(hierarchicalMemories);
    
    when(userProfileService.getUserProfile(anyString())).thenReturn(createTestProfile());
    when(contextBudgetManager.buildBudgetedSystemPrompt(any(), any(), anyString(), anyString(), anyString()))
            .thenReturn("系统提示词");
    when(deepSeekClient.chat(any())).thenReturn(createTestChatResponse());
    
    ChatRequest request = ChatRequest.builder()
            .customerId("user123")
            .conversationId("conv-abc")
            .userMessage("我以前的健康趋势如何？")
            .build();
    
    ChatResponse response = aiChatService.chat(request);
    
    // 验证使用了分层记忆检索
    verify(hierarchicalMemoryManager, times(1)).retrieveHierarchicalMemories(
            eq("user123"), anyString(), eq("我以前的健康趋势如何？"), anyInt());
    
    // 验证不再使用旧的记忆检索方法
    verify(memoryService, never()).searchMemories(anyString(), anyString(), anyString(), anyInt());
    
    assertThat(response).isNotNull();
    assertThat(response.getSuccess()).isTrue();
}

@Test
void shouldPassCorrectParametersToHierarchicalMemoryManager() {
    when(hierarchicalMemoryManager.retrieveHierarchicalMemories(
            anyString(), anyString(), anyString(), anyInt()))
            .thenReturn(Collections.emptyList());
    
    when(userProfileService.getUserProfile(anyString())).thenReturn(createTestProfile());
    when(contextBudgetManager.buildBudgetedSystemPrompt(any(), any(), anyString(), anyString(), anyString()))
            .thenReturn("系统提示词");
    when(deepSeekClient.chat(any())).thenReturn(createTestChatResponse());
    
    ChatRequest request = ChatRequest.builder()
            .customerId("user123")
            .conversationId("conv-abc")
            .userMessage("测试问题")
            .build();
    
    aiChatService.chat(request);
    
    // 验证传递了正确的参数
    ArgumentCaptor<String> customerIdCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> groupIdCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> questionCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Integer> maxCountCaptor = ArgumentCaptor.forClass(Integer.class);
    
    verify(hierarchicalMemoryManager).retrieveHierarchicalMemories(
            customerIdCaptor.capture(),
            groupIdCaptor.capture(),
            questionCaptor.capture(),
            maxCountCaptor.capture());
    
    assertThat(customerIdCaptor.getValue()).isEqualTo("user123");
    assertThat(questionCaptor.getValue()).isEqualTo("测试问题");
    assertThat(maxCountCaptor.getValue()).isEqualTo(10);  // 从配置读取
}

@Test
void shouldHandleMemoryRetrievalFailureGracefully() {
    // 模拟记忆检索失败
    when(hierarchicalMemoryManager.retrieveHierarchicalMemories(
            anyString(), anyString(), anyString(), anyInt()))
            .thenThrow(new RuntimeException("记忆检索失败"));
    
    when(userProfileService.getUserProfile(anyString())).thenReturn(createTestProfile());
    when(contextBudgetManager.buildBudgetedSystemPrompt(any(), any(), anyString(), anyString(), anyString()))
            .thenReturn("系统提示词");
    when(deepSeekClient.chat(any())).thenReturn(createTestChatResponse());
    
    ChatRequest request = ChatRequest.builder()
            .customerId("user123")
            .conversationId("conv-abc")
            .userMessage("测试问题")
            .build();
    
    // 应该继续执行，使用空记忆列表
    ChatResponse response = aiChatService.chat(request);
    
    assertThat(response).isNotNull();
    assertThat(response.getSuccess()).isTrue();
    
    // 验证仍然调用了 ContextBudgetManager（使用空记忆列表）
    verify(contextBudgetManager).buildBudgetedSystemPrompt(
            any(), eq(Collections.emptyList()), anyString(), anyString(), anyString());
}

private MemoryItemDTO createMemory(String content, LocalDateTime time, double similarity) {
    MemoryItemDTO memory = new MemoryItemDTO();
    memory.setId(String.valueOf(System.nanoTime()));
    memory.setContent(content);
    memory.setCreateTime(time);
    memory.setSimilarity(similarity);
    return memory;
}

private UserProfileDTO createTestProfile() {
    UserProfileDTO profile = new UserProfileDTO();
    profile.setCustomerId("user123");
    profile.setName("测试用户");
    profile.setAge(35);
    profile.setGender("男");
    return profile;
}

private ChatResponse createTestChatResponse() {
    return ChatResponse.builder()
            .success(true)
            .message("AI 回复")
            .promptTokens(3000)
            .completionTokens(400)
            .totalTokens(3400)
            .build();
}
```

- [ ] **Step 2: 运行测试验证失败**

```bash
mvn test -Dtest=AiChatServiceTest
```

预期输出：新增测试失败，提示缺少 HierarchicalMemoryManager 依赖

- [ ] **Step 3: 修改 AiChatService 使用分层记忆检索**

```java
@Slf4j
@Service
public class AiChatService {
    
    @Autowired
    private UserProfileService userProfileService;
    
    @Autowired
    private HierarchicalMemoryManager hierarchicalMemoryManager;
    
    @Autowired
    private ContextBudgetManager contextBudgetManager;
    
    @Autowired
    private DeepSeekClient deepSeekClient;
    
    @Autowired
    private AiChatHistoryMapper chatHistoryMapper;
    
    @Autowired
    private ContextBudgetProperties properties;
    
    /**
     * AI 聊天服务
     */
    public ChatResponse chat(ChatRequest request) {
        long startTime = System.currentTimeMillis();
        
        try {
            // 1. 获取用户画像
            UserProfileDTO profile = userProfileService.getUserProfile(request.getCustomerId());
            if (profile == null) {
                log.warn("用户画像不存在 - customerId: {}", request.getCustomerId());
                return ChatResponse.builder()
                        .success(false)
                        .message("用户画像不存在")
                        .build();
            }
            
            // 2. 使用分层记忆检索（替换原有的记忆检索）
            List<MemoryItemDTO> memories = retrieveMemories(
                    request.getCustomerId(),
                    profile.getGroupId(),
                    request.getUserMessage());
            
            log.info("记忆检索完成 - customerId: {}, 记忆数: {}", 
                    request.getCustomerId(), memories.size());
            
            // 3. 构建系统提示词（包含对话历史）
            String systemPrompt = contextBudgetManager.buildBudgetedSystemPrompt(
                    profile,
                    memories,
                    request.getCustomerId(),
                    request.getConversationId(),
                    request.getUserMessage());
            
            log.debug("系统提示词构建完成 - tokens: {}", 
                    contextBudgetManager.getLastTotalTokens());
            
            // 4. 调用 DeepSeek API
            ChatRequest apiRequest = ChatRequest.builder()
                    .customerId(request.getCustomerId())
                    .conversationId(request.getConversationId())
                    .userMessage(request.getUserMessage())
                    .systemPrompt(systemPrompt)
                    .build();
            
            ChatResponse response = deepSeekClient.chat(apiRequest);
            
            // 5. 保存对话历史
            saveConversationHistory(request, response);
            
            long totalTime = System.currentTimeMillis() - startTime;
            log.info("AI 聊天完成 - customerId: {}, 耗时: {}ms", 
                    request.getCustomerId(), totalTime);
            
            return response;
            
        } catch (Exception e) {
            log.error("AI 聊天失败 - customerId: {}", request.getCustomerId(), e);
            return ChatResponse.builder()
                    .success(false)
                    .message("AI 服务调用失败: " + e.getMessage())
                    .build();
        }
    }
    
    /**
     * 检索记忆（使用分层检索策略）
     */
    private List<MemoryItemDTO> retrieveMemories(
            String customerId, 
            String groupId, 
            String userQuestion) {
        
        try {
            int maxCount = properties.getMemory().getMaxCount();
            
            // 使用分层记忆管理器检索
            List<MemoryItemDTO> memories = hierarchicalMemoryManager.retrieveHierarchicalMemories(
                    customerId,
                    groupId,
                    userQuestion,
                    maxCount);
            
            log.debug("分层记忆检索完成 - 短期/中期/长期记忆总计: {} 条", memories.size());
            
            return memories;
            
        } catch (Exception e) {
            log.error("记忆检索失败，使用空记忆列表", e);
            return Collections.emptyList();
        }
    }
    
    /**
     * 保存对话历史
     */
    private void saveConversationHistory(ChatRequest request, ChatResponse response) {
        try {
            AiChatHistory history = new AiChatHistory();
            history.setCustomerId(request.getCustomerId());
            history.setConversationId(request.getConversationId());
            history.setUserMessage(request.getUserMessage());
            history.setAiResponse(response.getMessage());
            history.setPromptTokens(response.getPromptTokens());
            history.setCompletionTokens(response.getCompletionTokens());
            history.setTotalTokens(response.getTotalTokens());
            history.setCreateTime(LocalDateTime.now());
            history.setIsDeleted(0);
            
            chatHistoryMapper.insert(history);
            
            log.debug("对话历史已保存 - conversationId: {}", request.getConversationId());
            
        } catch (Exception e) {
            log.error("保存对话历史失败", e);
            // 不影响主流程，只记录错误
        }
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

```bash
mvn test -Dtest=AiChatServiceTest
```

预期输出：所有测试通过

- [ ] **Step 5: 运行集成测试**

启动服务并进行端到端测试：

```bash
# 启动服务
mvn spring-boot:run

# 在另一个终端测试 API
curl -X POST http://localhost:8085/api/ai-chat/chat \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "test-user-001",
    "conversationId": "conv-test-001",
    "userMessage": "我以前的血压趋势如何？"
  }'
```

预期输出：
- 返回 AI 回复
- 日志中显示分层记忆检索信息
- 日志中显示对话历史信息
- Token 使用在预算范围内

- [ ] **Step 6: 验证日志输出**

检查日志文件，确认以下信息：

```
INFO  - 记忆检索完成 - customerId: test-user-001, 记忆数: 8
DEBUG - 短期记忆: 2 条
DEBUG - 中期记忆: 4 条
DEBUG - 长期记忆: 2 条
DEBUG - 记忆检索完成 - 总计: 8 条
DEBUG - 对话历史构建完成 - 轮数: 3, tokens: 450
DEBUG - 系统提示词构建完成 - tokens: 2800
INFO  - Token 使用详情 - customerId: test-user-001, conversationId: conv-test-001, 
       核心画像: 650, 记忆: 980, 对话: 450, 扩展: 320, 
       系统提示词总计: 2400, API返回: prompt=2450, completion=380, total=2830
INFO  - AI 聊天完成 - customerId: test-user-001, 耗时: 1250ms
```

- [ ] **Step 7: 提交代码**

```bash
git add ai-chat-service/src/main/java/com/lx/governance/aichat/service/AiChatService.java
git add ai-chat-service/src/test/java/com/lx/governance/aichat/service/AiChatServiceTest.java
git commit -m "feat: use hierarchical memory retrieval in AiChatService"
```

---

## 验证和总结

### 完成标准

**阶段 1（配置化和监控）：**
- [x] Task 1: 创建配置属性类
- [x] Task 2: 添加配置文件
- [x] Task 3: 创建 Token 监控组件
- [x] Task 4: 修改 ContextBudgetManager 使用配置化预算
- [x] Task 5: 集成 TokenMonitor 到 DeepSeekClient

**阶段 2（记忆分层和对话历史）：**
- [x] Task 6: 创建对话历史数据模型
- [x] Task 7: 创建对话历史管理器
- [x] Task 8: 创建记忆分层管理器
- [x] Task 9: 在 ContextBudgetManager 中集成对话历史
- [x] Task 10: 在 AiChatService 中使用分层记忆检索

### 功能验证清单

- [ ] **配置验证**
  - [ ] 配置文件可以调整预算分配
  - [ ] 各部分预算配置生效
  - [ ] 告警阈值配置生效

- [ ] **Token 监控验证**
  - [ ] Token 使用日志完整准确
  - [ ] 超过阈值时触发告警
  - [ ] 各部分 token 使用统计正确

- [ ] **对话历史验证**
  - [ ] 对话历史正确注入到系统提示词
  - [ ] 对话历史受预算约束
  - [ ] 最近 N 轮对话正确获取

- [ ] **记忆分层验证**
  - [ ] 短期记忆优先检索
  - [ ] 中期记忆按相似度检索
  - [ ] 长期记忆仅在特定问题时检索
  - [ ] 记忆去重有效

- [ ] **性能验证**
  - [ ] 上下文构建耗时 < 100ms
  - [ ] Prompt tokens 控制在 4000 以内
  - [ ] API 响应时间 < 3s

- [ ] **质量验证**
  - [ ] AI 回复质量不下降
  - [ ] 关键信息（过敏、慢性病）不丢失
  - [ ] 对话连贯性良好

### 预期效果

1. **Token 控制**
   - ✅ Prompt tokens 控制在 4000 以内
   - ✅ 各部分预算分配合理
   - ✅ 超预算时自动压缩或截断

2. **上下文质量**
   - ✅ 核心画像信息完整
   - ✅ 相关记忆准确
   - ✅ 对话历史连贯
   - ✅ 扩展画像按需加载

3. **性能提升**
   - ✅ 降低 API 调用成本 30-50%
   - ✅ 提升响应速度
   - ✅ 减少不必要的上下文

4. **可观测性**
   - ✅ 详细的 token 使用日志
   - ✅ 超限告警
   - ✅ 性能指标监控

### 后续优化建议

1. **短期优化**
   - 添加 Redis 缓存最近对话历史
   - 优化 token 估算算法（使用 tiktoken）
   - 添加 Prometheus 指标导出

2. **中期优化**
   - 实现语义相似度去重（使用向量距离）
   - 添加用户反馈机制（记忆质量评分）
   - 支持多模型切换（GPT-4、Claude 等）

3. **长期优化**
   - 实现自适应预算分配（基于问题类型）
   - 添加记忆重要性评分（自动淘汰低质量记忆）
   - 支持多轮对话上下文压缩（摘要生成）

---

**实施完成后，请运行完整的测试套件并验证所有功能正常工作。**

```bash
# 运行所有测试
mvn clean test

# 启动服务进行手动测试
mvn spring-boot:run

# 检查日志输出
tail -f logs/ai-chat-service.log
```

