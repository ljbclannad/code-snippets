# Token预算管理重构实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 重构Token预算管理，调整预算分配，增加会话上下文权重，实现智能压缩

**Architecture:** 三层预算分配（必需层、重要层、可选层），动态调整策略，智能内容压缩，增强查询构建

**Tech Stack:** Spring Boot, DeepSeek API, 自定义预算管理器

---

## 文件结构

### 新增文件
- `ai-chat-service/src/main/java/com/lx/governance/aichat/context/ContextBudgetManagerV2.java` - 新版预算管理器
- `ai-chat-service/src/main/java/com/lx/governance/aichat/context/BudgetAllocation.java` - 预算分配结果
- `ai-chat-service/src/main/java/com/lx/governance/aichat/context/ContentCompressor.java` - 内容压缩器
- `ai-chat-service/src/test/java/com/lx/governance/aichat/context/ContextBudgetManagerV2Test.java` - 单元测试

### 修改文件
- `ai-chat-service/src/main/java/com/lx/governance/aichat/service/AiChatService.java` - 使用新版预算管理器
- `ai-chat-service/src/main/java/com/lx/governance/aichat/prompt/PromptBuilder.java` - 集成会话上下文

---

## Task 1: 创建BudgetAllocation数据结构

**Files:**
- Create: `ai-chat-service/src/main/java/com/lx/governance/aichat/context/BudgetAllocation.java`

- [ ] **Step 1: 创建BudgetAllocation类**

```java
package com.lx.governance.aichat.context;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BudgetAllocation {
    
    /**
     * 核心画像预算
     */
    private int coreProfileBudget;
    
    /**
     * 会话上下文预算
     */
    private int sessionContextBudget;
    
    /**
     * 相关记忆预算
     */
    private int memoryBudget;
    
    /**
     * 扩展画像预算
     */
    private int extendedProfileBudget;
    
    /**
     * 用户消息预留
     */
    private int userMessageReserve;
    
    /**
     * 总预算
     */
    private int totalBudget;
    
    /**
     * 实际使用
     */
    private int actualUsage;
    
    /**
     * 是否需要压缩
     */
    private boolean needsCompression;
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
git add ai-chat-service/src/main/java/com/lx/governance/aichat/context/BudgetAllocation.java
git commit -m "feat: add BudgetAllocation data structure"
```

---

## Task 2: 创建ContentCompressor

**Files:**
- Create: `ai-chat-service/src/main/java/com/lx/governance/aichat/context/ContentCompressor.java`

- [ ] **Step 1: 创建压缩器类**

