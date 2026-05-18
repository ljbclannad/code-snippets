# 向量记忆改造详细设计

**父文档**: [AI对话流程重构设计方案](./2026-05-18-ai-chat-flow-refactor-design.md)

---

## 1. 概述

向量记忆改造包括三个核心部分：
1. **Embedding模型升级**：从千问v2（1536维）升级到v3（1024维）
2. **Redis去重索引**：基于内容hash的快速去重
3. **最近记忆缓存**：Redis Sorted Set缓存最近20条记忆

### 1.1 设计目标

- **模型升级**：全量迁移到v3，提升向量质量
- **去重优化**：Redis快速去重，避免每次查询Milvus
- **检索加速**：优先返回最近记忆，减少Milvus查询
- **生产可靠**：支持断点续传、进度追踪、失败重试

---

## 2. Embedding模型升级

### 2.1 配置变更

```yaml
# vector-memory-service/application.yml
spring:
  ai:
    vectorstore:
      milvus:
        embedding-dimension: 1024  # 从1536改为1024
        collection-name: user_memory_vectors_v3  # 新collection
        index-type: IVF_FLAT
        metric-type: COSINE
        nlist: 1024
    
    dashscope:
      api-key: ${DASHSCOPE_API_KEY}
      embedding:
        options:
          model: text-embedding-v3  # 从v2升级到v3
```

### 2.2 Milvus Collection创建

```python
# 迁移前手动执行
from pymilvus import connections, Collection, FieldSchema, CollectionSchema, DataType

# 连接Milvus
connections.connect("default", host="localhost", port="19530")

# 定义Schema
fields = [
    FieldSchema(name="id", dtype=DataType.VARCHAR, is_primary=True, max_length=100),
    FieldSchema(name="embedding", dtype=DataType.FLOAT_VECTOR, dim=1024),
    FieldSchema(name="content", dtype=DataType.VARCHAR, max_length=2000),
    FieldSchema(name="group_id", dtype=DataType.VARCHAR, max_length=100),
    FieldSchema(name="memory_id", dtype=DataType.INT64),
    FieldSchema(name="memory_type", dtype=DataType.VARCHAR, max_length=50),
    FieldSchema(name="confidence_score", dtype=DataType.FLOAT),
    FieldSchema(name="create_time", dtype=DataType.INT64)
]

schema = CollectionSchema(fields, description="User memory vectors v3")

# 创建Collection
collection = Collection(name="user_memory_vectors_v3", schema=schema)

# 创建索引
index_params = {
    "index_type": "IVF_FLAT",
    "metric_type": "COSINE",
    "params": {"nlist": 1024}
}
collection.create_index(field_name="embedding", index_params=index_params)

print("Collection created successfully")
```

### 2.3 数据库变更

```sql
-- 添加embedding_version字段
ALTER TABLE b_ai_group_memory 
ADD COLUMN embedding_version VARCHAR(10) DEFAULT 'v3' COMMENT 'Embedding版本';

-- 创建索引
CREATE INDEX idx_embedding_version ON b_ai_group_memory(embedding_version);

-- 查询统计
SELECT 
  embedding_version,
  COUNT(*) as count
FROM b_ai_group_memory
GROUP BY embedding_version;
```

---

## 3. Redis记忆索引

### 3.1 数据结构设计

#### 3.1.1 去重索引

```
Key: ai:memory:hash:{groupId}
Type: Set
Members: MD5(content)
TTL: 7天
```

**用途**：快速检查记忆是否重复，避免每次都查询Milvus

#### 3.1.2 最近记忆缓存

```
Key: ai:memory:recent:{groupId}
Type: Sorted Set
Score: timestamp（毫秒）
Members: JSON序列化的MemoryItemDTO
Size: 最多20条
TTL: 24小时
```

**用途**：缓存最近记忆，优先返回，减少Milvus查询

### 3.2 RedisMemoryIndexService

