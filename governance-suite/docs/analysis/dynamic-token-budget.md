# 动态 Token 预算调整优化

## 背景

在原有实现中，token 预算是固定的，无论对话复杂度如何，都使用相同的预算分配策略。这导致：

1. **资源浪费**：
   - 简单对话（如"你好"、"谢谢"）也使用完整的上下文预算
   - 不必要的记忆检索和对话历史加载

2. **上下文不足**：
   - 复杂对话（如多轮技术咨询）可能需要更多上下文
   - 固定预算可能导致关键信息被截断

3. **响应质量不稳定**：
   - 简单问题过度依赖上下文，可能引入噪音
   - 复杂问题上下文不足，回答质量下降

## 解决方案：动态 Token 预算调整

### 核心思想

根据 `SessionContext.complexity` 字段（由 `DeepSeekIntentAnalyzer` 分析得出），动态调整各部分的 token 预算：

- **simple**（简单对话）：减少上下文预算，快速响应
- **medium**（中等复杂度）：使用基础预算
- **complex**（复杂对话）：增加上下文预算，提供更丰富的信息

### 预算调整策略

| 复杂度 | 核心画像 | 记忆 | 历史 | 扩展画像 | 总计 |
|--------|---------|------|------|---------|------|
| **simple** | 600 (×0.75) | 600 (×0.5) | 300 (×0.5) | 200 (×0.5) | **1,700** |
| **medium** | 800 (×1.0) | 1200 (×1.0) | 600 (×1.0) | 400 (×1.0) | **3,000** |
| **complex** | 1000 (×1.25) | 1800 (×1.5) | 900 (×1.5) | 600 (×1.5) | **4,300** |

**说明**：
- 基础预算来自 `application.yml` 配置
- 乘数因子根据复杂度动态调整
- 核心画像始终保留（即使是 simple 也有 600 tokens）

### 实现架构

```
SessionContext (complexity)
    ↓
DynamicBudgetStrategy.calculateBudget()
    ↓
BudgetAllocation (动态预算)
    ↓
ContextBudgetManager.buildBudgetedSystemPrompt()
    ↓
DeepSeekClient.chat()
```

### 核心类

#### 1. DynamicBudgetStrategy

**位置**：`ai-chat-service/src/main/java/com/lx/governance/aichat/context/DynamicBudgetStrategy.java`

**职责**：根据复杂度计算动态预算

**核心方法**：

```java
public BudgetAllocation calculateBudget(String complexity, BaseBudgetConfig baseBudget) {
    BudgetAllocation allocation = new BudgetAllocation();
    
    switch (complexity.toLowerCase()) {
        case "simple":
            allocation.setCoreProfileBudget((int) (baseBudget.getCoreProfileBudget() * 0.75));
            allocation.setMemoryBudget((int) (baseBudget.getRelevantMemoriesBudget() * 0.5));
            allocation.setSessionContextBudget((int) (baseBudget.getConversationHistoryBudget() * 0.5));
            allocation.setExtendedProfileBudget((int) (baseBudget.getExtendedProfileBudget() * 0.5));
            break;
            
        case "complex":
            allocation.setCoreProfileBudget((int) (baseBudget.getCoreProfileBudget() * 1.25));
            allocation.setMemoryBudget((int) (baseBudget.getRelevantMemoriesBudget() * 1.5));
            allocation.setSessionContextBudget((int) (baseBudget.getConversationHistoryBudget() * 1.5));
            allocation.setExtendedProfileBudget((int) (baseBudget.getExtendedProfileBudget() * 1.5));
            break;
            
        case "medium":
        default:
            // 使用基础预算
            allocation.setCoreProfileBudget(baseBudget.getCoreProfileBudget());
            allocation.setMemoryBudget(baseBudget.getRelevantMemoriesBudget());
            allocation.setSessionContextBudget(baseBudget.getConversationHistoryBudget());
            allocation.setExtendedProfileBudget(baseBudget.getExtendedProfileBudget());
            break;
    }
    
    return allocation;
}
```

#### 2. ContextBudgetManager（修改）

**修改点**：在 `buildBudgetedSystemPrompt()` 方法中集成动态预算

**关键代码**：

```java
// 根据对话复杂度动态调整预算
String complexity = (sessionContext != null && sessionContext.getComplexity() != null)
        ? sessionContext.getComplexity()
        : "medium";

DynamicBudgetStrategy.BaseBudgetConfig baseBudget = new DynamicBudgetStrategy.BaseBudgetConfig();
baseBudget.setCoreProfileBudget(properties.getCoreProfileBudget());
baseBudget.setRelevantMemoriesBudget(properties.getRelevantMemoriesBudget());
baseBudget.setConversationHistoryBudget(properties.getConversationHistoryBudget());
baseBudget.setExtendedProfileBudget(properties.getExtendedProfileBudget());

BudgetAllocation dynamicBudget = dynamicBudgetStrategy.calculateBudget(complexity, baseBudget);

// 使用动态预算构建上下文
int coreProfileBudget = dynamicBudget.getCoreProfileBudget();
int relevantMemoriesBudget = dynamicBudget.getMemoryBudget();
int sessionContextBudget = dynamicBudget.getSessionContextBudget();
int extendedProfileBudget = dynamicBudget.getExtendedProfileBudget();
```

### 配置说明

**位置**：`ai-chat-service/src/main/resources/application.yml`