```java
package com.lx.governance.aichat.context;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@Slf4j
public class ContentCompressor {
    
    private static final int CHARS_PER_TOKEN = 2;
    
    /**
     * 压缩文本到目标token数
     */
    public String compressToTokens(String text, int targetTokens) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        
        int currentTokens = estimateTokens(text);
        
        if (currentTokens <= targetTokens) {
            return text;
        }
        
        // 简单截断策略
        int targetChars = targetTokens * CHARS_PER_TOKEN;
        if (text.length() <= targetChars) {
            return text;
        }
        
        return text.substring(0, targetChars) + "...";
    }
    
    /**
     * 压缩列表内容
     */
    public List<String> compressListToTokens(List<String> items, int targetTokens) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        
        int tokensPerItem = targetTokens / items.size();
        
        return items.stream()
            .map(item -> compressToTokens(item, tokensPerItem))
            .collect(Collectors.toList());
    }
    
    /**
     * 估算token数量
     */
    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        
        // 简化估算：中文1字符≈1token，英文1字符≈0.5token
        int chineseCount = 0;
        int otherCount = 0;
        
        for (char c : text.toCharArray()) {
            if (isChinese(c)) {
                chineseCount++;
            } else {
                otherCount++;
            }
        }
        
        return chineseCount + (otherCount / 2);
    }
    
    /**
     * 判断是否为中文字符
     */
    private boolean isChinese(char c) {
        return c >= 0x4E00 && c <= 0x9FA5;
    }
    
    /**
     * 智能摘要（保留关键信息）
     */
    public String smartSummarize(String text, int targetTokens) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        
        int currentTokens = estimateTokens(text);
        
        if (currentTokens <= targetTokens) {
            return text;
        }
        
        // 按句子分割
        String[] sentences = text.split("[。！？\\n]");
        
        if (sentences.length <= 1) {
            return compressToTokens(text, targetTokens);
        }
        
        // 保留前几句和最后一句
        StringBuilder result = new StringBuilder();
        int tokensUsed = 0;
        int tokensPerSentence = targetTokens / Math.min(3, sentences.length);
        
        // 添加前两句
        for (int i = 0; i < Math.min(2, sentences.length) && tokensUsed < targetTokens; i++) {
            String sentence = sentences[i].trim();
            if (!sentence.isEmpty()) {
                int sentenceTokens = estimateTokens(sentence);
                if (tokensUsed + sentenceTokens <= targetTokens) {
                    result.append(sentence).append("。");
                    tokensUsed += sentenceTokens;
                }
            }
        }
        
        // 添加最后一句
        if (sentences.length > 2 && tokensUsed < targetTokens) {
            String lastSentence = sentences[sentences.length - 1].trim();
            if (!lastSentence.isEmpty()) {
                int sentenceTokens = estimateTokens(lastSentence);
                if (tokensUsed + sentenceTokens <= targetTokens) {
                    result.append("...").append(lastSentence).append("。");
                }
            }
        }
        
        return result.toString();
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
git add ai-chat-service/src/main/java/com/lx/governance/aichat/context/ContentCompressor.java
git commit -m "feat: add ContentCompressor for smart text compression"
```

---

## Task 3: 创建ContextBudgetManagerV2（第1部分）

**Files:**
- Create: `ai-chat-service/src/main/java/com/lx/governance/aichat/context/ContextBudgetManagerV2.java`

- [ ] **Step 1: 创建管理器类框架**

```java
package com.lx.governance.aichat.context;

import com.lx.governance.aichat.dto.SessionContext;
import com.lx.governance.profile.dto.UserProfileDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Slf4j
public class ContextBudgetManagerV2 {
    
    @Autowired
    private ContentCompressor compressor;
    
    // 预算常量
    private static final int TOTAL_BUDGET = 4000;
    private static final int CORE_PROFILE_BUDGET = 600;
    private static final int SESSION_CONTEXT_BUDGET = 800;
    private static final int MEMORY_BUDGET = 1200;
    private static final int EXTENDED_PROFILE_BUDGET = 200;
    private static final int USER_MESSAGE_RESERVE = 500;
    
    /**
     * 分配预算
     */
    public BudgetAllocation allocateBudget(
            UserProfileDTO profile,
            SessionContext sessionContext,
            List<String> memories,
            String userMessage) {
        
        BudgetAllocation allocation = BudgetAllocation.builder()
            .coreProfileBudget(CORE_PROFILE_BUDGET)
            .sessionContextBudget(SESSION_CONTEXT_BUDGET)
            .memoryBudget(MEMORY_BUDGET)
            .extendedProfileBudget(EXTENDED_PROFILE_BUDGET)
            .userMessageReserve(USER_MESSAGE_RESERVE)
            .totalBudget(TOTAL_BUDGET)
            .build();
        
        // 计算实际使用
        int actualUsage = calculateActualUsage(profile, sessionContext, memories, userMessage);
        allocation.setActualUsage(actualUsage);
        
        // 判断是否需要压缩
        allocation.setNeedsCompression(actualUsage > TOTAL_BUDGET);
        
        return allocation;
    }
    
    /**
     * 计算实际使用量
     */
    private int calculateActualUsage(
            UserProfileDTO profile,
            SessionContext sessionContext,
            List<String> memories,
            String userMessage) {
        
        int profileTokens = estimateProfileTokens(profile);
        int contextTokens = estimateContextTokens(sessionContext);
        int memoryTokens = estimateMemoriesTokens(memories);
        int messageTokens = compressor.estimateTokens(userMessage);
        
        return profileTokens + contextTokens + memoryTokens + messageTokens;
    }
    
    // 估算方法将在下一个任务实现
    private int estimateProfileTokens(UserProfileDTO profile) {
        // TODO: Task 4实现
        return 0;
    }
    
    private int estimateContextTokens(SessionContext sessionContext) {
        // TODO: Task 4实现
        return 0;
    }
    
    private int estimateMemoriesTokens(List<String> memories) {
        // TODO: Task 4实现
        return 0;
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
git add ai-chat-service/src/main/java/com/lx/governance/aichat/context/ContextBudgetManagerV2.java
git commit -m "feat: add ContextBudgetManagerV2 skeleton"
```

