# AI 服务上下文管理策略

## 问题背景

随着用户画像和向量记忆的不断积累，直接将所有上下文注入到第三方 API（如 DeepSeek）会面临以下问题：

1. **上下文窗口限制**：DeepSeek API 有最大 token 限制（如 32K）
2. **成本问题**：更多的 prompt tokens 意味着更高的 API 调用成本
3. **响应质量**：过多无关信息可能干扰 AI 的判断
4. **性能问题**：更大的上下文导致更慢的响应时间

## 当前实现的问题

```java
// 当前实现：直接注入所有内容
private String buildSystemPrompt(UserProfileDTO userProfile, List<MemoryItemDTO> memories) {
    StringBuilder prompt = new StringBuilder();
    
    // 问题 1：完整的用户画像（可能包含几十个字段）
    prompt.append(userProfile.toString());
    
    // 问题 2：只限制了 5 条记忆，但没有 token 预算
    for (int i = 0; i < Math.min(memories.size(), 5); i++) {
        prompt.append(memory.getContent());
    }
    
    return prompt.toString();
}
```

**潜在风险：**
- 用户画像可能包含 50+ 字段，占用 1000-2000 tokens
- 5 条记忆可能占用 500-1000 tokens
- 随着记忆增长，检索结果会越来越多
- 没有考虑对话历史的 token 占用

## 解决方案架构

### 方案 1：上下文预算管理（推荐）

**核心思想：** 设置总 token 预算，按优先级动态分配

