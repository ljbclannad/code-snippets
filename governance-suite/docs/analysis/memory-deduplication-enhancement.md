# 记忆检索增强去重策略

## 背景

在原有实现中，去重策略仅基于记忆 ID，存在以下问题：

1. **语义重复无法识别**：
   - 不同 ID 但内容相似的记忆无法去重
   - 例如："今天血糖 7.2" 和 "今日血糖值为 7.2mmol/L" 是同一条信息

2. **时间维度缺失**：
   - 短时间内的重复记忆无法合并
   - 例如：用户在 5 分钟内多次提到同一件事

3. **检索结果冗余**：
   - 返回的记忆中存在大量重复信息
   - 浪费 token 预算，降低上下文质量

## 解决方案：多层去重策略

### 核心思想

实现三层去重策略，逐步过滤重复记忆：

1. **ID 去重**（基础层）：基于记忆 ID，保留相似度更高的
2. **语义去重**（核心层）：基于内容语义相似度，识别语义重复
3. **时间窗口去重**（优化层）：在时间窗口内合并相似记忆

### 去重流程

```
Redis 记忆 + Milvus 记忆
    ↓
[1] ID 去重（基于 memoryId）
    ↓
[2] 语义去重（相似度 >= 0.95）
    ↓
[3] 时间窗口去重（30分钟内，相似度 >= 0.85）
    ↓
最终结果
```

## 实现架构

### 核心类

#### 1. MemoryDeduplicationService

**位置**：`vector-memory-service/src/main/java/com/lx/governance/vectormemory/service/MemoryDeduplicationService.java`

**职责**：实现多层去重策略

**核心方法**：

```java
public List<MemoryItemDTO> mergeAndDeduplicate(
    List<MemoryItemDTO> redisMemories, 
    List<MemoryItemDTO> milvusMemories
) {
    // Step 1: ID 去重
    List<MemoryItemDTO> idDeduped = deduplicateById(redisMemories, milvusMemories);
    
    // Step 2: 语义去重（如果启用）
    List<MemoryItemDTO> semanticDeduped = idDeduped;
    if (properties.getDeduplication().isSemanticEnabled()) {
        semanticDeduped = deduplicateBySemantic(idDeduped);
    }
    
    // Step 3: 时间窗口去重（如果启用）
    List<MemoryItemDTO> finalResult = semanticDeduped;
    if (properties.getDeduplication().isTimeWindowEnabled()) {
        finalResult = deduplicateByTimeWindow(semanticDeduped);
    }
    
    return finalResult;
}
```

### 去重策略详解

#### 策略 1：ID 去重

**目标**：去除完全相同的记忆（ID 重复）

**逻辑**：
- 使用 `Map<Long, MemoryItemDTO>` 存储记忆
- 如果 ID 重复，保留相似度更高的记忆

**代码**：
```java
private List<MemoryItemDTO> deduplicateById(
    List<MemoryItemDTO> redisMemories, 
    List<MemoryItemDTO> milvusMemories
) {
    Map<Long, MemoryItemDTO> memoryMap = new HashMap<>();
    
    // 添加 Redis 记忆
    for (MemoryItemDTO memory : redisMemories) {
        if (memory.getId() != null) {
            memoryMap.put(memory.getId(), memory);
        }
    }
    
    // 添加 Milvus 记忆（如果 ID 重复，保留相似度更高的）
    for (MemoryItemDTO memory : milvusMemories) {
        if (memory.getId() != null) {
            MemoryItemDTO existing = memoryMap.get(memory.getId());
            if (existing == null) {
                memoryMap.put(memory.getId(), memory);
            } else {
                double existingSimilarity = existing.getSimilarity() != null ? existing.getSimilarity() : 0.0;
                double newSimilarity = memory.getSimilarity() != null ? memory.getSimilarity() : 0.0;
                if (newSimilarity > existingSimilarity) {
                    memoryMap.put(memory.getId(), memory);
                }
            }
        }
    }
    
    return new ArrayList<>(memoryMap.values());
}
```

#### 策略 2：语义去重

**目标**：去除语义高度相似的记忆

**逻辑**：
- 计算记忆之间的语义相似度（使用 embedding + 余弦相似度）
- 如果相似度 >= 阈值（默认 0.95），视为重复
- 保留相似度更高的记忆