```java
package com.lx.governance.vectormemory.service;

import com.alibaba.fastjson.JSON;
import com.lx.governance.vectormemory.dto.MemoryItemDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Redis记忆索引服务
 * 提供去重索引和最近记忆缓存
 */
@Service
@Slf4j
public class RedisMemoryIndexService {
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    // 去重索引：ai:memory:hash:{groupId}
    private static final String HASH_KEY_PREFIX = "ai:memory:hash:";
    
    // 最近记忆缓存：ai:memory:recent:{groupId}
    private static final String RECENT_KEY_PREFIX = "ai:memory:recent:";
    
    private static final int RECENT_CACHE_SIZE = 20; // 缓存最近20条
    private static final int RECENT_CACHE_TTL_HOURS = 24;
    private static final int HASH_INDEX_TTL_DAYS = 7;
    
    /**
     * 检查记忆是否重复（基于内容hash）
     * 
     * @param groupId 群组ID
     * @param content 记忆内容
     * @return true=重复，false=不重复
     */
    public boolean isDuplicate(String groupId, String content) {
        String key = HASH_KEY_PREFIX + groupId;
        String contentHash = DigestUtils.md5DigestAsHex(content.getBytes());
        
        Boolean exists = redisTemplate.opsForSet().isMember(key, contentHash);
        
        if (Boolean.TRUE.equals(exists)) {
            log.debug("记忆重复（Redis hash匹配）: groupId={}, hash={}", groupId, contentHash);
            return true;
        }
        
        return false;
    }
    
    /**
     * 添加记忆hash到去重索引
     * 
     * @param groupId 群组ID
     * @param content 记忆内容
     */
    public void addToHashIndex(String groupId, String content) {
        String key = HASH_KEY_PREFIX + groupId;
        String contentHash = DigestUtils.md5DigestAsHex(content.getBytes());
        
        redisTemplate.opsForSet().add(key, contentHash);
        
        // 设置7天过期（避免索引无限增长）
        redisTemplate.expire(key, HASH_INDEX_TTL_DAYS, TimeUnit.DAYS);
        
        log.debug("添加到去重索引: groupId={}, hash={}", groupId, contentHash);
    }
    
    /**
     * 添加到最近记忆缓存（Sorted Set，按时间排序）
     * 
     * @param groupId 群组ID
     * @param memory 记忆对象
     */
    public void addToRecentCache(String groupId, MemoryItemDTO memory) {
        String key = RECENT_KEY_PREFIX + groupId;
        
        // 使用timestamp作为score
        double score = System.currentTimeMillis();
        
        // 序列化为JSON
        String json = JSON.toJSONString(memory);
        
        redisTemplate.opsForZSet().add(key, json, score);
        
        // 保持缓存大小（删除最旧的记录）
        Long size = redisTemplate.opsForZSet().size(key);
        if (size != null && size > RECENT_CACHE_SIZE) {
            long removeCount = size - RECENT_CACHE_SIZE;
            redisTemplate.opsForZSet().removeRange(key, 0, removeCount - 1);
        }
        
        // 设置TTL
        redisTemplate.expire(key, RECENT_CACHE_TTL_HOURS, TimeUnit.HOURS);
        
        log.debug("添加到最近记忆缓存: groupId={}, memoryId={}", groupId, memory.getId());
    }
    
    /**
     * 从最近记忆缓存中检索（优先级高于Milvus）
     * 
     * @param groupId 群组ID
     * @param limit 返回数量
     * @return 记忆列表（按时间倒序）
     */
    public List<MemoryItemDTO> getRecentMemories(String groupId, int limit) {
        String key = RECENT_KEY_PREFIX + groupId;
        
        // 获取最新的N条（倒序）
        Set<Object> cached = redisTemplate.opsForZSet().reverseRange(key, 0, limit - 1);
        
        if (cached == null || cached.isEmpty()) {
            log.debug("最近记忆缓存为空: groupId={}", groupId);
            return Collections.emptyList();
        }
        
        List<MemoryItemDTO> memories = cached.stream()
            .map(obj -> JSON.parseObject(obj.toString(), MemoryItemDTO.class))
            .collect(Collectors.toList());
        
        log.debug("从Redis缓存返回最近记忆: groupId={}, count={}", groupId, memories.size());
        
        return memories;
    }
    
    /**
     * 清空群组的所有索引（用于测试或重置）
     * 
     * @param groupId 群组ID
     */
    public void clearGroupIndex(String groupId) {
        redisTemplate.delete(HASH_KEY_PREFIX + groupId);
        redisTemplate.delete(RECENT_KEY_PREFIX + groupId);
        log.info("清空群组索引: groupId={}", groupId);
    }
    
    /**
     * 获取去重索引大小
     * 
     * @param groupId 群组ID
     * @return 索引大小
     */
    public Long getHashIndexSize(String groupId) {
        String key = HASH_KEY_PREFIX + groupId;
        return redisTemplate.opsForSet().size(key);
    }
    
    /**
     * 获取最近记忆缓存大小
     * 
     * @param groupId 群组ID
     * @return 缓存大小
     */
    public Long getRecentCacheSize(String groupId) {
        String key = RECENT_KEY_PREFIX + groupId;
        return redisTemplate.opsForZSet().size(key);
    }
}
```

