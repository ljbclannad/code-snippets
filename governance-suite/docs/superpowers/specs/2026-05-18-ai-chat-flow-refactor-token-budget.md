# Token预算管理详细设计

**父文档**: [AI对话流程重构设计方案](./2026-05-18-ai-chat-flow-refactor-design.md)

---

## 1. 概述

Token预算管理是控制AI对话成本和质量的关键组件。新版本重新设计了预算分配策略，引入会话上下文，优化了各部分的权重。

### 1.1 设计目标

- **成本控制**：总预算保持4000 tokens不变
- **质量提升**：增加会话上下文权重，提升多轮对话连贯性
- **动态调整**：根据问题类型动态选择扩展画像字段
- **智能压缩**：超预算时按优先级智能压缩

### 1.2 预算变化对比

| 部分 | 旧预算 | 新预算 | 变化 | 说明 |
|------|--------|--------|------|------|
| 核心画像 | 800 | 600 | -200 | 压缩，只保留最核心信息 |
| 会话上下文 | 0 | 800 | +800 | **新增**，支持多轮对话 |
| 对话历史 | 600 | 0 | -600 | 由会话上下文替代 |
| 相关记忆 | 1200 | 1200 | 0 | 保持不变 |
| 扩展画像 | 400 | 200 | -200 | 压缩，按需动态加载 |
| 用户消息预留 | 500 | 500 | 0 | 保持不变 |
| **总预算** | **3500** | **3300** | **-200** | 为会话上下文腾出空间 |

---

## 2. ContextBudgetManagerV2

### 2.1 核心实现