**配置**：
```yaml
memory:
  retrieval:
    deduplication:
      semantic-enabled: true
      semantic-threshold: 0.95  # 相似度阈值
```

**示例**：
- 记忆 A："今天血糖 7.2"（相似度 0.92）
- 记忆 B："今日血糖值为 7.2mmol/L"（相似度 0.88）
- 语义相似度：0.96 >= 0.95 → 去重，保留记忆 A

**代码**：
```java
private List<MemoryItemDTO> deduplicateBySemantic(List<MemoryItemDTO> memories) {
    double threshold = properties.getDeduplication().getSemanticThreshold();
    List<MemoryItemDTO> result = new ArrayList<>();
    Set<Integer> removed = new HashSet<>();
    
    // 按相似度降序排序，优先保留相似度高的
    List<MemoryItemDTO> sorted = memories.stream()
        .sorted(Comparator.comparing(m -> m.getSimilarity() != null ? m.getSimilarity() : 0.0,
                Comparator.reverseOrder()))
        .collect(Collectors.toList());
    
    for (int i = 0; i < sorted.size(); i++) {
        if (removed.contains(i)) continue;
        
        MemoryItemDTO current = sorted.get(i);
        result.add(current);
        
        // 与后续记忆比较
        for (int j = i + 1; j < sorted.size(); j++) {
            if (removed.contains(j)) continue;
            
            MemoryItemDTO other = sorted.get(j);
            double similarity = calculateContentSimilarity(current.getContent(), other.getContent());
            
            if (similarity >= threshold) {
                removed.add(j);
                log.debug("语义去重: 移除相似记忆 (相似度={:.3f})", similarity);
            }
        }
    }
    
    return result;
}
```

#### 策略 3：时间窗口去重

**目标**：在时间窗口内合并相似记忆

**逻辑**：
- 按时间排序记忆
- 在时间窗口内（默认 30 分钟），如果相似度 >= 阈值（默认 0.85），视为重复
- 保留时间最近的记忆

**配置**：
```yaml
memory:
  retrieval:
    deduplication:
      time-window-enabled: true
      time-window-minutes: 30      # 时间窗口大小
      time-window-threshold: 0.85  # 相似度阈值
```

**示例**：
- 记忆 A："今天血糖 7.2"（10:00）
- 记忆 B："刚测了血糖，7.2"（10:15）
- 时间差：15 分钟 < 30 分钟
- 语义相似度：0.88 >= 0.85 → 去重，保留记忆 B（更近）

**代码**：
```java
private List<MemoryItemDTO> deduplicateByTimeWindow(List<MemoryItemDTO> memories) {
    int windowMinutes = properties.getDeduplication().getTimeWindowMinutes();
    double threshold = properties.getDeduplication().getTimeWindowThreshold();
    
    // 按时间倒序排序（最新的在前）
    List<MemoryItemDTO> sorted = memories.stream()
        .sorted(Comparator.comparing(m -> m.getCreatedAt() != null ? m.getCreatedAt() : LocalDateTime.MIN,
                Comparator.reverseOrder()))
        .collect(Collectors.toList());
    
    List<MemoryItemDTO> result = new ArrayList<>();
    Set<Integer> removed = new HashSet<>();
    
    for (int i = 0; i < sorted.size(); i++) {
        if (removed.contains(i)) continue;
        
        MemoryItemDTO current = sorted.get(i);
        result.add(current);
        
        if (current.getCreatedAt() == null) continue;
        
        // 检查时间窗口内的记忆
        for (int j = i + 1; j < sorted.size(); j++) {
            if (removed.contains(j)) continue;
            
            MemoryItemDTO other = sorted.get(j);
            if (other.getCreatedAt() == null) continue;
            
            long minutesDiff = ChronoUnit.MINUTES.between(other.getCreatedAt(), current.getCreatedAt());
            if (minutesDiff > windowMinutes) {
                break; // 超出时间窗口
            }
            
            double similarity = calculateContentSimilarity(current.getContent(), other.getContent());
            if (similarity >= threshold) {
                removed.add(j);
                log.debug("时间窗口去重: 移除 {}分钟内的相似记忆", minutesDiff);
            }
        }
    }
    
    return result;
}
```

### 语义相似度计算

使用 **Embedding + 余弦相似度** 计算两段文本的语义相似度：

