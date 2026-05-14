# AI 服务上下文管理优化设计

## 1. 设计目标

### 核心目标
确保发送给 DeepSeek API 的 prompt tokens 不超过预算上限（4000 tokens），避免：
- 超出 API 的上下文窗口限制（32K）
- 产生过高的调用成本
- 影响响应速度和质量

### 优化范围
按照文档 `docs/context-management-strategy.md` 的建议，实施阶段 1 和阶段 2 的优化：

**阶段 1（立即实施）：**
- 配置化预算管理
- 优化记忆检索（top 10、相似度阈值、去重）
- 添加 Token 监控和告警

**阶段 2（中期优化）：**
- 实现记忆分层管理（短期/中期/长期）
- 添加对话历史管理（最近 N 轮对话）
- 集成到统一的预算分配流程

## 2. 架构设计

### 2.1 整体架构

```
用户请求
  ↓
AiChatService
  ↓
并行获取上下文：
  ├─ UserProfile (用户画像)
  ├─ Memories (向量记忆 - 分层检索)
  └─ ConversationHistory (对话历史)
  ↓
ContextBudgetManager.buildBudgetedSystemPrompt()
  ├─ 1. 核心画像 (800 tokens, 必须)
  ├─ 2. 相关记忆 (1200 tokens, 按相似度)
  ├─ 3. 对话历史 (600 tokens, 最近 5 轮)
  └─ 4. 扩展画像 (400 tokens, 按问题类型)
  ↓
DeepSeekClient.chat()
  ↓
TokenMonitor.recordTokenUsage()
  ↓
返回响应
```

### 2.2 核心组件

#### ContextBudgetManager（增强版）
**职责：** 统一管理所有上下文的预算分配

**现有功能：**
- 提取核心画像（必须字段）
- 提取扩展画像（根据问题类型）
- 选择相关记忆（基于相似度和预算）
- Token 估算和文本压缩

**新增功能：**
- 集成对话历史管理
- 支持配置化预算（从 application.yml 读取）
- 添加 token 使用监控和日志

#### ConversationHistoryManager（新增）
**职责：** 从数据库查询和管理对话历史

**核心方法：**
```java
// 查询最近 N 轮对话
List<ConversationMessage> getRecentHistory(
    String customerId, 
    String conversationId, 
    int maxRounds
)

// 格式化对话历史为文本（受预算约束）
String formatHistory(List<ConversationMessage> history, int budget)
```

**数据来源：** `b_ai_chat_history` 表
- 按 `conversation_id` 和 `create_time DESC` 查询
- 限制最近 N 轮（默认 5 轮）
- 格式：`用户: xxx\n助手: xxx\n`

#### HierarchicalMemoryManager（新增）
**职责：** 实现记忆分层检索策略

**分层策略：**
1. **短期记忆**（最近 3 天）
   - 优先级最高
   - 直接从数据库查询（不走向量检索）
   - 包含最近的对话记忆

2. **中期记忆**（最近 30 天）
   - 优先级中等
   - 使用向量检索
   - 按相似度排序

3. **长期记忆**（>30 天）
   - 优先级最低
   - 仅在特定问题类型时检索（如"以前"、"历史"、"趋势"）
   - 使用向量检索

**核心方法：**
```java
// 分层检索记忆
List<MemoryItemDTO> retrieveHierarchicalMemories(
    String customerId,
    String groupId,
    String userQuestion,
    int maxMemories
)

// 判断是否需要长期记忆
boolean needsLongTermMemory(String question)

// 记忆去重
List<MemoryItemDTO> deduplicateMemories(List<MemoryItemDTO> memories)
```

#### TokenMonitor（新增）
**职责：** 监控 token 使用情况并记录日志

**核心功能：**
```java
// 记录 token 使用
void recordTokenUsage(TokenUsageMetrics metrics)

// 检查是否超过告警阈值
boolean shouldAlert(int promptTokens)

// 获取统计信息
TokenStatistics getStatistics(String customerId, LocalDate date)
```