---

## Task 4: 完成ContextBudgetManagerV2实现

**Files:**
- Modify: `ai-chat-service/src/main/java/com/lx/governance/aichat/context/ContextBudgetManagerV2.java`

- [ ] **Step 1: 实现estimateProfileTokens方法**

替换方法：

```java
private int estimateProfileTokens(UserProfileDTO profile) {
    if (profile == null) {
        return 0;
    }
    
    StringBuilder profileText = new StringBuilder();
    
    // 基本信息
    if (profile.getBasicInfo() != null) {
        profileText.append("姓名:").append(profile.getBasicInfo().getName())
            .append(",年龄:").append(profile.getBasicInfo().getAge())
            .append(",性别:").append(profile.getBasicInfo().getGender());
    }
    
    // 健康档案
    if (profile.getHealthProfile() != null) {
        profileText.append(",BMI:").append(profile.getHealthProfile().getBmi())
            .append(",血压:").append(profile.getHealthProfile().getBloodPressure());
    }
    
    return compressor.estimateTokens(profileText.toString());
}
```

- [ ] **Step 2: 实现estimateContextTokens方法**

替换方法：

```java
private int estimateContextTokens(SessionContext sessionContext) {
    if (sessionContext == null) {
        return 0;
    }
    
    StringBuilder contextText = new StringBuilder();
    
    contextText.append("话题:").append(sessionContext.getCurrentTopic())
        .append(",阶段:").append(sessionContext.getConversationPhase())
        .append(",意图:").append(sessionContext.getUserIntent());
    
    // 最近消息摘要
    if (sessionContext.getRecentSummaries() != null) {
        for (var summary : sessionContext.getRecentSummaries()) {
            contextText.append(",").append(summary.getUserMessage());
        }
    }
    
    return compressor.estimateTokens(contextText.toString());
}
```

- [ ] **Step 3: 实现estimateMemoriesTokens方法**

替换方法：

```java
private int estimateMemoriesTokens(List<String> memories) {
    if (memories == null || memories.isEmpty()) {
        return 0;
    }
    
    return memories.stream()
        .mapToInt(compressor::estimateTokens)
        .sum();
}
```

- [ ] **Step 4: 添加压缩方法**

在类中添加：