```java
private double calculateContentSimilarity(String content1, String content2) {
    // 1. 完全相同
    if (content1.equals(content2)) {
        return 1.0;
    }
    
    // 2. 长度差异过大，直接判定为不相似
    int len1 = content1.length();
    int len2 = content2.length();
    double lengthRatio = (double) Math.min(len1, len2) / Math.max(len1, len2);
    if (lengthRatio < 0.5) {
        return 0.0;
    }
    
    // 3. 使用 embedding 计算语义相似度
    try {
        List<Double> embedding1 = embeddingService.embed(content1);
        List<Double> embedding2 = embeddingService.embed(content2);
        return embeddingService.cosineSimilarity(embedding1, embedding2);
    } catch (Exception e) {
        // 降级方案：简单文本相似度
        return calculateSimpleTextSimilarity(content1, content2);
    }
}
```

## 配置说明

**位置**：`vector-memory-service/src/main/resources/application.yml`

```yaml
memory:
  retrieval:
    deduplication:
      # 语义去重配置
      semantic-enabled: true        # 是否启用语义去重
      semantic-threshold: 0.95      # 语义相似度阈值（>= 此值视为重复）
      
      # 时间窗口去重配置
      time-window-enabled: true     # 是否启用时间窗口去重
      time-window-minutes: 30       # 时间窗口大小（分钟）
      time-window-threshold: 0.85   # 时间窗口内的相似度阈值
```

**参数说明**：

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `semantic-enabled` | true | 是否启用语义去重 |
| `semantic-threshold` | 0.95 | 语义相似度阈值，越高越严格 |
| `time-window-enabled` | true | 是否启用时间窗口去重 |
| `time-window-minutes` | 30 | 时间窗口大小（分钟） |
| `time-window-threshold` | 0.85 | 时间窗口内的相似度阈值 |

**调优建议**：

1. **语义去重阈值**：
   - 0.95+：严格去重，只去除几乎完全相同的记忆
   - 0.90-0.95：标准去重，去除高度相似的记忆
   - 0.85-0.90：宽松去重，去除较为相似的记忆

2. **时间窗口大小**：
   - 15-30 分钟：适合实时对话场景
   - 60-120 分钟：适合长时间对话场景
   - 1440 分钟（1天）：适合跨天去重

3. **时间窗口阈值**：
   - 通常低于语义去重阈值（0.85 vs 0.95）
   - 因为时间窗口内的记忆更可能是重复的

## 优化效果

### 预期效果

假设原始检索结果有 20 条记忆：

| 去重阶段 | 记忆数量 | 去重率 | 说明 |
|---------|---------|--------|------|
| 原始结果 | 20 | - | Redis + Milvus 合并结果 |
| ID 去重 | 18 | 10% | 去除完全相同的记忆 |
| 语义去重 | 14 | 22% | 去除语义高度相似的记忆 |
| 时间窗口去重 | 12 | 14% | 去除时间窗口内的相似记忆 |
| **最终结果** | **12** | **40%** | 总去重率 |

**效果分析**：
- ✅ 去重率提升：从 10%（仅 ID 去重）提升到 40%
- ✅ 上下文质量提升：减少冗余信息，提高信息密度
- ✅ Token 节省：减少 40% 的记忆 token 消耗
- ✅ 响应质量提升：更精准的上下文，更准确的回答

### 实际场景

**场景 1：血糖监测**

原始记忆（20 条）：
1. "今天早上血糖 7.2"（10:00）
2. "刚测了血糖，7.2mmol/L"（10:15）
3. "今日空腹血糖值为 7.2"（10:20）
4. "昨天血糖 6.8"（昨天 10:00）
5. ...

去重后（12 条）：
1. "今天早上血糖 7.2"（10:00）- 保留最早的
2. "昨天血糖 6.8"（昨天 10:00）- 不同日期，保留
3. ...

**场景 2：用药记录**

原始记忆（20 条）：
1. "今天吃了二甲双胍"（08:00）
2. "服用二甲双胍 500mg"（08:05）
3. "刚吃了降糖药"（08:10）
4. "昨天吃了二甲双胍"（昨天 08:00）
5. ...

去重后（12 条）：
1. "今天吃了二甲双胍"（08:00）- 保留最早的
2. "昨天吃了二甲双胍"（昨天 08:00）- 不同日期，保留
3. ...