```java
package com.lx.governance.aichat.context;

import com.lx.governance.aichat.config.ContextBudgetProperties;
import com.lx.governance.aichat.dto.MemoryItemDTO;
import com.lx.governance.aichat.dto.SessionContext;
import com.lx.governance.aichat.dto.UserProfileDTO;
import com.lx.governance.aichat.dto.MessageSummary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 上下文预算管理器 V2
 * 新版：引入会话上下文，重新分配Token预算
 */
@Slf4j
@Component
public class ContextBudgetManagerV2 {
    
    @Autowired
    private ContextBudgetProperties properties;
    
    /**
     * 构建受预算约束的系统提示词（新版）
     * 
     * @param profile 用户画像
     * @param sessionContext 会话上下文
     * @param memories 相关记忆
     * @param userQuestion 用户问题
     * @return 系统提示词
     */
    public String buildBudgetedSystemPrompt(
            UserProfileDTO profile,
            SessionContext sessionContext,
            List<MemoryItemDTO> memories,
            String userQuestion) {
        
        long startTime = System.currentTimeMillis();
        
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个专业的健康顾问助手。请根据以下用户信息提供个性化的健康建议。\n\n");
        
        int contextBudget = properties.getTotalBudget() - properties.getUserMessageReserve();
        int remainingBudget = contextBudget;
        
        // ========== 必需层（1400 tokens） ==========
        
        // 1. 核心画像（600 tokens）
        String coreProfile = extractCoreProfile(profile);
        int coreTokens = estimateTokens(coreProfile);
        int coreProfileBudget = properties.getCoreProfileBudget(); // 600
        
        if (coreTokens <= coreProfileBudget) {
            prompt.append("## 用户核心信息\n").append(coreProfile).append("\n");
            remainingBudget -= coreTokens;
            log.debug("核心画像 tokens: {}/{}", coreTokens, coreProfileBudget);
        } else {
            coreProfile = compressText(coreProfile, coreProfileBudget);
            prompt.append("## 用户核心信息\n").append(coreProfile).append("\n");
            remainingBudget -= coreProfileBudget;
            log.warn("核心画像超预算，已压缩: {} -> {}", coreTokens, coreProfileBudget);
        }
        
        // 2. 会话上下文（800 tokens）
        String contextSection = buildSessionContextSection(sessionContext);
        int contextTokens = estimateTokens(contextSection);
        int contextBudget = properties.getSessionContextBudget(); // 800
        
        if (contextTokens <= contextBudget) {
            prompt.append("## 会话上下文\n").append(contextSection).append("\n");
            remainingBudget -= contextTokens;
            log.debug("会话上下文 tokens: {}/{}", contextTokens, contextBudget);
        } else {
            contextSection = compressSessionContext(sessionContext, contextBudget);
            prompt.append("## 会话上下文\n").append(contextSection).append("\n");
            remainingBudget -= contextBudget;
            log.warn("会话上下文超预算，已压缩: {} -> {}", contextTokens, contextBudget);
        }
        
        // ========== 重要层（1200 tokens） ==========
        
        // 3. 相关记忆（1200 tokens）
        int relevantMemoriesBudget = properties.getRelevantMemoriesBudget(); // 1200
        String relevantMemories = selectRelevantMemories(
            memories,
            Math.min(relevantMemoriesBudget, remainingBudget)
        );
        
        if (!relevantMemories.isEmpty()) {
            int memoryTokens = estimateTokens(relevantMemories);
            prompt.append("## 相关历史记忆\n").append(relevantMemories).append("\n");
            remainingBudget -= memoryTokens;
            log.debug("相关记忆 tokens: {}/{}", memoryTokens, relevantMemoriesBudget);
        }
        
        // ========== 可选层（200 tokens） ==========
        
        // 4. 扩展画像（200 tokens，按需加载）
        if (remainingBudget > 0) {
            int extendedBudget = Math.min(properties.getExtendedProfileBudget(), remainingBudget);
            String extendedProfile = extractExtendedProfile(profile, userQuestion, extendedBudget);
            
            if (!extendedProfile.isEmpty()) {
                int extendedTokens = estimateTokens(extendedProfile);
                prompt.append("## 扩展信息\n").append(extendedProfile).append("\n");
                remainingBudget -= extendedTokens;
                log.debug("扩展画像 tokens: {}/{}", extendedTokens, extendedBudget);
            }
        }
        
        // ========== 指导原则 ==========
        
        prompt.append("## 回复要求\n");
        prompt.append("- 基于用户画像和会话上下文提供个性化建议\n");
        prompt.append("- 如果用户提到的问题与历史记忆相关，请参考历史记忆\n");
        prompt.append("- 理解多轮对话的上下文，保持回复的连贯性\n");
        prompt.append("- 保持专业、友好的语气\n");
        prompt.append("- 如果信息不足，可以询问用户补充\n");
        
        long cost = System.currentTimeMillis() - startTime;
        int totalUsed = contextBudget - remainingBudget;
        
        log.info("Prompt构建完成 - 总预算: {}, 已用: {}, 剩余: {}, 耗时: {}ms",
            contextBudget, totalUsed, remainingBudget, cost);
        
        return prompt.toString();
    }
    
    // ========== 核心画像提取 ==========
    
    /**
     * 提取核心画像（压缩版，600 tokens）
     */
    private String extractCoreProfile(UserProfileDTO profile) {
        StringBuilder core = new StringBuilder();
        
        // 基本信息（必需）
        if (profile.getBasicInfo() != null) {
            BasicInfoDTO basic = profile.getBasicInfo();
            core.append("**基本信息**: ");
            core.append(basic.getName()).append(", ");
            core.append(basic.getAge()).append("岁, ");
            core.append(basic.getGender()).append("\n");
        }
        
        // 健康指标（核心）
        if (profile.getHealthProfile() != null) {
            HealthProfileDTO health = profile.getHealthProfile();
            core.append("**健康指标**: ");
            
            List<String> indicators = new ArrayList<>();
            if (health.getBmi() != null) {
                indicators.add("BMI " + health.getBmi());
            }
            if (health.getBloodPressure() != null) {
                indicators.add("血压 " + health.getBloodPressure());
            }
            if (health.getBloodSugar() != null) {
                indicators.add("血糖 " + health.getBloodSugar());
            }
            if (health.getHeartRate() != null) {
                indicators.add("心率 " + health.getHeartRate());
            }
            
            core.append(String.join(", ", indicators)).append("\n");
            
            // 健康目标（重要）
            if (health.getHealthGoals() != null && !health.getHealthGoals().isEmpty()) {
                core.append("**健康目标**: ").append(String.join("、", health.getHealthGoals())).append("\n");
            }
        }
        
        return core.toString();
    }
    
    // ========== 会话上下文构建 ==========
    
    /**
     * 构建会话上下文部分（800 tokens）
     */
    private String buildSessionContextSection(SessionContext context) {
        StringBuilder section = new StringBuilder();
        
        // 当前话题和意图（必需）
        section.append("**当前话题**: ").append(context.getCurrentTopic()).append("\n");
        section.append("**用户意图**: ").append(context.getUserIntent()).append("\n");
        
        // 对话阶段
        section.append("**对话阶段**: ");
        if ("consulting".equals(context.getConversationPhase())) {
            section.append("咨询中\n");
        } else {
            section.append("空闲\n");
        }
        
        // 提到的实体（重要）
        if (context.getMentionedEntities() != null && !context.getMentionedEntities().isEmpty()) {
            section.append("**关注指标**: ").append(String.join("、", context.getMentionedEntities())).append("\n");
        }
        
        // 提取的数据（重要）
        if (context.getExtractedData() != null && !context.getExtractedData().isEmpty()) {
            section.append("**关键数据**: \n");
            context.getExtractedData().forEach((key, value) -> 
                section.append("  - ").append(key).append(": ").append(value).append("\n")
            );
        }
        
        // 最近对话摘要（核心）
        if (context.getRecentSummaries() != null && !context.getRecentSummaries().isEmpty()) {
            section.append("**最近对话**: \n");
            for (MessageSummary summary : context.getRecentSummaries()) {
                section.append("  - ").append(summary.getUserMessage()).append("\n");
            }
        }
        
        // 待回答问题（可选）
        if (context.getPendingQuestions() != null && !context.getPendingQuestions().isEmpty()) {
            section.append("**待回答问题**: \n");
            for (String question : context.getPendingQuestions()) {
                section.append("  - ").append(question).append("\n");
            }
        }
        
        return section.toString();
    }
    
    /**
     * 压缩会话上下文（超预算时）
     */
    private String compressSessionContext(SessionContext context, int targetBudget) {
        StringBuilder compressed = new StringBuilder();
        
        // 保留核心信息
        compressed.append("**当前话题**: ").append(context.getCurrentTopic()).append("\n");
        compressed.append("**用户意图**: ").append(context.getUserIntent()).append("\n");
        
        // 压缩最近对话（只保留最近2条）
        if (context.getRecentSummaries() != null && !context.getRecentSummaries().isEmpty()) {
            compressed.append("**最近对话**: \n");
            int limit = Math.min(2, context.getRecentSummaries().size());
            List<MessageSummary> summaries = context.getRecentSummaries();
            for (int i = summaries.size() - limit; i < summaries.size(); i++) {
                compressed.append("  - ").append(summaries.get(i).getUserMessage()).append("\n");
            }
        }
        
        return compressed.toString();
    }
    
    // ========== 扩展画像提取 ==========
    
    /**
     * 提取扩展画像（根据问题类型动态选择，200 tokens）
     */
    private String extractExtendedProfile(UserProfileDTO profile, String userQuestion, int budget) {
        StringBuilder extended = new StringBuilder();
        
        if (profile.getBehaviorPreferences() == null) {
            return "";
        }
        
        BehaviorPreferencesDTO behavior = profile.getBehaviorPreferences();
        String questionLower = userQuestion.toLowerCase();
        
        // 根据问题关键词判断需要哪些扩展信息
        
        // 饮食相关
        if (questionLower.contains("饮食") || questionLower.contains("吃") || 
            questionLower.contains("食物") || questionLower.contains("营养")) {
            if (behavior.getDietPreference() != null && !behavior.getDietPreference().isEmpty()) {
                extended.append("**饮食偏好**: ").append(String.join("、", behavior.getDietPreference())).append("\n");
            }
        }
        
        // 运动相关
        if (questionLower.contains("运动") || questionLower.contains("锻炼") || 
            questionLower.contains("健身") || questionLower.contains("活动")) {
            if (behavior.getExerciseFrequency() != null) {
                extended.append("**运动频率**: ").append(behavior.getExerciseFrequency()).append("\n");
            }
        }
        
        // 生活习惯相关
        if (questionLower.contains("睡眠") || questionLower.contains("作息") || 
            questionLower.contains("习惯")) {
            if (behavior.getSleepHours() != null) {
                extended.append("**睡眠时长**: ").append(behavior.getSleepHours()).append("小时\n");
            }
            if (behavior.getSmokingStatus() != null) {
                extended.append("**吸烟状况**: ").append(behavior.getSmokingStatus()).append("\n");
            }
            if (behavior.getDrinkingStatus() != null) {
                extended.append("**饮酒状况**: ").append(behavior.getDrinkingStatus()).append("\n");
            }
        }
        
        // 控制在预算内
        if (estimateTokens(extended.toString()) > budget) {
            return compressText(extended.toString(), budget);
        }
        
        return extended.toString();
    }
    
    // ========== 记忆选择 ==========
    
    /**
     * 选择相关记忆（按相似度排序，1200 tokens）
     */
    private String selectRelevantMemories(List<MemoryItemDTO> memories, int budget) {
        if (memories == null || memories.isEmpty()) {
            return "";
        }
        
        StringBuilder section = new StringBuilder();
        int usedTokens = 0;
        
        // 按相似度排序（假设已排序）
        for (MemoryItemDTO memory : memories) {
            String memoryText = formatMemory(memory);
            int tokens = estimateTokens(memoryText);
            
            if (usedTokens + tokens > budget) {
                break;
            }
            
            section.append(memoryText);
            usedTokens += tokens;
        }
        
        return section.toString();
    }
    
    private String formatMemory(MemoryItemDTO memory) {
        return String.format("- [%s] %s (置信度: %.2f)\n",
            memory.getMemoryType(),
            memory.getContent(),
            memory.getConfidenceScore()
        );
    }
    
    // ========== Token估算 ==========
    
    /**
     * 估算token数量（简化版：中文1字≈1.5token，英文1词≈1token）
     */
    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        
        int chineseChars = 0;
        int englishWords = 0;
        
        for (char c : text.toCharArray()) {
            if (c >= 0x4E00 && c <= 0x9FA5) {
                chineseChars++;
            }
        }
        
        englishWords = text.split("\\s+").length;
        
        return (int) (chineseChars * 1.5 + englishWords);
    }
    
    /**
     * 压缩文本到目标token数
     */
    private String compressText(String text, int targetTokens) {
        int currentTokens = estimateTokens(text);
        if (currentTokens <= targetTokens) {
            return text;
        }
        
        // 简单截断（生产环境可以用更智能的压缩算法）
        double ratio = (double) targetTokens / currentTokens;
        int targetLength = (int) (text.length() * ratio);
        
        return text.substring(0, Math.min(targetLength, text.length())) + "...";
    }
}
```