**监控指标：**
- 各部分 token 使用（核心画像、记忆、对话历史、扩展画像）
- 总 prompt tokens、completion tokens、total tokens
- 响应时间
- 超限次数

## 3. 配置设计

### 3.1 application.yml 配置

```yaml
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
      total-tokens-threshold: 28000  # Total tokens 告警阈值（接近 32K 上限）
```

### 3.2 配置属性类

```java
@Data
@Component
@ConfigurationProperties(prefix = "ai.context")
public class ContextBudgetProperties {
    private int totalBudget = 4000;
    private int userMessageReserve = 500;
    private int coreProfileBudget = 800;
    private int relevantMemoriesBudget = 1200;
    private int conversationHistoryBudget = 600;
    private int extendedProfileBudget = 400;
    
    private MemoryConfig memory = new MemoryConfig();
    private ConversationConfig conversation = new ConversationConfig();
    private AlertConfig alert = new AlertConfig();
    
    @Data
    public static class MemoryConfig {
        private int maxCount = 10;
        private double similarityThreshold = 0.7;
        private int shortTermDays = 3;
        private int midTermDays = 30;
        private boolean enableDeduplication = true;
    }
    
    @Data
    public static class ConversationConfig {
        private int maxRounds = 5;
        private boolean enabled = true;
    }
    
    @Data
    public static class AlertConfig {
        private boolean enabled = true;
        private int promptTokensThreshold = 6000;
        private int totalTokensThreshold = 28000;
    }
}
```

## 4. 预算分配策略

### 4.1 优先级排序

1. **核心画像**（必须，800 tokens）
   - 基本信息：姓名、年龄、性别
   - 关键健康指标：血压、BMI、血糖
   - 过敏信息
   - 健康目标

2. **相关记忆**（重要，1200 tokens）
   - 按相似度排序（>= 0.7）
   - 分层检索：短期 → 中期 → 长期
   - 去重处理

3. **对话历史**（重要，600 tokens）
   - 最近 5 轮对话
   - 按时间倒序（最新的优先）
   - 格式化为简洁文本

4. **扩展画像**（可选，400 tokens）
   - 根据问题类型动态选择
   - 饮食问题 → 饮食偏好
   - 运动问题 → 运动习惯
   - 用药问题 → 慢性病、用药信息

### 4.2 动态调整规则

- 如果某部分未用完预算，剩余预算可分配给下一优先级
- 如果某部分超预算，进行压缩或截断
- 核心画像超预算时，按比例压缩（保留关键信息）
- 记忆和对话历史超预算时，截断（保留高优先级项）

### 4.3 预算分配示例

**场景 1：正常情况**
```
核心画像: 650/800 tokens
相关记忆: 980/1200 tokens
对话历史: 450/600 tokens
扩展画像: 320/400 tokens
总计: 2400/3500 tokens
剩余: 1100 tokens
```

**场景 2：记忆较多**
```
核心画像: 650/800 tokens
相关记忆: 1200/1200 tokens (达到上限)
对话历史: 600/600 tokens (使用剩余预算)
扩展画像: 550/400 tokens (使用剩余预算)
总计: 3000/3500 tokens
剩余: 500 tokens
```

**场景 3：核心画像超预算**
```
核心画像: 950 tokens → 压缩到 800 tokens
相关记忆: 1200/1200 tokens
对话历史: 600/600 tokens
扩展画像: 400/400 tokens
总计: 3000/3500 tokens
剩余: 500 tokens
```

## 5. 记忆分层检索策略

### 5.1 检索流程

