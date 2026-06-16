package com.lx.governance.vectormemory.service;

import com.lx.governance.vectormemory.config.MemoryRetrievalProperties;
import com.lx.governance.vectormemory.dto.MemorySearchFilter;
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
import java.util.LinkedHashMap;
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
    private final MemoryRerankService rerankService;
    private final MemoryDeduplicationService deduplicationService;
    private final StructuredMemoryFilterMatcher structuredMemoryFilterMatcher;
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
        return retrieveGroupMemories(query, groupId, customerId, topK, null);
    }

    public List<MemoryItemDTO> retrieveGroupMemories(
            String query,
            String groupId,
            String customerId,
            int topK,
            Set<String> memoryTypes,
            Double minConfidence,
            boolean verifiedOnly,
            Integer recentDays) {
        return retrieveGroupMemories(
                query,
                groupId,
                customerId,
                topK,
                MemorySearchFilter.builder()
                        .memoryTypes(memoryTypes)
                        .minConfidence(minConfidence)
                        .verifiedOnly(verifiedOnly)
                        .recentDays(recentDays)
                        .build());
    }

    public List<MemoryItemDTO> retrieveGroupMemories(
            String query,
            String groupId,
            String customerId,
            int topK,
            MemorySearchFilter filter) {
        long startTime = System.currentTimeMillis();
        log.info("Retrieving group memories with hybrid search + rerank: query={}, groupId={}, customerId={}, topK={}",
                query, groupId, customerId, topK);

        // Step 1: 并行检索 Redis、Milvus 和 Keyword（请求更多数量以便 rerank 后筛选）
        int fetchCount = topK * Math.max(1, properties.getHybrid().getFetchMultiplier());

        List<MemoryItemDTO> recentMemories = new ArrayList<>();
        List<MemoryItemDTO> milvusMemories = new ArrayList<>();
        List<MemoryItemDTO> keywordMemories = new ArrayList<>();

        // 1.1 从 Redis 获取最近记忆
        try {
            if (redisMemoryIndexService != null) {
                List<String> redisEntries = redisMemoryIndexService.getRecentMemoryEntries(groupId, fetchCount);
                recentMemories = loadRecentMemoriesFromRedis(redisEntries, filter);
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
                    List<AiGroupMemoryEntity> memoryEntities = shouldPushDownFilter(filter)
                            ? memoryMapper.selectByEmbeddingIdsWithFilter(embeddingIds, filter)
                            : memoryMapper.selectByEmbeddingIds(embeddingIds);

                if (!memoryEntities.isEmpty()) {
                    memoryEntities = applyExpressionFilter(memoryEntities, filter);

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
                                Double similarity = normalizeVectorSimilarity(
                                        similarityMap.getOrDefault(entity.getEmbeddingId(), 0.0));
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

        // 1.3 从 MySQL 获取关键词命中记忆
        if (properties.getHybrid().isKeywordEnabled() && query != null && !query.isBlank()) {
            try {
                keywordMemories = applyExpressionFilter(
                                memoryMapper.selectByKeyword(groupId, customerId, query.trim(), fetchCount, filter),
                                filter).stream()
                        .map(entity -> toKeywordMemoryDto(entity, query))
                        .collect(Collectors.toList());
                log.debug("Retrieved {} keyword memories from MySQL", keywordMemories.size());
            } catch (Exception e) {
                log.warn("Failed to retrieve keyword memories: {}", e.getMessage());
            }
        }

        // Step 2: 合并结果并去重（使用增强去重策略）
        List<MemoryItemDTO> allCandidates = deduplicationService.mergeAndDeduplicate(
                recentMemories,
                milvusMemories,
                keywordMemories);

        if (allCandidates.isEmpty()) {
            log.info("No memories found from Redis, Milvus, or keyword search");
            return new ArrayList<>();
        }

        log.info("Merged candidates: total={} (Redis={}, Milvus={}, Keyword={})",
                allCandidates.size(), recentMemories.size(), milvusMemories.size(), keywordMemories.size());

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

    private boolean shouldPushDownFilter(MemorySearchFilter filter) {
        return filter != null
                && ((filter.getMemoryTypes() != null && !filter.getMemoryTypes().isEmpty())
                || filter.getMinConfidence() != null
                || Boolean.TRUE.equals(filter.getVerifiedOnly())
                || (filter.getRecentDays() != null && filter.getRecentDays() > 0));
    }

    private List<MemoryItemDTO> loadRecentMemoriesFromRedis(List<String> redisEntries, MemorySearchFilter filter) {
        if (redisEntries == null || redisEntries.isEmpty()) {
            return List.of();
        }

        Map<Long, String> contentById = new LinkedHashMap<>();
        for (String entry : redisEntries) {
            try {
                String[] parts = entry.split(":", 2);
                if (parts.length != 2) {
                    log.warn("Unexpected Redis value format: {}", entry);
                    continue;
                }
                contentById.put(Long.parseLong(parts[0]), parts[1]);
            } catch (NumberFormatException e) {
                log.warn("Failed to parse memoryId from Redis value: {}", entry, e);
            }
        }

        if (contentById.isEmpty()) {
            return List.of();
        }

        List<AiGroupMemoryEntity> memoryEntities = shouldPushDownFilter(filter)
                ? memoryMapper.selectByIdsWithFilter(new ArrayList<>(contentById.keySet()), filter)
                : contentById.keySet().stream()
                        .map(memoryMapper::selectById)
                        .filter(entity -> entity != null && entity.getIsDeleted() != null && entity.getIsDeleted() == 0)
                        .collect(Collectors.toList());
        memoryEntities = applyExpressionFilter(memoryEntities, filter);

        return memoryEntities.stream()
                .map(entity -> toRecentMemoryDto(entity, contentById.getOrDefault(entity.getId(), entity.getMemoryContent())))
                .collect(Collectors.toList());
    }

    private MemoryItemDTO toRecentMemoryDto(AiGroupMemoryEntity entity, String content) {
        double confidence = entity.getConfidenceScore() != null ? entity.getConfidenceScore().doubleValue() : 0.0;
        return MemoryItemDTO.builder()
                .id(entity.getId())
                .content(content)
                .similarity(calculateRedisSimilarity(confidence))
                .confidenceScore(entity.getConfidenceScore() != null ? confidence : null)
                .verified(confidence >= 0.8)
                .source("RECENT_CACHE")
                .build();
    }

    private MemoryItemDTO toKeywordMemoryDto(AiGroupMemoryEntity entity, String query) {
        double confidence = entity.getConfidenceScore() != null ? entity.getConfidenceScore().doubleValue() : 0.0;
        return MemoryItemDTO.builder()
                .id(entity.getId())
                .content(entity.getMemoryContent())
                .similarity(calculateKeywordSimilarity(entity, query, confidence))
                .confidenceScore(entity.getConfidenceScore() != null ? confidence : null)
                .verified(confidence >= 0.8)
                .source("KEYWORD")
                .build();
    }

    private double calculateRedisSimilarity(double confidence) {
        MemoryRetrievalProperties.WeightConfig weights = properties.getHybrid().getWeights();
        return clamp01(weights.getRedisBase() + (clamp01(confidence) * weights.getRedisConfidenceBonus()));
    }

    private double calculateKeywordSimilarity(AiGroupMemoryEntity entity, String query, double confidence) {
        MemoryRetrievalProperties.WeightConfig weights = properties.getHybrid().getWeights();
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase();
        String memoryKey = lower(entity.getMemoryKey());
        String memoryType = lower(entity.getMemoryType());
        String content = lower(entity.getMemoryContent());

        double score = weights.getKeywordBase();
        if (!normalizedQuery.isEmpty()) {
            if (memoryKey.equals(normalizedQuery) || content.equals(normalizedQuery)) {
                score += weights.getKeywordTitleBonus();
            }
            if (memoryKey.contains(normalizedQuery) || memoryType.contains(normalizedQuery)) {
                score += weights.getKeywordKeyBonus();
            }
            if (content.contains(normalizedQuery)) {
                score += weights.getKeywordContentBonus();
            }
        }
        score += clamp01(confidence) * weights.getKeywordConfidenceBonus();
        return clamp01(score);
    }

    private double normalizeVectorSimilarity(double similarity) {
        return clamp01(similarity * properties.getHybrid().getWeights().getVectorScale());
    }

    private double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private String lower(String value) {
        return value == null ? "" : value.toLowerCase();
    }

    /**
     * SQL 下推优先解决基础过滤，复杂 DSL 表达式在这里做补充过滤。
     * 这样既保留现有数据库查询效率，也支持 OR / 嵌套 / 文本条件等更灵活的 self-query 表达式。
     */
    private List<AiGroupMemoryEntity> applyExpressionFilter(List<AiGroupMemoryEntity> memoryEntities, MemorySearchFilter filter) {
        if (memoryEntities == null || memoryEntities.isEmpty() || filter == null || filter.getExpression() == null) {
            return memoryEntities;
        }
        return memoryEntities.stream()
                .filter(entity -> structuredMemoryFilterMatcher.matches(entity, filter.getExpression()))
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