```java
public class ContextBudgetManager {
    
    // 总预算配置
    private static final int TOTAL_BUDGET = 4000;  // 总 token 预算
    private static final int USER_MESSAGE_RESERVE = 500;  // 为用户消息预留
    private static final int AI_RESPONSE_RESERVE = 2000;  // 为 AI 响应预留
    
    // 可用于上下文的预算
    private static final int CONTEXT_BUDGET = TOTAL_BUDGET - USER_MESSAGE_RESERVE;
    
    // 各部分预算分配（按优先级）
    private static final int CORE_PROFILE_BUDGET = 800;      // 核心画像（必须）
    private static final int RELEVANT_MEMORIES_BUDGET = 1200; // 相关记忆（重要）
    private static final int CONVERSATION_HISTORY_BUDGET = 600; // 对话历史（可选）
    private static final int EXTENDED_PROFILE_BUDGET = 400;   // 扩展画像（可选）
    
    /**
     * 构建受预算约束的上下文
     */
    public String buildBudgetedContext(
            UserProfileDTO fullProfile,
            List<MemoryItemDTO> memories,
            List<ConversationMessage> history,
            String userQuestion) {
        
        StringBuilder context = new StringBuilder();
        int remainingBudget = CONTEXT_BUDGET;
        
        // 1. 核心画像（优先级最高，必须包含）
        String coreProfile = extractCoreProfile(fullProfile);
        int coreTokens = estimateTokens(coreProfile);
        if (coreTokens <= CORE_PROFILE_BUDGET) {
            context.append("## 用户核心信息\n").append(coreProfile).append("\n\n");
            remainingBudget -= coreTokens;
        } else {
            // 如果核心画像超预算，进行压缩
            coreProfile = compressProfile(coreProfile, CORE_PROFILE_BUDGET);
            context.append("## 用户核心信息\n").append(coreProfile).append("\n\n");
            remainingBudget -= CORE_PROFILE_BUDGET;
        }
        
        // 2. 相关记忆（优先级高，根据相似度排序）
        String relevantMemories = selectRelevantMemories(
            memories, 
            userQuestion, 
            Math.min(RELEVANT_MEMORIES_BUDGET, remainingBudget)
        );
        if (!relevantMemories.isEmpty()) {
            context.append("## 相关历史记忆\n").append(relevantMemories).append("\n\n");
            remainingBudget -= estimateTokens(relevantMemories);
        }
        
        // 3. 对话历史（优先级中，最近的对话）
        if (remainingBudget > 0 && history != null && !history.isEmpty()) {
            String recentHistory = selectRecentHistory(
                history, 
                Math.min(CONVERSATION_HISTORY_BUDGET, remainingBudget)
            );
            if (!recentHistory.isEmpty()) {
                context.append("## 最近对话\n").append(recentHistory).append("\n\n");
                remainingBudget -= estimateTokens(recentHistory);
            }
        }
        
        // 4. 扩展画像（优先级低，如果还有预算）
        if (remainingBudget > 0) {
            String extendedProfile = extractExtendedProfile(
                fullProfile, 
                userQuestion,
                Math.min(EXTENDED_PROFILE_BUDGET, remainingBudget)
            );
            if (!extendedProfile.isEmpty()) {
                context.append("## 补充信息\n").append(extendedProfile).append("\n\n");
            }
        }
        
        return context.toString();
    }
    
    /**
     * 提取核心画像（必须字段）
     */
    private String extractCoreProfile(UserProfileDTO profile) {
        StringBuilder core = new StringBuilder();
        
        // 基本信息（必须）
        if (profile.getName() != null) {
            core.append("姓名：").append(profile.getName()).append("\n");
        }
        if (profile.getAge() != null) {
            core.append("年龄：").append(profile.getAge()).append("岁\n");
        }
        if (profile.getGender() != null) {
            core.append("性别：").append(profile.getGender()).append("\n");
        }
        
        // 关键健康指标（必须）
        Map<String, Object> healthData = profile.getHealthData();
        if (healthData != null) {
            if (healthData.containsKey("bloodPressure")) {
                core.append("血压：").append(healthData.get("bloodPressure")).append("\n");
            }
            if (healthData.containsKey("bmi")) {
                core.append("BMI：").append(healthData.get("bmi")).append("\n");
            }
            if (healthData.containsKey("bloodSugar")) {
                core.append("血糖：").append(healthData.get("bloodSugar")).append("\n");
            }
        }
        
        // 关键过敏信息（必须）
        if (profile.getAllergies() != null && !profile.getAllergies().isEmpty()) {
            core.append("过敏：").append(String.join("、", profile.getAllergies())).append("\n");
        }
        
        // 主要目标（必须）
        if (profile.getGoals() != null && !profile.getGoals().isEmpty()) {
            core.append("健康目标：").append(String.join("、", profile.getGoals())).append("\n");
        }
        
        return core.toString();
    }
    
    /**
     * 提取扩展画像（根据问题类型动态选择）
     */
    private String extractExtendedProfile(
            UserProfileDTO profile, 
            String userQuestion, 
            int budget) {
        
        StringBuilder extended = new StringBuilder();
        int usedTokens = 0;
        
        // 根据问题类型选择相关字段
        QuestionType type = classifyQuestion(userQuestion);
        
        switch (type) {
            case DIET:
                // 饮食相关问题：注入饮食偏好、饮食限制
                if (profile.getDietaryPreferences() != null) {
                    String dietInfo = "饮食偏好：" + profile.getDietaryPreferences() + "\n";
                    int tokens = estimateTokens(dietInfo);
                    if (usedTokens + tokens <= budget) {
                        extended.append(dietInfo);
                        usedTokens += tokens;
                    }
                }
                break;
                
            case EXERCISE:
                // 运动相关问题：注入运动习惯、体能水平
                if (profile.getExerciseHabits() != null) {
                    String exerciseInfo = "运动习惯：" + profile.getExerciseHabits() + "\n";
                    int tokens = estimateTokens(exerciseInfo);
                    if (usedTokens + tokens <= budget) {
                        extended.append(exerciseInfo);
                        usedTokens += tokens;
                    }
                }
                break;
                
            case MEDICATION:
                // 用药相关问题：注入用药历史、慢性病
                if (profile.getMedications() != null) {
                    String medInfo = "当前用药：" + profile.getMedications() + "\n";
                    int tokens = estimateTokens(medInfo);
                    if (usedTokens + tokens <= budget) {
                        extended.append(medInfo);
                        usedTokens += tokens;
                    }
                }
                break;
                
            case GENERAL:
            default:
                // 通用问题：按优先级添加字段
                // 可以添加一些通用的补充信息
                break;
        }
        
        return extended.toString();
    }
    
    /**
     * 选择相关记忆（基于相似度和预算）
     */
    private String selectRelevantMemories(
            List<MemoryItemDTO> memories, 
            String userQuestion,
            int budget) {
        
        if (memories == null || memories.isEmpty()) {
            return "";
        }
        
        StringBuilder selected = new StringBuilder();
        int usedTokens = 0;
        
        // 记忆已经按相似度排序（从向量检索返回）
        // 按优先级选择，直到达到预算
        for (MemoryItemDTO memory : memories) {
            // 跳过低相似度的记忆
            if (memory.getSimilarity() < 0.7) {
                continue;
            }
            
            String memoryText = String.format("- %s (相关度: %.0f%%)\n", 
                memory.getContent(), 
                memory.getSimilarity() * 100);
            
            int tokens = estimateTokens(memoryText);
            
            if (usedTokens + tokens <= budget) {
                selected.append(memoryText);
                usedTokens += tokens;
            } else {
                // 预算用完，停止添加
                break;
            }
        }
        
        return selected.toString();
    }
    
    /**
     * 选择最近对话历史
     */
    private String selectRecentHistory(
            List<ConversationMessage> history, 
            int budget) {
        
        StringBuilder selected = new StringBuilder();
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
                selected.insert(0, msgText);
                usedTokens += tokens;
            } else {
                break;
            }
        }
        
        return selected.toString();
    }
    
    /**
     * 估算文本的 token 数量
     * 简单估算：中文 1 字符 ≈ 1.5 tokens，英文 1 单词 ≈ 1.3 tokens
     */
    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        
        // 统计中文字符
        int chineseChars = 0;
        int otherChars = 0;
        
        for (char c : text.toCharArray()) {
            if (c >= 0x4E00 && c <= 0x9FA5) {
                chineseChars++;
            } else {
                otherChars++;
            }
        }
        
        // 估算：中文 1.5 tokens/字符，其他 0.25 tokens/字符
        return (int) (chineseChars * 1.5 + otherChars * 0.25);
    }
    
    /**
     * 压缩画像（当超出预算时）
     */
    private String compressProfile(String profile, int targetBudget) {
        // 简单截断策略
        int estimatedTokens = estimateTokens(profile);
        if (estimatedTokens <= targetBudget) {
            return profile;
        }
        
        // 按比例截断
        double ratio = (double) targetBudget / estimatedTokens;
        int targetLength = (int) (profile.length() * ratio);
        
        return profile.substring(0, Math.min(targetLength, profile.length())) + "...";
    }
    
    /**
     * 问题分类
     */
    private QuestionType classifyQuestion(String question) {
        if (question.contains("饮食") || question.contains("吃") || question.contains("食物")) {
            return QuestionType.DIET;
        } else if (question.contains("运动") || question.contains("锻炼") || question.contains("健身")) {
            return QuestionType.EXERCISE;
        } else if (question.contains("药") || question.contains("治疗")) {
            return QuestionType.MEDICATION;
        }
        return QuestionType.GENERAL;
    }
    
    enum QuestionType {
        DIET,       // 饮食
        EXERCISE,   // 运动
        MEDICATION, // 用药
        GENERAL     // 通用
    }
}
```