```java
/**
 * 压缩内容以适应预算
 */
public CompressedContent compressContent(
        UserProfileDTO profile,
        SessionContext sessionContext,
        List<String> memories,
        BudgetAllocation allocation) {
    
    if (!allocation.isNeedsCompression()) {
        return CompressedContent.builder()
            .coreProfile(buildCoreProfile(profile))
            .sessionContext(buildSessionContext(sessionContext))
            .memories(memories)
            .build();
    }
    
    log.info("内容超出预算，开始压缩: actual={}, budget={}", 
        allocation.getActualUsage(), allocation.getTotalBudget());
    
    // 1. 核心画像（必需层，不压缩）
    String coreProfile = buildCoreProfile(profile);
    
    // 2. 会话上下文（必需层，轻度压缩）
    String compressedContext = compressor.smartSummarize(
        buildSessionContext(sessionContext),
        allocation.getSessionContextBudget()
    );
    
    // 3. 相关记忆（重要层，按需压缩）
    List<String> compressedMemories = compressor.compressListToTokens(
        memories,
        allocation.getMemoryBudget()
    );
    
    // 4. 扩展画像（可选层，可能被丢弃）
    String extendedProfile = "";
    int usedTokens = compressor.estimateTokens(coreProfile) +
                     compressor.estimateTokens(compressedContext) +
                     estimateMemoriesTokens(compressedMemories);
    
    if (usedTokens + allocation.getUserMessageReserve() < allocation.getTotalBudget()) {
        int remaining = allocation.getTotalBudget() - usedTokens - allocation.getUserMessageReserve();
        extendedProfile = buildExtendedProfile(profile, remaining);
    }
    
    return CompressedContent.builder()
        .coreProfile(coreProfile)
        .sessionContext(compressedContext)
        .memories(compressedMemories)
        .extendedProfile(extendedProfile)
        .build();
}

/**
 * 构建核心画像文本
 */
private String buildCoreProfile(UserProfileDTO profile) {
    if (profile == null || profile.getBasicInfo() == null) {
        return "";
    }
    
    return String.format("用户：%s，%d岁，%s",
        profile.getBasicInfo().getName(),
        profile.getBasicInfo().getAge(),
        profile.getBasicInfo().getGender());
}

/**
 * 构建会话上下文文本
 */
private String buildSessionContext(SessionContext context) {
    if (context == null) {
        return "";
    }
    
    StringBuilder sb = new StringBuilder();
    sb.append("当前话题：").append(context.getCurrentTopic()).append("\n");
    sb.append("对话阶段：").append(context.getConversationPhase()).append("\n");
    sb.append("用户意图：").append(context.getUserIntent()).append("\n");
    
    if (context.getRecentSummaries() != null && !context.getRecentSummaries().isEmpty()) {
        sb.append("最近对话：\n");
        for (var summary : context.getRecentSummaries()) {
            sb.append("- ").append(summary.getUserMessage()).append("\n");
        }
    }
    
    return sb.toString();
}

/**
 * 构建扩展画像文本
 */
private String buildExtendedProfile(UserProfileDTO profile, int maxTokens) {
    if (profile == null) {
        return "";
    }
    
    StringBuilder sb = new StringBuilder();
    
    // 健康档案
    if (profile.getHealthProfile() != null) {
        sb.append("健康档案：BMI ").append(profile.getHealthProfile().getBmi())
          .append("，血压 ").append(profile.getHealthProfile().getBloodPressure())
          .append("\n");
    }
    
    // 行为偏好
    if (profile.getBehaviorPreferences() != null) {
        sb.append("饮食偏好：").append(profile.getBehaviorPreferences().getDietPreference())
          .append("\n");
    }
    
    return compressor.compressToTokens(sb.toString(), maxTokens);
}

/**
 * 压缩后的内容
 */
@lombok.Data
@lombok.Builder
public static class CompressedContent {
    private String coreProfile;
    private String sessionContext;
    private List<String> memories;
    private String extendedProfile;
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
git add ai-chat-service/src/main/java/com/lx/governance/aichat/context/ContextBudgetManagerV2.java
git commit -m "feat: complete ContextBudgetManagerV2 with compression logic"
```

---

## Task 5: 编写ContextBudgetManagerV2单元测试

**Files:**
- Create: `ai-chat-service/src/test/java/com/lx/governance/aichat/context/ContextBudgetManagerV2Test.java`

- [ ] **Step 1: 创建测试类**