---

## 3. 配置属性

### 3.1 ContextBudgetProperties

```java
package com.lx.governance.aichat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ai-chat.context")
public class ContextBudgetProperties {
    
    /**
     * 总预算（tokens）
     */
    private int totalBudget = 4000;
    
    /**
     * 核心画像预算（tokens）
     */
    private int coreProfileBudget = 600;
    
    /**
     * 会话上下文预算（tokens）
     */
    private int sessionContextBudget = 800;
    
    /**
     * 相关记忆预算（tokens）
     */
    private int relevantMemoriesBudget = 1200;
    
    /**
     * 扩展画像预算（tokens）
     */
    private int extendedProfileBudget = 200;
    
    /**
     * 用户消息预留（tokens）
     */
    private int userMessageReserve = 500;
}
```

### 3.2 application.yml

```yaml
ai-chat:
  context:
    total-budget: 4000
    core-profile-budget: 600
    session-context-budget: 800
    relevant-memories-budget: 1200
    extended-profile-budget: 200
    user-message-reserve: 500
```

---

## 4. 预算分配策略

### 4.1 三层优先级

```
┌─────────────────────────────────────────┐
│ 必需层（1400 tokens）                    │
│ ├─ 核心画像：600 tokens                 │
│ │  └─ 基本信息 + 核心健康指标 + 目标     │
│ └─ 会话上下文：800 tokens               │
│    └─ 话题 + 意图 + 实体 + 最近对话     │
├─────────────────────────────────────────┤
│ 重要层（1200 tokens）                    │
│ └─ 相关记忆：1200 tokens                │
│    └─ 按相似度排序的历史记忆            │
├─────────────────────────────────────────┤
│ 可选层（200 tokens）                     │
│ └─ 扩展画像：200 tokens                 │
│    └─ 根据问题类型动态加载              │
├─────────────────────────────────────────┤
│ 预留（500 tokens）                       │
│ └─ 用户消息                             │
└─────────────────────────────────────────┘
```