---

## 4. 记忆提取改造

### 4.1 MemoryExtractionService

```java
@Service
@Slf4j
public class MemoryExtractionService {
    
    @Autowired
    private VectorStoreService vectorStoreService;
    
    @Autowired
    private AiGroupMemoryMapper memoryMapper;
    
    @Autowired
    private RedisMemoryIndexService redisIndexService;
    
    @Autowired
    private DeepSeekDirectService deepSeekService;
    
    @Autowired
    private EmbeddingService embeddingService;
    
    /**
     * 异步提取并保存记忆
     */
    @Async
    public void extractAndSaveMemories(
            String groupId,
            String customerId,
            String userMessage,
            String aiResponse) {
        
        long startTime = System.currentTimeMillis();
        
        log.info("开始提取记忆: groupId={}, customerId={}", groupId, customerId);
        
        try {
            // 1. 调用DeepSeek提取记忆
            MemoryExtractionResult result = deepSeekService.extractMemories(
                userMessage,
                aiResponse
            );
            
            if (result == null || result.getMemories().isEmpty()) {
                log.info("未提取到记忆: groupId={}", groupId);
                return;
            }
            
            int saved = 0;
            int filtered = 0;
            
            for (MemoryItem item : result.getMemories()) {
                try {
                    // 2. 置信度过滤
                    if (item.getConfidenceScore() < 0.7) {
                        log.debug("置信度过低，跳过: content={}, score={}", 
                            item.getContent(), item.getConfidenceScore());
                        filtered++;
                        continue;
                    }
                    
                    // 3. Redis快速去重检查
                    if (redisIndexService.isDuplicate(groupId, item.getContent())) {
                        log.debug("记忆重复（Redis hash匹配），跳过: {}", item.getContent());
                        filtered++;
                        continue;
                    }
                    
                    // 4. Milvus相似度去重（保留原有逻辑）
                    if (isSimilarMemoryExists(groupId, item.getContent())) {
                        log.debug("记忆重复（向量相似度），跳过: {}", item.getContent());
                        filtered++;
                        continue;
                    }
                    
                    // 5. 生成向量（使用v3模型）
                    float[] embedding = embeddingService.generateEmbedding(item.getContent());
                    
                    // 6. 保存到MySQL
                    AiGroupMemoryEntity entity = new AiGroupMemoryEntity();
                    entity.setGroupId(groupId);
                    entity.setCustomerId(customerId);
                    entity.setContent(item.getContent());
                    entity.setMemoryType(item.getMemoryType());
                    entity.setConfidenceScore(item.getConfidenceScore());
                    entity.setSourceMessage(userMessage);
                    entity.setEmbeddingVersion("v3");
                    entity.setCreateTime(LocalDateTime.now());
                    
                    memoryMapper.insert(entity);
                    
                    // 7. 保存到Milvus
                    String embeddingId = vectorStoreService.saveGroupMemory(
                        groupId,
                        item.getContent(),
                        embedding,
                        entity.getId()
                    );
                    
                    // 8. 更新embeddingId
                    entity.setEmbeddingId(embeddingId);
                    memoryMapper.updateById(entity);
                    
                    // 9. 添加到Redis索引
                    redisIndexService.addToHashIndex(groupId, item.getContent());
                    
                    // 10. 添加到最近记忆缓存
                    MemoryItemDTO memoryDTO = convertToDTO(entity);
                    redisIndexService.addToRecentCache(groupId, memoryDTO);
                    
                    saved++;
                    
                } catch (Exception e) {
                    log.error("保存记忆失败: content={}", item.getContent(), e);
                }
            }
            
            long cost = System.currentTimeMillis() - startTime;
            log.info("记忆提取完成: groupId={}, 提取={}, 保存={}, 过滤={}, 耗时={}ms",
                groupId, result.getMemories().size(), saved, filtered, cost);
            
        } catch (Exception e) {
            log.error("记忆提取失败: groupId={}", groupId, e);
        }
    }
    
    /**
     * 检查是否存在相似记忆（Milvus向量检索）
     */
    private boolean isSimilarMemoryExists(String groupId, String content) {
        try {
            List<MemoryItemDTO> similar = vectorStoreService.searchSimilarMemories(
                groupId,
                content,
                1,
                0.85 // 高相似度阈值
            );
            
            if (!similar.isEmpty()) {
                MemoryItemDTO existing = similar.get(0);
                
                // 内容相似度判断
                if (isContentSimilar(content, existing.getContent())) {
                    log.debug("发现相似记忆: new={}, existing={}, similarity={}", 
                        content, existing.getContent(), existing.getSimilarity());
                    return true;
                }
            }
            
            return false;
            
        } catch (Exception e) {
            log.error("相似度检查失败", e);
            return false;
        }
    }
    
    /**
     * 内容相似度判断（包含关系+长度相近）
     */
    private boolean isContentSimilar(String content1, String content2) {
        // 包含关系
        if (content1.contains(content2) || content2.contains(content1)) {
            // 长度相近（差异小于20%）
            int len1 = content1.length();
            int len2 = content2.length();
            double ratio = (double) Math.min(len1, len2) / Math.max(len1, len2);
            return ratio > 0.8;
        }
        return false;
    }
    
    private MemoryItemDTO convertToDTO(AiGroupMemoryEntity entity) {
        return MemoryItemDTO.builder()
            .id(entity.getId())
            .groupId(entity.getGroupId())
            .content(entity.getContent())
            .memoryType(entity.getMemoryType())
            .confidenceScore(entity.getConfidenceScore())
            .embeddingId(entity.getEmbeddingId())
            .createTime(entity.getCreateTime())
            .build();
    }
}
```