```java
package com.lx.governance.aichat.context;

import com.lx.governance.aichat.dto.MessageSummary;
import com.lx.governance.aichat.dto.SessionContext;
import com.lx.governance.profile.dto.BasicInfoDTO;
import com.lx.governance.profile.dto.HealthProfileDTO;
import com.lx.governance.profile.dto.UserProfileDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContextBudgetManagerV2Test {
    
    @Mock
    private ContentCompressor compressor;
    
    @InjectMocks
    private ContextBudgetManagerV2 budgetManager;
    
    private UserProfileDTO mockProfile;
    private SessionContext mockContext;
    private List<String> mockMemories;
    
    @BeforeEach
    void setUp() {
        // Mock profile
        mockProfile = new UserProfileDTO();
        BasicInfoDTO basic = new BasicInfoDTO();
        basic.setName("张三");
        basic.setAge(35);
        basic.setGender("男");
        mockProfile.setBasicInfo(basic);
        
        HealthProfileDTO health = new HealthProfileDTO();
        health.setBmi(24.5);
        health.setBloodPressure("120/80");
        mockProfile.setHealthProfile(health);
        
        // Mock context
        mockContext = SessionContext.builder()
            .currentTopic("血糖管理")
            .conversationPhase("consulting")
            .userIntent("咨询血糖控制")
            .recentSummaries(Arrays.asList(
                MessageSummary.builder().userMessage("我的血糖有点高").build(),
                MessageSummary.builder().userMessage("应该怎么控制").build()
            ))
            .build();
        
        // Mock memories
        mockMemories = Arrays.asList(
            "用户喜欢吃苹果",
            "用户每天运动30分钟",
            "用户血糖目标是5.5"
        );
        
        // Mock compressor
        when(compressor.estimateTokens(anyString())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0);
            return text.length() / 2; // 简化估算
        });
    }
    
    @Test
    void testAllocateBudget_WithinLimit() {
        // Given
        String userMessage = "今天吃什么好？";
        
        // When
        BudgetAllocation allocation = budgetManager.allocateBudget(
            mockProfile, mockContext, mockMemories, userMessage);
        
        // Then
        assertNotNull(allocation);
        assertEquals(600, allocation.getCoreProfileBudget());
        assertEquals(800, allocation.getSessionContextBudget());
        assertEquals(1200, allocation.getMemoryBudget());
        assertEquals(200, allocation.getExtendedProfileBudget());
        assertEquals(500, allocation.getUserMessageReserve());
        assertEquals(4000, allocation.getTotalBudget());
    }
    
    @Test
    void testAllocateBudget_ExceedsLimit() {
        // Given
        String longMessage = "这是一条很长的消息".repeat(100);
        when(compressor.estimateTokens(longMessage)).thenReturn(5000);
        
        // When
        BudgetAllocation allocation = budgetManager.allocateBudget(
            mockProfile, mockContext, mockMemories, longMessage);
        
        // Then
        assertTrue(allocation.isNeedsCompression());
        assertTrue(allocation.getActualUsage() > allocation.getTotalBudget());
    }
    
    @Test
    void testCompressContent_NoCompressionNeeded() {
        // Given
        BudgetAllocation allocation = BudgetAllocation.builder()
            .needsCompression(false)
            .build();
        
        // When
        var result = budgetManager.compressContent(
            mockProfile, mockContext, mockMemories, allocation);
        
        // Then
        assertNotNull(result);
        assertNotNull(result.getCoreProfile());
        assertNotNull(result.getSessionContext());
        assertEquals(3, result.getMemories().size());
    }
    
    @Test
    void testCompressContent_CompressionNeeded() {
        // Given
        BudgetAllocation allocation = BudgetAllocation.builder()
            .needsCompression(true)
            .sessionContextBudget(800)
            .memoryBudget(1200)
            .totalBudget(4000)
            .userMessageReserve(500)
            .build();
        
        when(compressor.smartSummarize(anyString(), anyInt())).thenReturn("压缩后的上下文");
        when(compressor.compressListToTokens(mockMemories, 1200)).thenReturn(mockMemories);
        when(compressor.compressToTokens(anyString(), anyInt())).thenReturn("压缩后的扩展画像");
        
        // When
        var result = budgetManager.compressContent(
            mockProfile, mockContext, mockMemories, allocation);
        
        // Then
        assertNotNull(result);
        assertNotNull(result.getCoreProfile());
        assertEquals("压缩后的上下文", result.getSessionContext());
    }
}
```

- [ ] **Step 2: 运行测试**

```bash
cd ai-chat-service
mvn test -Dtest=ContextBudgetManagerV2Test
```

Expected: Tests run: 4, Failures: 0

- [ ] **Step 3: 提交**

```bash
git add ai-chat-service/src/test/java/com/lx/governance/aichat/context/ContextBudgetManagerV2Test.java
git commit -m "test: add ContextBudgetManagerV2 unit tests"
```

---

## Task 6: 修改PromptBuilder集成会话上下文

**Files:**
- Modify: `ai-chat-service/src/main/java/com/lx/governance/aichat/prompt/PromptBuilder.java`