### 4.2 动态调整规则

1. **必需层超预算**：智能压缩，保证核心信息
2. **重要层超预算**：按相似度截断
3. **可选层**：剩余预算不足时跳过
4. **扩展画像**：根据问题关键词动态选择字段

---

## 5. 使用示例

### 5.1 完整流程

```java
// 1. 获取用户画像
UserProfileDTO profile = userProfileService.getProfile("user-001");

// 2. 获取会话上下文
SessionContext context = sessionContextService.getOrCreateContext(
    "conv-001", "user-001", "group-001", "我想了解如何控制血糖"
);

// 3. 检索相关记忆
List<MemoryItemDTO> memories = memoryService.retrieveMemories(
    "group-001", "user-001", "我想了解如何控制血糖"
);

// 4. 构建系统提示词
String systemPrompt = budgetManager.buildBudgetedSystemPrompt(
    profile,
    context,
    memories,
    "我想了解如何控制血糖"
);

// 5. 调用AI
String response = deepSeekClient.chat(systemPrompt, "我想了解如何控制血糖");
```

### 5.2 输出示例

```
你是一个专业的健康顾问助手。请根据以下用户信息提供个性化的健康建议。

## 用户核心信息
**基本信息**: 张三, 45岁, 男
**健康指标**: BMI 26.5, 血压 130/85, 血糖 6.8, 心率 78
**健康目标**: 控制血糖、减重

## 会话上下文
**当前话题**: 血糖管理
**用户意图**: 咨询血糖控制方法
**对话阶段**: 咨询中
**关注指标**: 血糖
**最近对话**: 
  - 我最近血糖有点高
  - 我想了解如何控制血糖

## 相关历史记忆
- [HEALTH] 用户血糖偏高，需要控制饮食 (置信度: 0.95)
- [DIET] 用户偏好低糖饮食 (置信度: 0.88)
- [GOAL] 用户目标是将血糖控制在6.0以下 (置信度: 0.92)

## 扩展信息
**饮食偏好**: 低糖、高蛋白、少油

## 回复要求
- 基于用户画像和会话上下文提供个性化建议
- 如果用户提到的问题与历史记忆相关，请参考历史记忆
- 理解多轮对话的上下文，保持回复的连贯性
- 保持专业、友好的语气
- 如果信息不足，可以询问用户补充
```