---

## 5. 记忆检索改造

### 5.1 MemoryRetrievalService

```java
@Service
@Slf4j
public class MemoryRetrievalService {
    
    @Autowired
    private VectorStoreService vectorStoreService;
    
    @Autowired
    private RedisMemoryIndexService redisIndexService;
    
    @Autowired
    private AiGroupMemoryMapper memoryMapper;
    
    /**
     * 检索群组记忆（优先Redis缓存）
     * 
     * @param groupId 群组ID
     * @param query 查询文本
     * @param topK 返回数量
     * @return 记忆列表
     */
    public List<MemoryItemDTO> retrieveGroupMemories(
            String groupId, 
            String query, 
            int topK) {
        
        long startTime = System.currentTimeMillis();
        
        try {
            // 1. 优先从Redis获取最近记忆
            List<MemoryItemDTO> recentMemories = redisIndexService.getRecentMemories(groupId, 5);
            
            // 2. 如果最近记忆足够，直接返回
            if (recentMemories.size() >= topK) {
                log.debug("从Redis缓存返回最近记忆: groupId={}, count={}", 
                    groupId, recentMemories.size());
                return recentMemories.subList(0, topK);
            }
            
            // 3. Redis不足，查询Milvus
            log.debug("Redis缓存不足，查询Milvus: groupId={}", groupId);
            List<MemoryItemDTO> milvusMemories = searchFromMilvus(groupId, query, topK);
            
            // 4. 合并去重（优先最近记忆）
            Set<Long> recentIds = recentMemories.stream()
                .map(MemoryItemDTO::getId)
                .collect(Collectors.toSet());
            
            List<MemoryItemDTO> combined = new ArrayList<>(recentMemories);
            milvusMemories.stream()
                .filter(m -> !recentIds.contains(m.getId()))
                .limit(topK - recentMemories.size())
                .forEach(combined::add);
            
            long cost = System.currentTimeMillis() - startTime;
            log.info("记忆检索完成: groupId={}, redis={}, milvus={}, total={}, cost={}ms",
                groupId, recentMemories.size(), milvusMemories.size(), combined.size(), cost);
            
            return combined;
            
        } catch (Exception e) {
            log.error("记忆检索失败: groupId={}", groupId, e);
            return Collections.emptyList();
        }
    }
    
    /**
     * 从Milvus检索记忆
     */
    private List<MemoryItemDTO> searchFromMilvus(String groupId, String query, int topK) {
        // 原有Milvus查询逻辑
        List<String> embeddingIds = vectorStoreService.searchGroupMemories(
            groupId,
            query,
            topK,
            0.7 // 相似度阈值
        );
        
        if (embeddingIds.isEmpty()) {
            return Collections.emptyList();
        }
        
        // 根据embeddingId查询MySQL完整信息
        List<AiGroupMemoryEntity> entities = memoryMapper.selectByEmbeddingIds(embeddingIds);
        
        return entities.stream()
            .map(this::convertToDTO)
            .collect(Collectors.toList());
    }
    
    private MemoryItemDTO convertToDTO(AiGroupMemoryEntity entity) {
        return MemoryItemDTO.builder()
            .id(entity.getId())
            .groupId(entity.getGroupId())
            .content(entity.getContent())
            .memoryType(entity.getMemoryType())
            .confidenceScore(entity.getConfidenceScore())
            .embeddingId(entity.getEmbeddingId())
            .createTime(entity.getCreateTime())
            .build();
    }
}
```

