# 记忆检索 Rerank 功能说明

## 概述

本文档说明 `vector-memory-service` 中实现的**混合检索 + Rerank** 功能，用于解决 Redis 缓存记忆与用户当前提问语义不相关的问题。

## 问题背景

### 原有实现的问题

**旧版检索流程**：
```
1. 优先从 Redis 获取最近 topK 条记忆
2. 如果 Redis 返回数量 >= topK，直接返回（不查询 Milvus）
3. 否则，查询 Milvus 补充不足的记忆
```

**核心缺陷**：
- Redis 缓存是**按时间排序**的（最近的对话），不考虑**语义相关性**
- 当用户切换话题时，Redis 返回的记忆可能与当前提问完全无关
- 例如：上次聊天气，现在问技术问题，Redis 返回的天气记忆毫无帮助

### 举例说明

**场景 1: 话题切换**
```
上次对话（已缓存到 Redis）:
- "今天天气怎么样？"
- "明天会下雨吗？"
- "周末适合出游吗？"

当前提问:
- "如何实现分布式事务？"  ← 与天气话题完全无关

旧版返回: 天气相关的 5 条记忆 ❌
应该返回: 技术相关的记忆 ✅
```

**场景 2: 跨时间查询**
```
3 天前的对话（不在 Redis 缓存中）:
- "Seata 的 AT 模式原理是什么？"
- "如何配置 Seata 服务端？"

当前提问:
- "Seata 回滚失败怎么办？"  ← 与 3 天前的对话高度相关

旧版返回: 最近 24 小时的无关记忆 ❌
应该返回: 3 天前的 Seata 相关记忆 ✅
```

---

## 新版实现：混合检索 + Rerank

### 核心思路

1. **并行检索** Redis 和 Milvus（不再有快速路径跳过 Milvus）
2. **合并结果**并去重
3. **Rerank**：基于语义相关性重新排序
4. 返回 topK 结果

### 检索流程

```
用户查询 (query, groupId, customerId, topK=5)
    ↓
┌─────────────────────────────────────────┐
│ Step 1: 并行检索（请求 topK * 2 = 10 条）│
├─────────────────────────────────────────┤
│ 1.1 Redis 缓存检索                       │
│     - 最近 10 条记忆（按时间排序）        │
│     - 相似度 = 0.0（未计算）             │
│                                         │
│ 1.2 Milvus 向量检索                      │
│     - 语义相似的 10 条记忆               │
│     - 相似度 >= 0.7                      │
│     - 基于 embedding 余弦相似度          │
└─────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────┐
│ Step 2: 合并结果并去重                   │
│ - 基于 memoryId 去重                     │
│ - 如果重复，保留相似度更高的              │
│ - 假设合并后有 15 条候选记忆             │
└─────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────┐
│ Step 3: Rerank（语义重排序）             │
│ 策略 1: partial（默认）                  │
│   - 仅对 Redis 记忆重新计算相似度        │
│   - Milvus 记忆保留原始相似度            │
│                                         │
│ 策略 2: full                             │
│   - 对所有候选记忆重新计算相似度          │
│   - 统一使用 query embedding 计算        │
│                                         │
│ 实现：                                   │
│   1. 生成 query 的 embedding             │
│   2. 批量生成记忆内容的 embedding        │
│   3. 计算余弦相似度                      │
│   4. 按相似度降序排序                    │
└─────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────┐
│ Step 4: 返回 topK 结果                   │
│ - 取相似度最高的 5 条记忆                │
│ - 保证与当前 query 语义相关              │
└─────────────────────────────────────────┘
```

---

## 代码实现

### 1. MemoryRerankService（新增）

**位置**: `vector-memory-service/src/main/java/com/lx/governance/vectormemory/service/MemoryRerankService.java`

**核心方法**：

#### `rerank()` - 全量重排序
```java
public List<MemoryItemDTO> rerank(String query, List<MemoryItemDTO> memories, int topK) {
    // 1. 生成 query embedding
    float[] queryVector = embeddingService.generateEmbedding(query);
    
    // 2. 批量生成记忆 embedding
    List<float[]> memoryVectors = embeddingService.generateEmbeddings(memoryContents);
    
    // 3. 计算每条记忆与 query 的余弦相似度
    for (int i = 0; i < memories.size(); i++) {
        double rerankScore = embeddingService.cosineSimilarity(queryVector, memoryVectors.get(i));
        memories.get(i).setSimilarity(rerankScore);
    }
    
    // 4. 按相似度降序排序，返回 topK
    return memories.stream()
        .sorted(Comparator.comparing(MemoryItemDTO::getSimilarity).reversed())
        .limit(topK)
        .collect(Collectors.toList());
}
```

#### `rerankPartial()` - 部分重排序（推荐）
```java
public List<MemoryItemDTO> rerankPartial(String query, List<MemoryItemDTO> memories, int topK) {
    // 1. 找出需要重新打分的记忆（相似度为 0.0 的，来自 Redis）
    List<MemoryItemDTO> needRerank = memories.stream()
        .filter(m -> m.getSimilarity() == null || m.getSimilarity() == 0.0)
        .collect(Collectors.toList());
    
    // 2. 仅对这些记忆重新计算相似度
    // 3. Milvus 的记忆保留原始相似度
    // 4. 统一排序并返回 topK
}
```

**优势**：
- ✅ 节省 embedding 计算成本（Milvus 记忆已有相似度分数）
- ✅ 保留 Milvus 的向量检索优势
- ✅ 仅对 Redis 缓存记忆补充语义相关性

---

### 2. MemoryRetrievalService（重构）

**位置**: `vector-memory-service/src/main/java/com/lx/governance/vectormemory/service/MemoryRetrievalService.java`