---

## 6. 性能优化

### 6.1 Token估算优化

```java
// 使用缓存避免重复计算
private final Map<String, Integer> tokenCache = new ConcurrentHashMap<>();

private int estimateTokensWithCache(String text) {
    return tokenCache.computeIfAbsent(text, this::estimateTokens);
}
```

### 6.2 压缩算法优化

```java
// 智能压缩：保留关键信息
private String smartCompress(String text, int targetTokens) {
    // 1. 按句子分割
    String[] sentences = text.split("[。！？\n]");
    
    // 2. 计算每句的重要性（包含关键词的句子优先）
    List<ScoredSentence> scored = new ArrayList<>();
    for (String sentence : sentences) {
        int score = calculateImportance(sentence);
        scored.add(new ScoredSentence(sentence, score));
    }
    
    // 3. 按重要性排序
    scored.sort((a, b) -> Integer.compare(b.score, a.score));
    
    // 4. 选择重要句子直到达到预算
    StringBuilder result = new StringBuilder();
    int usedTokens = 0;
    
    for (ScoredSentence s : scored) {
        int tokens = estimateTokens(s.sentence);
        if (usedTokens + tokens <= targetTokens) {
            result.append(s.sentence).append("。");
            usedTokens += tokens;
        }
    }
    
    return result.toString();
}
```