### 方案 2：记忆分层管理

**核心思想：** 将记忆分为短期、中期、长期，按层级检索

```java
public class HierarchicalMemoryManager {
    
    /**
     * 分层检索记忆
     */
    public List<MemoryItemDTO> retrieveHierarchicalMemories(
            String customerId, 
            String groupId,
            String userQuestion,
            int maxMemories) {
        
        List<MemoryItemDTO> result = new ArrayList<>();
        
        // 1. 短期记忆（最近 3 天，直接从 MySQL 查询）
        List<MemoryItemDTO> shortTerm = getShortTermMemories(customerId, groupId, 3);
        result.addAll(shortTerm);
        
        // 2. 中期记忆（最近 30 天，向量检索）
        if (result.size() < maxMemories) {
            List<MemoryItemDTO> midTerm = vectorSearch(
                customerId, 
                groupId, 
                userQuestion, 
                maxMemories - result.size(),
                30  // 最近 30 天
            );
            result.addAll(midTerm);
        }
        
        // 3. 长期记忆（30 天以前，仅在特定情况下检索）
        if (result.size() < maxMemories && needsLongTermMemory(userQuestion)) {
            List<MemoryItemDTO> longTerm = vectorSearch(
                customerId, 
                groupId, 
                userQuestion, 
                maxMemories - result.size(),
                null  // 不限时间
            );
            result.addAll(longTerm);
        }
        
        // 按相似度排序
        result.sort((a, b) -> Double.compare(b.getSimilarity(), a.getSimilarity()));
        
        return result;
    }
    
    /**
     * 判断是否需要长期记忆
     */
    private boolean needsLongTermMemory(String question) {
        // 某些问题需要查看历史趋势
        return question.contains("以前") || 
               question.contains("历史") || 
               question.contains("变化") ||
               question.contains("趋势");
    }
}
```

### 方案 3：智能记忆摘要

**核心思想：** 对大量记忆进行摘要，减少 token 占用

```java
public class MemorySummarizer {
    
    /**
     * 摘要记忆（当记忆过多时）
     */
    public String summarizeMemories(List<MemoryItemDTO> memories) {
        if (memories.size() <= 5) {
            // 记忆较少，直接返回
            return formatMemories(memories);
        }
        
        // 按类别分组
        Map<String, List<MemoryItemDTO>> grouped = memories.stream()
            .collect(Collectors.groupingBy(m -> m.getCategory() != null ? m.getCategory() : "其他"));
        
        StringBuilder summary = new StringBuilder();
        
        // 每个类别生成摘要
        for (Map.Entry<String, List<MemoryItemDTO>> entry : grouped.entrySet()) {
            String category = entry.getKey();
            List<MemoryItemDTO> items = entry.getValue();
            
            summary.append(String.format("【%s】", getCategoryName(category)));
            
            if (items.size() <= 3) {
                // 少量记忆，直接列出
                for (MemoryItemDTO item : items) {
                    summary.append(item.getContent()).append("；");
                }
            } else {
                // 大量记忆，提取关键点
                summary.append(extractKeyPoints(items));
            }
            
            summary.append("\n");
        }
        
        return summary.toString();
    }
    
    /**
     * 提取关键点
     */
    private String extractKeyPoints(List<MemoryItemDTO> memories) {
        // 简单实现：取前3条高相似度的记忆
        return memories.stream()
            .sorted((a, b) -> Double.compare(b.getSimilarity(), a.getSimilarity()))
            .limit(3)
            .map(MemoryItemDTO::getContent)
            .collect(Collectors.joining("；"));
    }
}
```