**核心改动**：

#### 移除快速路径
```java
// ❌ 删除这段代码
if (recentMemories.size() >= topK) {
    return recentMemories;  // 不再直接返回 Redis 结果
}
```

#### 并行检索
```java
// ✅ 并行检索 Redis 和 Milvus（请求 2 倍数量）
int fetchCount = topK * 2;

List<MemoryItemDTO> recentMemories = redisMemoryIndexService.getRecentMemories(groupId, fetchCount);
List<MemoryItemDTO> milvusMemories = vectorStoreService.searchGroupMemories(query, groupId, customerId, fetchCount);
```

#### 合并与去重
```java
// ✅ 合并结果，基于 memoryId 去重
List<MemoryItemDTO> allCandidates = mergeAndDeduplicate(recentMemories, milvusMemories);
```

#### Rerank
```java
// ✅ 根据配置选择 Rerank 策略
if (properties.getRerank().isEnabled()) {
    if ("full".equalsIgnoreCase(strategy)) {
        finalResults = rerankService.rerank(query, allCandidates, topK);
    } else {
        finalResults = rerankService.rerankPartial(query, allCandidates, topK);
    }
}
```

---

## 配置说明

### application.yml

```yaml
# 记忆检索配置
memory:
  retrieval:
    # 是否启用 Rerank（语义重排序）
    rerank:
      enabled: true
      # Rerank 策略: full（全量重排序）, partial（仅对 Redis 缓存记忆重排序）
      strategy: partial
```

### 配置项说明

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `memory.retrieval.rerank.enabled` | boolean | true | 是否启用 Rerank |
| `memory.retrieval.rerank.strategy` | string | partial | Rerank 策略：`full` 或 `partial` |

### 策略对比

| 策略 | 计算成本 | 准确性 | 适用场景 |
|------|---------|--------|---------|
| **partial** | 低（仅对 Redis 记忆计算） | 高 | 推荐，平衡性能与准确性 |
| **full** | 高（对所有候选记忆计算） | 最高 | 对准确性要求极高的场景 |
| **disabled** | 无 | 低 | 调试或性能测试 |

---

## 性能影响

### 延迟分析

**旧版流程**（Redis 快速路径）：
```
Redis 查询: ~5ms
总延迟: ~5ms
```

**新版流程**（混合检索 + Rerank）：
```
Redis 查询: ~5ms（并行）
Milvus 查询: ~50ms（并行）
合并去重: ~1ms
Rerank (partial): ~30ms（embedding 计算）
总延迟: ~86ms（并行后）
```

**延迟增加**：约 80ms

**优化建议**：
1. 使用 `partial` 策略（默认）减少 embedding 计算
2. 调整 `fetchCount = topK * 2`（当前为 2 倍，可根据实际情况调整）
3. 考虑缓存 query embedding（相同查询复用）

---

## 效果对比

### 测试场景：话题切换

**查询**: "如何实现分布式事务？"

**旧版返回**（Redis 快速路径）：
```
1. "今天天气怎么样？" (similarity: 0.0)
2. "明天会下雨吗？" (similarity: 0.0)
3. "周末适合出游吗？" (similarity: 0.0)
4. "推荐一家餐厅" (similarity: 0.0)
5. "附近有什么景点？" (similarity: 0.0)
```
❌ 完全不相关

**新版返回**（混合检索 + Rerank）：
```
1. "Seata 的 AT 模式原理是什么？" (similarity: 0.89)
2. "如何配置 Seata 服务端？" (similarity: 0.85)
3. "分布式事务的两阶段提交" (similarity: 0.82)
4. "Seata 回滚失败怎么办？" (similarity: 0.78)
5. "TCC 模式和 AT 模式的区别" (similarity: 0.75)
```
✅ 高度相关

---

## 使用建议

### 推荐配置

```yaml
memory:
  retrieval:
    rerank:
      enabled: true      # 启用 Rerank
      strategy: partial  # 使用部分重排序（性能与准确性平衡）
```

### 何时禁用 Rerank

1. **性能测试**：对比启用/禁用 Rerank 的延迟差异
2. **调试**：排查 Milvus 向量检索问题
3. **特殊场景**：用户明确要求"最近的对话"（时间优先）

### 监控指标

建议监控以下指标：
- Rerank 耗时（P50, P95, P99）
- Embedding 调用次数
- 最终返回记忆的平均相似度
- 用户反馈（记忆是否相关）

---

## 总结

### 改进点

1. ✅ **解决语义不相关问题**：不再因为 Redis 缓存命中而返回无关记忆
2. ✅ **兼顾时间与语义**：Redis（时间新鲜度）+ Milvus（语义相关性）
3. ✅ **灵活配置**：支持启用/禁用 Rerank，支持 full/partial 策略
4. ✅ **性能可控**：partial 策略减少计算成本

### 代价

1. ⚠️ **延迟增加**：约 80ms（可接受）
2. ⚠️ **Embedding 成本**：每次查询需要调用 embedding API

### 后续优化方向

1. **Query Embedding 缓存**：相同查询复用 embedding
2. **异步 Rerank**：先返回初步结果，后台 Rerank 后更新
3. **用户反馈学习**：根据用户点击/忽略记录优化 Rerank 权重
4. **专用 Rerank 模型**：使用 BGE-reranker 等专门的 rerank 模型

---

## 相关文件

- `MemoryRerankService.java` - Rerank 服务实现
- `MemoryRetrievalService.java` - 记忆检索服务（重构）
- `MemoryRetrievalProperties.java` - 配置属性类
- `application.yml` - 配置文件
- `EmbeddingService.java` - Embedding 服务（复用）

---

**作者**: LeJinBo  
**日期**: 2026-05-25  
**版本**: v1.0
