package com.lx.governance.vectormemory.service;

import com.lx.governance.vectormemory.dto.MemoryItemDTO;
import com.lx.governance.vectormemory.entity.AiGroupMemoryEntity;
import com.lx.governance.vectormemory.mapper.AiGroupMemoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 记忆检索服务
 * 整合向量检索和 MySQL 查询，返回完整的记忆信息
 *
 * @author LeJinBo
 * @since 2026-05-13
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryRetrievalService {

    private final VectorStoreService vectorStoreService;
    private final AiGroupMemoryMapper memoryMapper;
    private final RedisMemoryIndexService redisMemoryIndexService;

    /**
     * 检索群组记忆
     * 优先从 Redis 缓存获取最近记忆，不足时再查询 Milvus
     *
     * @param query      查询文本
     * @param groupId    群组ID
     * @param customerId 用户ID
     * @param topK       返回结果数量
     * @return 记忆列表（包含相似度）
     */
    public List<MemoryItemDTO> retrieveGroupMemories(String query, String groupId, String customerId, int topK) {
        long startTime = System.currentTimeMillis();
        log.info("Retrieving group memories: query={}, groupId={}, customerId={}, topK={}",
                query, groupId, customerId, topK);

        // Step 1: 优先从 Redis 获取最近记忆
        List<MemoryItemDTO> recentMemories = new ArrayList<>();
        try {
            if (redisMemoryIndexService != null) {
                List<String> redisValues = redisMemoryIndexService.getRecentMemories(groupId, topK);
                log.debug("Retrieved {} recent memories from Redis", redisValues.size());

                // 解析 Redis 值（格式: "memoryId:content"）
                recentMemories = parseRedisMemories(redisValues);

                // 如果 Redis 返回的记忆数量足够，直接返回
                if (recentMemories.size() >= topK) {
                    log.info("Redis cache hit: returning {} memories without Milvus query", recentMemories.size());
                    long elapsedTime = System.currentTimeMillis() - startTime;
                    log.info("Memory retrieval completed from Redis: found {} memories in {} ms",
                            recentMemories.size(), elapsedTime);
                    return recentMemories;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to retrieve from Redis, falling back to Milvus: {}", e.getMessage());
            recentMemories = new ArrayList<>();
        }

        // Step 2: Redis 记忆不足，查询 Milvus
        log.debug("Redis returned {} memories, querying Milvus for additional results", recentMemories.size());
        List<Document> documents = vectorStoreService.searchGroupMemories(query, groupId, customerId, topK);

        if (documents.isEmpty()) {
            log.info("No similar memories found in vector store");
            // 如果 Milvus 也没有结果，返回 Redis 的结果（如果有）
            if (!recentMemories.isEmpty()) {
                return recentMemories;
            }
            return new ArrayList<>();
        }

        // Step 3: 提取 embeddingIds
        List<String> embeddingIds = documents.stream()
                .map(Document::getId)
                .collect(Collectors.toList());

        log.debug("Found {} embedding IDs from vector search: {}", embeddingIds.size(), embeddingIds);

        // Step 4: 查询完整信息
        List<AiGroupMemoryEntity> memoryEntities = memoryMapper.selectByEmbeddingIds(embeddingIds);

        if (memoryEntities.isEmpty()) {
            log.warn("No memory entities found in database for embedding IDs: {}", embeddingIds);
            return new ArrayList<>();
        }

        // Step 5: 构建 embeddingId -> similarity 映射
        Map<String, Double> similarityMap = new HashMap<>();
        for (Document doc : documents) {
            String embeddingId = doc.getId();
            Object distanceObj = doc.getMetadata().get("distance");
            double distance = 0.0;
            if (distanceObj instanceof Number) {
                distance = ((Number) distanceObj).doubleValue();
            }
            double similarity = 1.0 - distance;
            similarityMap.put(embeddingId, similarity);
            log.debug("Embedding ID: {}, distance: {}, similarity: {}", embeddingId, distance, similarity);
        }

        // Step 6: 转换为 MemoryItemDTO 列表
        List<MemoryItemDTO> milvusResults = memoryEntities.stream()
                .map(entity -> {
                    Double similarity = similarityMap.getOrDefault(entity.getEmbeddingId(), 0.0);
                    return MemoryItemDTO.builder()
                            .id(entity.getId())
                            .content(entity.getMemoryContent())
                            .similarity(similarity)
                            .confidenceScore(entity.getConfidenceScore() != null ?
                                    entity.getConfidenceScore().doubleValue() : null)
                            .verified(entity.getConfidenceScore() != null &&
                                    entity.getConfidenceScore().doubleValue() >= 0.8)
                            .source(entity.getMemoryType())
                            .build();
                })
                .collect(Collectors.toList());

        // Step 7: 合并 Redis 和 Milvus 结果（去重）
        List<MemoryItemDTO> finalResults = new ArrayList<>();

        // 收集 Redis 缓存的记忆 ID（用于去重）
        Set<Long> redisMemoryIds = recentMemories.stream()
                .map(MemoryItemDTO::getId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());

        // 先添加 Redis 缓存的记忆
        finalResults.addAll(recentMemories);

        // 再添加 Milvus 的记忆（去重：排除 Redis 中已存在的记忆 ID）
        for (MemoryItemDTO milvusItem : milvusResults) {
            if (milvusItem.getId() == null || !redisMemoryIds.contains(milvusItem.getId())) {
                finalResults.add(milvusItem);
            }
        }

        // 限制返回数量
        if (finalResults.size() > topK) {
            finalResults = finalResults.subList(0, topK);
        }

        long elapsedTime = System.currentTimeMillis() - startTime;
        log.info("Memory retrieval completed: found {} memories ({} from Redis, {} from Milvus) in {} ms",
                finalResults.size(), recentMemories.size(),
                finalResults.size() - recentMemories.size(), elapsedTime);

        return finalResults;
    }

    /**
     * 解析 Redis 记忆值
     * Redis 格式: "memoryId:content"
     *
     * @param redisValues Redis 返回的原始值列表
     * @return 解析后的 MemoryItemDTO 列表
     */
    private List<MemoryItemDTO> parseRedisMemories(List<String> redisValues) {
        return redisValues.stream()
                .map(value -> {
                    try {
                        // 解析格式: "memoryId:content"
                        String[] parts = value.split(":", 2);
                        if (parts.length == 2) {
                            Long memoryId = Long.parseLong(parts[0]);
                            String content = parts[1];
                            return MemoryItemDTO.builder()
                                    .id(memoryId)
                                    .content(content)
                                    .similarity(0.0) // 0.0 表示未计算相似度（来自最近缓存）
                                    .source("RECENT_CACHE")
                                    .build();
                        } else {
                            // 降级处理：如果格式不符合预期，仅使用 content
                            log.warn("Unexpected Redis value format: {}", value);
                            return MemoryItemDTO.builder()
                                    .content(value)
                                    .similarity(0.0)
                                    .source("RECENT_CACHE")
                                    .build();
                        }
                    } catch (NumberFormatException e) {
                        log.warn("Failed to parse memoryId from Redis value: {}", value, e);
                        return MemoryItemDTO.builder()
                                .content(value)
                                .similarity(0.0)
                                .source("RECENT_CACHE")
                                .build();
                    }
                })
                .collect(Collectors.toList());
    }

    /**
     * 检查是否存在相似记忆（去重）
     *
     * @param groupId    群组ID
     * @param customerId 用户ID
     * @param content    记忆内容
     * @return true 如果存在相似记忆
     */
    public boolean hasSimilarMemory(String groupId, String customerId, String content) {
        log.debug("Checking for similar memory: groupId={}, customerId={}, content length={}",
                groupId, customerId, content != null ? content.length() : 0);

        int count = memoryMapper.countSimilarMemory(groupId, customerId, content);
        boolean hasSimilar = count > 0;

        log.debug("Similar memory check result: count={}, hasSimilar={}", count, hasSimilar);
        return hasSimilar;
    }
}