```java
List<MemoryItemDTO> retrieveMemories(String customerId, String groupId, String question) {
    List<MemoryItemDTO> result = new ArrayList<>();
    int maxCount = properties.getMemory().getMaxCount();
    
    // 1. 短期记忆（最近 3 天，直接查询）
    List<MemoryItemDTO> shortTerm = getShortTermMemories(customerId, groupId, 3);
    result.addAll(shortTerm);
    log.debug("短期记忆: {} 条", shortTerm.size());
    
    // 2. 中期记忆（最近 30 天，向量检索）
    if (result.size() < maxCount) {
        int remaining = maxCount - result.size();
        List<MemoryItemDTO> midTerm = vectorSearch(customerId, groupId, question, remaining, 30);
        result.addAll(midTerm);
        log.debug("中期记忆: {} 条", midTerm.size());
    }
    
    // 3. 长期记忆（>30 天，仅特定问题）
    if (result.size() < maxCount && needsLongTermMemory(question)) {
        int remaining = maxCount - result.size();
        List<MemoryItemDTO> longTerm = vectorSearch(customerId, groupId, question, remaining, null);
        result.addAll(longTerm);
        log.debug("长期记忆: {} 条", longTerm.size());
    }
    
    // 4. 去重和排序
    result = deduplicateMemories(result);
    result.sort((a, b) -> Double.compare(b.getSimilarity(), a.getSimilarity()));
    
    log.info("记忆检索完成 - 总计: {} 条", result.size());
    return result;
}
```

### 5.2 长期记忆触发条件

```java
boolean needsLongTermMemory(String question) {
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
```

### 5.3 记忆去重策略

```java
List<MemoryItemDTO> deduplicateMemories(List<MemoryItemDTO> memories) {
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

// 简单的内容相似度判断（基于编辑距离或关键词重叠）
boolean isSimilarContent(String content1, String content2) {
    // 实现方式 1：完全相同
    if (content1.equals(content2)) {
        return true;
    }
    
    // 实现方式 2：包含关系
    if (content1.contains(content2) || content2.contains(content1)) {
        return true;
    }
    
    // 实现方式 3：编辑距离（可选）
    // int distance = levenshteinDistance(content1, content2);
    // return distance < threshold;
    
    return false;
}
```

## 6. 对话历史管理

### 6.1 数据模型

```java
@Data
@Builder
public class ConversationMessage {
    private String role;        // "user" 或 "assistant"
    private String content;     // 消息内容
    private LocalDateTime time; // 消息时间
}
```

### 6.2 查询逻辑

```java
List<ConversationMessage> getRecentHistory(
        String customerId, 
        String conversationId, 
        int maxRounds) {
    
    // 从数据库查询最近的对话记录
    // SELECT user_message, ai_response, create_time 
    // FROM b_ai_chat_history 
    // WHERE customer_id = ? AND conversation_id = ? AND is_deleted = 0
    // ORDER BY create_time DESC 
    // LIMIT ?
    
    List<ConversationMessage> messages = new ArrayList<>();
    
    for (ChatHistory record : records) {
        // 用户消息
        messages.add(ConversationMessage.builder()
            .role("user")
            .content(record.getUserMessage())
            .time(record.getCreateTime())
            .build());
        
        // AI 回复
        if (record.getAiResponse() != null) {
            messages.add(ConversationMessage.builder()
                .role("assistant")
                .content(record.getAiResponse())
                .time(record.getCreateTime())
                .build());
        }
    }
    
    // 按时间正序排列（最早的在前）
    Collections.reverse(messages);
    
    return messages;
}
```

### 6.3 格式化逻辑

```java
String formatHistory(List<ConversationMessage> history, int budget) {
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
            log.debug("对话历史预算用完，已选择 {} 条消息", 
                history.size() - i - 1);
            break;
        }
    }
    
    return formatted.toString();
}
```

## 7. Token 监控设计

### 7.1 监控指标

```java
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
    private boolean isOverBudget;       // 是否超预算
    private boolean shouldAlert;        // 是否需要告警
}
```

### 7.2 监控实现

```java
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

### 7.3 日志输出示例

**正常情况：**
```
INFO  - Token 使用详情 - customerId: user123, conversationId: conv-abc, 
        核心画像: 650, 记忆: 980, 对话: 450, 扩展: 320, 
        系统提示词总计: 2400, API返回: prompt=2450, completion=380, total=2830, 
        上下文构建: 45ms, API响应: 1250ms