### 方案 4：动态上下文窗口

**核心思想：** 根据 API 返回的 token 使用情况动态调整

```java
public class DynamicContextManager {
    
    private int lastPromptTokens = 0;
    private int currentBudget = 4000;
    
    /**
     * 根据上次调用的 token 使用情况调整预算
     */
    public void adjustBudget(int actualPromptTokens, int actualTotalTokens) {
        this.lastPromptTokens = actualPromptTokens;
        
        // 如果接近上限，减少预算
        if (actualTotalTokens > 28000) {  // DeepSeek 32K 上下文
            currentBudget = Math.max(2000, currentBudget - 500);
            log.warn("接近上下文上限，减少预算到: {}", currentBudget);
        } else if (actualPromptTokens < currentBudget * 0.7) {
            // 如果使用率低，可以适当增加预算
            currentBudget = Math.min(6000, currentBudget + 200);
        }
    }
    
    public int getCurrentBudget() {
        return currentBudget;
    }
}
```

## 实施建议

### 阶段 1：立即实施（优先级高）

1. **实现上下文预算管理**
   - 设置总 token 预算（4000 tokens）
   - 实现 `ContextBudgetManager`
   - 修改 `DeepSeekClient.buildSystemPrompt()` 使用预算管理

2. **优化记忆检索**
   - 限制检索数量（top 10）
   - 添加相似度阈值（>= 0.7）
   - 实现记忆去重（避免重复内容）

3. **添加 Token 监控**
   - 记录每次调用的 prompt_tokens
   - 添加告警（超过 6000 tokens）
   - 在日志中输出 token 使用情况

### 阶段 2：中期优化（1-2周）

1. **实现问题分类**
   - 使用关键词匹配或简单分类模型
   - 根据问题类型动态选择画像字段

2. **实现记忆分层**
   - 短期记忆（3天）：直接查询
   - 中期记忆（30天）：向量检索
   - 长期记忆（>30天）：按需检索

3. **添加对话历史管理**
   - 存储最近 N 轮对话
   - 实现滑动窗口（保留最近 5 轮）

### 阶段 3：长期优化（1-2月）

1. **实现智能摘要**
   - 对大量记忆进行分类摘要
   - 使用 LLM 生成记忆摘要

2. **实现动态预算调整**
   - 根据实际 token 使用情况自动调整
   - 实现自适应上下文管理

3. **优化向量检索**
   - 添加重排序（Reranking）
   - 实现多轮检索策略

## 配置示例

```yaml
# application.yml
ai:
  context:
    # 总预算配置
    total-budget: 4000
    user-message-reserve: 500
    
    # 各部分预算分配
    core-profile-budget: 800
    relevant-memories-budget: 1200
    conversation-history-budget: 600
    extended-profile-budget: 400
    
    # 记忆检索配置
    memory:
      max-count: 10
      similarity-threshold: 0.7
      short-term-days: 3
      mid-term-days: 30
    
    # 告警阈值
    alert:
      prompt-tokens-threshold: 6000
      total-tokens-threshold: 28000
```

## 监控指标

建议添加以下监控指标：

1. **Token 使用情况**
   - 平均 prompt_tokens
   - 最大 prompt_tokens
   - token 超限次数

2. **上下文组成**
   - 核心画像 tokens
   - 记忆 tokens
   - 对话历史 tokens

3. **性能指标**
   - 上下文构建耗时
   - API 响应时间
   - 成本统计

## 总结

**推荐方案组合：**

1. ✅ **上下文预算管理**（方案 1）- 核心方案，立即实施
2. ✅ **记忆分层管理**（方案 2）- 优化检索效率
3. ⏳ **智能摘要**（方案 3）- 中期优化
4. ⏳ **动态调整**（方案 4）- 长期优化

**预期效果：**
- 将 prompt tokens 控制在 4000 以内
- 保持响应质量（只注入相关上下文）
- 降低 API 调用成本（减少 30-50% tokens）
- 提升响应速度（更少的 tokens）