- [ ] **Step 1: 添加会话上下文参数**

找到 `buildPrompt` 方法签名，修改为：

```java
public String buildPrompt(
        UserProfileDTO profile,
        SessionContext sessionContext,
        List<String> memories,
        String userMessage) {
    
    StringBuilder prompt = new StringBuilder();
    
    // 1. 系统角色
    prompt.append("你是一位专业的健康顾问，擅长提供个性化的健康建议。\n\n");
    
    // 2. 用户画像
    if (profile != null) {
        prompt.append("## 用户画像\n");
        prompt.append(formatProfile(profile)).append("\n\n");
    }
    
    // 3. 会话上下文（新增）
    if (sessionContext != null) {
        prompt.append("## 会话上下文\n");
        prompt.append(formatSessionContext(sessionContext)).append("\n\n");
    }
    
    // 4. 相关记忆
    if (memories != null && !memories.isEmpty()) {
        prompt.append("## 相关记忆\n");
        for (String memory : memories) {
            prompt.append("- ").append(memory).append("\n");
        }
        prompt.append("\n");
    }
    
    // 5. 用户消息
    prompt.append("## 用户问题\n");
    prompt.append(userMessage).append("\n\n");
    
    // 6. 回复要求
    prompt.append("请基于以上信息，提供专业、个性化的健康建议。\n");
    
    return prompt.toString();
}
```

- [ ] **Step 2: 添加formatSessionContext方法**

在类中添加：

```java
/**
 * 格式化会话上下文
 */
private String formatSessionContext(SessionContext context) {
    StringBuilder sb = new StringBuilder();
    
    sb.append("当前话题：").append(context.getCurrentTopic()).append("\n");
    sb.append("对话阶段：").append(context.getConversationPhase()).append("\n");
    sb.append("用户意图：").append(context.getUserIntent()).append("\n");
    
    if (context.getRecentSummaries() != null && !context.getRecentSummaries().isEmpty()) {
        sb.append("最近对话：\n");
        for (var summary : context.getRecentSummaries()) {
            sb.append("  - ").append(summary.getUserMessage()).append("\n");
        }
    }
    
    if (context.getMentionedEntities() != null && !context.getMentionedEntities().isEmpty()) {
        sb.append("提及实体：").append(String.join("、", context.getMentionedEntities())).append("\n");
    }
    
    return sb.toString();
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
git add ai-chat-service/src/main/java/com/lx/governance/aichat/prompt/PromptBuilder.java
git commit -m "feat: integrate session context into PromptBuilder"
```

---

## Task 7: 修改AiChatService使用新版预算管理器

**Files:**
- Modify: `ai-chat-service/src/main/java/com/lx/governance/aichat/service/AiChatService.java`

- [ ] **Step 1: 替换预算管理器注入**

找到旧的 `ContextBudgetManager` 注入，替换为：

```java
@Autowired
private ContextBudgetManagerV2 budgetManagerV2;
```

- [ ] **Step 2: 修改chat方法使用新预算管理器**

找到 `chat` 方法中的Prompt构建部分，修改为：

```java
// 5. 分配Token预算
BudgetAllocation allocation = budgetManagerV2.allocateBudget(
    userProfile,
    sessionContext,
    relevantMemories,
    userMessage
);

log.info("Token预算分配: total={}, actual={}, needsCompression={}", 
    allocation.getTotalBudget(),
    allocation.getActualUsage(),
    allocation.isNeedsCompression());

// 6. 压缩内容（如果需要）
ContextBudgetManagerV2.CompressedContent compressed = 
    budgetManagerV2.compressContent(userProfile, sessionContext, relevantMemories, allocation);

// 7. 构建Prompt
String prompt = promptBuilder.buildPrompt(
    userProfile,
    sessionContext,
    compressed.getMemories(),
    userMessage
);
```

- [ ] **Step 3: 验证编译**

```bash
cd ai-chat-service
mvn compile
```

