package com.lx.governance.vectormemory.service;

import com.lx.governance.vectormemory.config.MemoryRetrievalProperties;
import com.lx.governance.vectormemory.dto.MemoryItemDTO;
import com.lx.governance.vectormemory.entity.AiGroupMemoryEntity;
import com.lx.governance.vectormemory.mapper.AiGroupMemoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final MemoryRerankService rerankService;
    private final MemoryDeduplicationService deduplicationService;
    private final MemoryRetrievalProperties properties;

    /**
     * 检索群组记忆（混合检索 + Rerank）
     * 策略：
     * 1. 并行检索 Redis（最近记忆）和 Milvus（语义相似）
     * 2. 合并结果并去重
     * 3. 使用 Rerank 基于语义相关性重新排序
     * 4. 返回 topK 结果
     *
     * @param query      查询文本
     * @param groupId    群组ID
     * @param customerId 用户ID
     * @param topK       返回结果数量
     * @return 记忆列表（包含相似度）
     */
    public List<MemoryItemDTO> retrieveGroupMemories(String query, String groupId, String customerId, int topK) {
        long startTime = System.currentTimeMillis();
        log.info("Retrieving group memories with hybrid search + rerank: query={}, groupId={}, customerId={}, topK={}",
                query, groupId, customerId, topK);

        // Step 1: 并行检索 Redis 和 Milvus（请求 2 倍数量以便 rerank 后筛选）
        int fetchCount = topK * 2;

        List<MemoryItemDTO> recentMemories = new ArrayList<>();
        List<MemoryItemDTO> milvusMemories = new ArrayList<>();

        // 1.1 从 Redis 获取最近记忆
        try {
            if (redisMemoryIndexService != null) {
                List<String> redisValues = redisMemoryIndexService.getRecentMemories(groupId, fetchCount);
                recentMemories = parseRedisMemories(redisValues);
                log.debug("Retrieved {} recent memories from Redis", recentMemories.size());
            }
        } catch (Exception e) {
            log.warn("Failed to retrieve from Redis: {}", e.getMessage());
        }

        // 1.2 从 Milvus 获取语义相似记忆
        try {
            List<Document> documents = vectorStoreService.searchGroupMemories(query, groupId, customerId, fetchCount);

            if (!documents.isEmpty()) {
                // 提取 embeddingIds
                List<String> embeddingIds = documents.stream()
                        .map(Document::getId)
                        .collect(Collectors.toList());

                log.debug("Found {} embedding IDs from Milvus", embeddingIds.size());

                // 查询完整信息
                List<AiGroupMemoryEntity> memoryEntities = memoryMapper.selectByEmbeddingIds(embeddingIds);

                if (!memoryEntities.isEmpty()) {
                    // 构建 embeddingId -> similarity 映射
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
                    }

                    // 转换为 MemoryItemDTO
                    milvusMemories = memoryEntities.stream()
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

                    log.debug("Retrieved {} memories from Milvus", milvusMemories.size());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to retrieve from Milvus: {}", e.getMessage());
        }

        // Step 2: 合并结果并去重（使用增强去重策略）
        List<MemoryItemDTO> allCandidates = deduplicationService.mergeAndDeduplicate(recentMemories, milvusMemories);

        if (allCandidates.isEmpty()) {
            log.info("No memories found from both Redis and Milvus");
            return new ArrayList<>();
        }

        log.info("Merged candidates: total={} (Redis={}, Milvus={})",
                allCandidates.size(), recentMemories.size(), milvusMemories.size());

        // Step 3: Rerank（基于语义相关性重新排序）
        List<MemoryItemDTO> finalResults;

        if (properties.getRerank().isEnabled()) {
            String strategy = properties.getRerank().getStrategy();
            log.info("Rerank enabled with strategy: {}", strategy);

            if ("full".equalsIgnoreCase(strategy)) {
                // 全量重排序：对所有候选记忆重新计算相似度
                finalResults = rerankService.rerank(query, allCandidates, topK);
            } else {
                // 部分重排序（默认）：仅对 Redis 缓存记忆重新计算相似度
                finalResults = rerankService.rerankPartial(query, allCandidates, topK);
            }
        } else {
            // 不启用 Rerank，直接按现有相似度排序
            log.info("Rerank disabled, sorting by existing similarity scores");
            finalResults = allCandidates.stream()
                    .sorted(Comparator.comparing(m -> m.getSimilarity() != null ? m.getSimilarity() : 0.0,
                            Comparator.reverseOrder()))
                    .limit(topK)
                    .collect(Collectors.toList());
        }

        long elapsedTime = System.currentTimeMillis() - startTime;
        log.info("Memory retrieval completed: candidates={}, final={}, duration={}ms",
                allCandidates.size(), finalResults.size(), elapsedTime);

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