```

**告警情况：**
```
WARN  - ⚠️ Token 使用超过告警阈值 - customerId: user123, 
        prompt_tokens: 6200 (阈值: 6000), 
        建议检查上下文配置或优化内容
```

## 8. 实施计划

### 8.1 阶段 1：配置化和监控（优先级高）

**任务清单：**
1. 创建 `ContextBudgetProperties` 配置类
2. 在 `application.yml` 中添加配置项
3. 修改 `ContextBudgetManager` 使用配置化预算
4. 创建 `TokenMonitor` 组件
5. 在 `DeepSeekClient` 中集成 token 监控
6. 添加单元测试

**预期成果：**
- 预算可通过配置文件调整
- 每次 API 调用都有详细的 token 使用日志
- 超过阈值时自动告警

### 8.2 阶段 2：记忆分层和对话历史（优先级中）

**任务清单：**
1. 创建 `ConversationMessage` 数据模型
2. 创建 `ConversationHistoryManager` 组件
3. 实现对话历史查询和格式化
4. 创建 `HierarchicalMemoryManager` 组件
5. 实现记忆分层检索逻辑
6. 实现记忆去重功能
7. 在 `ContextBudgetManager` 中集成对话历史
8. 在 `AiChatService` 中使用分层记忆检索
9. 添加单元测试和集成测试

**预期成果：**
- 对话历史自动注入到上下文
- 记忆按短期/中期/长期分层检索
- 重复记忆自动去重
- 整体 token 使用控制在预算内

### 8.3 验证标准

**功能验证：**
- [ ] 配置文件可以调整预算分配
- [ ] Token 使用日志完整准确
- [ ] 超过阈值时触发告警
- [ ] 对话历史正确注入
- [ ] 记忆分层检索正常工作
- [ ] 记忆去重有效

**性能验证：**
- [ ] 上下文构建耗时 < 100ms
- [ ] Prompt tokens 控制在 4000 以内
- [ ] API 响应时间 < 3s

**质量验证：**
- [ ] AI 回复质量不下降
- [ ] 关键信息（过敏、慢性病）不丢失
- [ ] 对话连贯性良好

## 9. 风险和注意事项

### 9.1 风险识别

1. **对话历史查询性能**
   - 风险：频繁查询数据库可能影响性能
   - 缓解：添加索引（idx_customer_group, idx_conversation）
   - 缓解：考虑使用 Redis 缓存最近对话

2. **记忆去重准确性**
   - 风险：简单的字符串匹配可能误判
   - 缓解：先使用简单策略，后续可优化为语义相似度

3. **Token 估算误差**
   - 风险：估算值与实际值可能有偏差
   - 缓解：记录估算值和实际值的差异，持续优化估算算法

4. **配置调整影响**
   - 风险：预算配置不当可能影响 AI 回复质量
   - 缓解：提供默认配置，建议在测试环境验证后再调整

### 9.2 注意事项

1. **向后兼容**
   - 保持现有 API 接口不变
   - 配置项提供默认值
   - 新功能可通过配置开关控制

2. **日志级别**
   - 详细的 token 使用日志使用 INFO 级别
   - 告警日志使用 WARN 级别
   - 调试信息使用 DEBUG 级别

3. **测试覆盖**
   - 单元测试覆盖核心逻辑
   - 集成测试验证端到端流程
   - 性能测试验证 token 控制效果

## 10. 总结

本设计方案通过以下措施确保 DeepSeek API 调用的 token 使用在预算内：

1. **配置化预算管理** - 灵活调整各部分预算分配
2. **优先级排序** - 确保关键信息（核心画像）优先注入
3. **记忆分层检索** - 提升检索效率和相关性
4. **对话历史管理** - 增强对话连贯性
5. **Token 监控告警** - 实时监控和预警

预期效果：
- ✅ Prompt tokens 控制在 4000 以内
- ✅ 保持 AI 回复质量
- ✅ 降低 API 调用成本 30-50%
- ✅ 提升响应速度