```yaml
ai:
  context:
    # Token 预算配置（基础配置，会根据对话复杂度动态调整）
    total-budget: 4000
    user-message-reserve: 500

    # 各部分预算分配（按优先级）
    # 注意：这些是基础预算，实际使用时会根据 SessionContext.complexity 动态调整
    # - simple: 各部分预算 * 0.5-0.75（简单对话，减少上下文）
    # - medium: 使用基础预算（默认）
    # - complex: 各部分预算 * 1.25-1.5（复杂对话，增加上下文）
    core-profile-budget: 800        # 核心画像（必须）
    relevant-memories-budget: 1200  # 相关记忆（重要）
    conversation-history-budget: 600 # 对话历史（重要）
    extended-profile-budget: 400    # 扩展画像（可选）
```

## 优化效果

### 预期效果

假设对话分布：40% simple，40% medium，20% complex

| 指标 | 优化前 | 优化后 | 改善 |
|------|--------|--------|------|
| 平均 token 消耗 | 3000 | 2560 | ↓ 15% |
| simple 对话响应时间 | 150ms | 100ms | ↓ 33% |
| complex 对话质量 | 中等 | 高 | ↑ 显著 |
| API 成本 | 100% | 85% | ↓ 15% |

**计算说明**：
- simple (40%): 1700 tokens
- medium (40%): 3000 tokens
- complex (20%): 4300 tokens
- 平均 = 1700×0.4 + 3000×0.4 + 4300×0.2 = 2560 tokens

### 实际场景

**场景 1：简单寒暄**
- 输入："你好"
- 复杂度：simple
- 预算：1700 tokens（减少 43%）
- 效果：快速响应，不加载冗余上下文

**场景 2：一般咨询**
- 输入："今天天气怎么样？"
- 复杂度：medium
- 预算：3000 tokens（基础预算）
- 效果：标准响应质量

**场景 3：复杂技术问题**
- 输入："如何管理我的血糖水平？我有糖尿病家族史，最近血糖偏高。"
- 复杂度：complex
- 预算：4300 tokens（增加 43%）
- 效果：加载更多记忆和历史，提供更详细的建议

## 监控与日志

### 日志示例

```
2026-05-25 11:45:00 INFO  DynamicBudgetStrategy - 动态预算分配 - complexity: simple, 核心画像: 600, 记忆: 600, 历史: 300, 扩展: 200, 总计: 1700
2026-05-25 11:45:00 INFO  ContextBudgetManager - 上下文构建完成 - 总 tokens: 1650/1700, 剩余预算: 50, 耗时: 45ms

2026-05-25 11:46:00 INFO  DynamicBudgetStrategy - 动态预算分配 - complexity: complex, 核心画像: 1000, 记忆: 1800, 历史: 900, 扩展: 600, 总计: 4300
2026-05-25 11:46:00 INFO  ContextBudgetManager - 上下文构建完成 - 总 tokens: 4150/4300, 剩余预算: 150, 耗时: 120ms
```

### 统计分析

通过日志可以统计：

```bash
# 统计各复杂度的分布
grep "动态预算分配" ai-chat-service.log | awk -F'complexity: ' '{print $2}' | awk '{print $1}' | sort | uniq -c

# 统计平均 token 消耗
grep "上下文构建完成" ai-chat-service.log | awk -F'总 tokens: ' '{print $2}' | awk -F'/' '{print $1}' | awk '{sum+=$1; count++} END {print "平均:", sum/count}'

# 统计各复杂度的平均耗时
grep "complexity: simple" ai-chat-service.log -A 1 | grep "耗时" | awk -F'耗时: ' '{print $2}' | awk -F'ms' '{sum+=$1; count++} END {print "simple 平均耗时:", sum/count, "ms"}'
```

## 与智能过滤的协同

动态预算调整与智能记忆检索过滤（已实现）协同工作：

1. **智能过滤**（第一层）：
   - 规则判断：跳过无意义消息（"哈哈"、"123"）
   - 意图分析：跳过 simple 复杂度的记忆检索

2. **动态预算**（第二层）：
   - 如果通过智能过滤，根据复杂度调整预算
   - simple：即使检索记忆，也只分配 600 tokens
   - complex：分配 1800 tokens，加载更多记忆

**协同效果**：
- simple 对话：跳过记忆检索 + 减少预算 = 最快响应
- complex 对话：执行记忆检索 + 增加预算 = 最佳质量

## 后续优化方向

1. **自适应调整因子**：
   - 根据历史数据，动态调整 simple/complex 的乘数因子
   - 例如：如果 simple 对话质量下降，增加乘数因子

2. **用户级预算策略**：
   - VIP 用户：使用更高的基础预算
   - 普通用户：使用标准预算

3. **时间段预算策略**：
   - 高峰期：减少预算，提高吞吐量
   - 低峰期：增加预算，提高质量

4. **A/B 测试**：
   - 对比不同乘数因子的效果
   - 优化预算分配策略

## 总结

通过动态 Token 预算调整，我们实现了：

**核心优势**：
- ✅ 根据对话复杂度智能调整预算
- ✅ 简单对话快速响应，节省成本
- ✅ 复杂对话提供更丰富的上下文
- ✅ 与智能过滤协同工作

**预期效果**：
- ⚡ 平均响应时间降低 15%
- 💰 API 成本降低 15%
- 📈 复杂对话质量显著提升
- 🎯 资源利用更加高效

**实现简洁**：
- 新增 `DynamicBudgetStrategy` 类（80 行代码）
- 修改 `ContextBudgetManager`（10 行代码）
- 配置化设计，易于调整