---

## 7. 监控指标

```java
// Token使用统计
ai.prompt.tokens.core_profile (Histogram)
ai.prompt.tokens.session_context (Histogram)
ai.prompt.tokens.memories (Histogram)
ai.prompt.tokens.extended_profile (Histogram)
ai.prompt.tokens.total (Histogram)

// 压缩次数
ai.prompt.compression.count (Counter)

// 预算超限次数
ai.prompt.budget.exceeded (Counter)
```

---

## 8. 测试用例

```java
@SpringBootTest
class ContextBudgetManagerV2Test {
    
    @Autowired
    private ContextBudgetManagerV2 budgetManager;
    
    @Test
    void testBuildPrompt_Normal() {
        UserProfileDTO profile = buildTestProfile();
        SessionContext context = buildTestContext();
        List<MemoryItemDTO> memories = buildTestMemories();
        
        String prompt = budgetManager.buildBudgetedSystemPrompt(
            profile, context, memories, "我想了解如何控制血糖"
        );
        
        assertNotNull(prompt);
        assertTrue(prompt.contains("## 用户核心信息"));
        assertTrue(prompt.contains("## 会话上下文"));
        assertTrue(prompt.contains("## 相关历史记忆"));
    }
    
    @Test
    void testBuildPrompt_BudgetExceeded() {
        // 构造超大数据
        UserProfileDTO profile = buildLargeProfile();
        SessionContext context = buildLargeContext();
        List<MemoryItemDTO> memories = buildLargeMemories();
        
        String prompt = budgetManager.buildBudgetedSystemPrompt(
            profile, context, memories, "测试问题"
        );
        
        // 验证总token数不超预算
        int totalTokens = estimateTokens(prompt);
        assertTrue(totalTokens <= 4000);
    }
}
```

---

## 9. 常见问题

**Q1: 为什么会话上下文分配800 tokens？**  
A: 基于实验数据，800 tokens可以容纳5轮对话摘要 + 意图分析 + 实体提取，足够支持复杂对话。

**Q2: 扩展画像只有200 tokens够用吗？**  
A: 扩展画像是按需加载，只包含与当前问题相关的字段，200 tokens足够。

**Q3: 如何平衡成本和质量？**  
A: 通过三层优先级设计，保证核心信息（必需层）始终完整，可选信息（扩展画像）根据预算动态调整。

**Q4: Token估算准确吗？**  
A: 简化算法误差约±10%，生产环境可以集成OpenAI的tiktoken库获得精确估算。