Expected: BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
git add ai-chat-service/src/main/java/com/lx/governance/aichat/service/AiChatService.java
git commit -m "feat: integrate ContextBudgetManagerV2 into AiChatService"
```

---

## Task 8: 端到端测试

**Files:**
- Test: 整个ai-chat-service

- [ ] **Step 1: 启动依赖服务**

```bash
# Redis
docker run -d --name redis-test -p 6379:6379 redis:7-alpine

# MySQL（假设已运行）
# RabbitMQ（假设已运行）
```

- [ ] **Step 2: 启动ai-chat-service**

```bash
cd ai-chat-service
mvn spring-boot:run
```

Expected: Started AiChatServiceApplication

- [ ] **Step 3: 测试简单对话（预算充足）**

```bash
curl -X POST http://localhost:8103/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "conversationId": "test-conv-001",
    "customerId": "test-user-001",
    "groupId": "test-group-001",
    "message": "我想咨询血糖问题"
  }'
```

Expected: 
- 返回AI回复
- 日志显示"needsCompression=false"
- 日志显示预算分配详情

- [ ] **Step 4: 测试复杂对话（可能需要压缩）**

```bash
curl -X POST http://localhost:8103/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "conversationId": "test-conv-002",
    "customerId": "test-user-002",
    "groupId": "test-group-002",
    "message": "'"$(cat <<EOF
我最近身体不太舒服，血糖一直偏高，早上空腹血糖在7.5左右，餐后两小时能到10.2。
我想知道这种情况严重吗？需要吃药吗？平时饮食应该注意什么？
我现在每天早上会吃一个馒头、一个鸡蛋、一杯豆浆，中午吃米饭配菜，晚上也是米饭。
我听说要少吃主食，但是不吃主食又会饿，应该怎么办？
另外我还想问问运动方面的建议，我现在每天走路30分钟够吗？
EOF
)"'"
  }'
```

Expected:
- 返回AI回复
- 日志可能显示"needsCompression=true"
- 日志显示压缩详情

- [ ] **Step 5: 验证会话上下文在Prompt中**

查看日志中的Prompt内容，确认包含：
- 用户画像
- 会话上下文（话题、阶段、意图、最近对话）
- 相关记忆
- 用户消息

- [ ] **Step 6: 测试预算分配API（可选）**

创建测试接口：

```java
@RestController
@RequestMapping("/api/debug")
public class DebugController {
    
    @Autowired
    private ContextBudgetManagerV2 budgetManager;
    
    @PostMapping("/budget")
    public BudgetAllocation testBudget(@RequestBody Map<String, Object> request) {
        // 构建测试数据
        UserProfileDTO profile = buildTestProfile();
        SessionContext context = buildTestContext();
        List<String> memories = buildTestMemories();
        String message = (String) request.get("message");
        
        return budgetManager.allocateBudget(profile, context, memories, message);
    }
}
```

测试：

```bash
curl -X POST http://localhost:8103/api/debug/budget \
  -H "Content-Type: application/json" \
  -d '{"message": "测试消息"}'
```

Expected: 返回详细的预算分配信息

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
git commit -m "feat: complete token budget management refactor

- Add ContextBudgetManagerV2 with three-tier budget allocation
- Implement ContentCompressor for smart text compression
- Add BudgetAllocation data structure
- Integrate session context into PromptBuilder
- Update AiChatService to use new budget manager
- Add comprehensive unit tests
- Budget allocation: core profile 600 + session context 800 + memories 1200 + extended 200
- Support dynamic compression when content exceeds budget"
```

---

## 验收标准

- [x] ContextBudgetManagerV2实现三层预算分配
- [x] ContentCompressor支持智能压缩
- [x] 会话上下文集成到Prompt
- [x] 预算超限时自动压缩
- [x] 单元测试覆盖率 > 80%
- [x] 端到端测试验证完整流程
- [x] 日志清晰显示预算分配和压缩情况

---

## 预计工作量

- Task 1-2: 数据结构和压缩器 - 1.5小时
- Task 3-4: 预算管理器实现 - 2.5小时
- Task 5: 单元测试 - 1小时
- Task 6-7: 集成到服务 - 1.5小时
- Task 8: 端到端测试 - 1小时

**总计**: 约7.5小时