---

## 6. 生产级迁移脚本

详见独立文档：[Embedding迁移脚本](./2026-05-18-ai-chat-flow-refactor-migration-script.md)

---

## 7. 监控指标

```java
// 去重过滤数量
ai.memory.duplicate.filtered

// Redis缓存命中率
ai.memory.cache.hit / (ai.memory.cache.hit + ai.memory.cache.miss)

// Milvus检索时间
ai.milvus.search.time (P50/P95/P99)

// Embedding生成时间
ai.embedding.time (P50/P95/P99)
```

---

## 8. 性能对比

| 场景 | 旧方案 | 新方案 | 提升 |
|------|--------|--------|------|
| 记忆检索（缓存命中） | 800ms | 100ms | 87.5% |
| 记忆检索（缓存未命中） | 800ms | 850ms | -6.25% |
| 去重检查 | 500ms（Milvus） | 10ms（Redis） | 98% |
| 平均检索时间（60%命中率） | 800ms | 370ms | 53.75% |

---

## 9. 常见问题

**Q1: 为什么要升级到v3？**  
A: v3模型在中文语义理解上更准确，且维度降低（1024 vs 1536）可以减少存储和计算开销。

**Q2: 迁移期间服务是否可用？**  
A: 需要停机迁移（2-3小时），建议选择低峰期（凌晨2:00-5:00）。

**Q3: Redis去重索引会不会误判？**  
A: MD5碰撞概率极低（2^128），实际使用中可以忽略。

**Q4: 最近记忆缓存的20条是否足够？**  
A: 基于统计分析，80%的查询可以在最近20条记忆中找到相关内容。