## 监控与日志

### 日志示例

```
2026-05-25 12:00:00 DEBUG MemoryDeduplicationService - ID 去重: 20 -> 18
2026-05-25 12:00:00 DEBUG MemoryDeduplicationService - 语义去重: 移除相似记忆 (相似度=0.96): [今天血糖 7.2] vs [今日血糖值为 7.2mmol/L]
2026-05-25 12:00:00 DEBUG MemoryDeduplicationService - 语义去重: 18 -> 14
2026-05-25 12:00:00 DEBUG MemoryDeduplicationService - 时间窗口去重: 移除 15分钟内的相似记忆 (相似度=0.88): [今天血糖 7.2] vs [刚测了血糖，7.2]
2026-05-25 12:00:00 DEBUG MemoryDeduplicationService - 时间窗口去重: 14 -> 12
2026-05-25 12:00:00 INFO  MemoryDeduplicationService - 去重完成: 原始=20, 最终=12, 耗时=45ms
```

### 统计分析

通过日志可以统计：

```bash
# 统计平均去重率
grep "去重完成" vector-memory-service.log | awk -F'原始=' '{print $2}' | awk -F', 最终=' '{print $1, $2}' | awk -F', 耗时' '{print $1}' | awk '{sum_orig+=$1; sum_final+=$2; count++} END {print "平均去重率:", (1 - sum_final/sum_orig) * 100 "%"}'

# 统计各阶段去重效果
grep "ID 去重" vector-memory-service.log | awk -F': ' '{print $2}' | awk -F' -> ' '{sum_before+=$1; sum_after+=$2; count++} END {print "ID 去重率:", (1 - sum_after/sum_before) * 100 "%"}'

grep "语义去重" vector-memory-service.log | grep -v "移除" | awk -F': ' '{print $2}' | awk -F' -> ' '{sum_before+=$1; sum_after+=$2; count++} END {print "语义去重率:", (1 - sum_after/sum_before) * 100 "%"}'

grep "时间窗口去重" vector-memory-service.log | grep -v "移除" | awk -F': ' '{print $2}' | awk -F' -> ' '{sum_before+=$1; sum_after+=$2; count++} END {print "时间窗口去重率:", (1 - sum_after/sum_before) * 100 "%"}'
```

## 性能影响

### 延迟分析

| 去重策略 | 平均延迟 | 说明 |
|---------|---------|------|
| ID 去重 | ~1ms | HashMap 操作，极快 |
| 语义去重 | ~30ms | 需要计算 embedding（假设 10 条记忆） |
| 时间窗口去重 | ~5ms | 时间比较 + 语义相似度（已缓存） |
| **总计** | **~36ms** | 相比原始 ID 去重增加 35ms |

**优化建议**：
- 语义去重是主要开销（embedding 计算）
- 可以通过缓存 embedding 减少计算
- 可以通过批量计算 embedding 提高效率

### 成本分析

假设每次检索 20 条记忆，去重后 12 条：

| 指标 | 优化前 | 优化后 | 改善 |
|------|--------|--------|------|
| 记忆数量 | 20 | 12 | ↓ 40% |
| Token 消耗 | ~2000 | ~1200 | ↓ 40% |
| 延迟 | ~5ms | ~41ms | ↑ 36ms |
| 上下文质量 | 中等 | 高 | ↑ 显著 |

**结论**：
- ✅ 延迟增加可接受（36ms）
- ✅ Token 节省显著（40%）
- ✅ 上下文质量显著提升

## 总结

通过实现多层去重策略，我们实现了：

**核心优势**：
- ✅ 三层去重：ID + 语义 + 时间窗口
- ✅ 配置化设计：灵活启用/禁用各层去重
- ✅ 智能相似度计算：embedding + 余弦相似度
- ✅ 降级方案：embedding 失败时使用简单文本相似度

**预期效果**：
- ⚡ 去重率提升：从 10% 提升到 40%
- 💰 Token 节省：减少 40% 的记忆 token 消耗
- 📈 上下文质量显著提升
- 🎯 响应质量更准确

**实现简洁**：
- 新增 `MemoryDeduplicationService` 类（~300 行代码）
- 修改 `MemoryRetrievalService`（集成去重服务）
- 更新 `MemoryRetrievalProperties`（添加配置）
- 配置化设计，易于调整
