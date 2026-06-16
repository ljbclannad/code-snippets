package com.lx.governance.aichat.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.lx.governance.aichat.client.VectorMemoryClientFixed;
import com.lx.governance.aichat.config.ContextBudgetProperties;
import com.lx.governance.aichat.dto.MemoryItemDTO;
import com.lx.governance.aichat.dto.StructuredMemoryQuery;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 记忆服务 - 实现分层检索策略
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryService {

    private final VectorMemoryClientFixed vectorMemoryClient;
    private final ContextBudgetProperties properties;

    /**
     * 分层检索记忆
     * 策略：
     * 1. 基于相似度检索（向量搜索）
     * 2. 按相似度阈值过滤
     * 3. 去重（如果启用）
     * 4. 限制数量
     *
     * @param groupId    群组ID
     * @param customerId 客户ID
     * @param query      查询文本
     * @return 记忆列表
     */
    public CompletableFuture<List<MemoryItemDTO>> retrieveMemories(
            String groupId,
            String customerId,
            String query) {
        return retrieveMemories(groupId, customerId, query, null, null, null, null);
    }

    public CompletableFuture<List<MemoryItemDTO>> retrieveMemories(
            String groupId,
            String customerId,
            StructuredMemoryQuery structuredQuery) {

        StructuredMemoryQuery.MemoryFilter filter = structuredQuery != null ? structuredQuery.getFilter() : null;
        return retrieveMemories(
                groupId,
                customerId,
                structuredQuery != null ? structuredQuery.getSemanticQuery() : "",
                filter != null ? filter.getMemoryTypes() : null,
                filter != null ? filter.getMinConfidence() : null,
                filter != null ? filter.getVerifiedOnly() : null,
                filter != null ? filter.getRecentDays() : null,
                structuredQuery);
    }

    public CompletableFuture<List<MemoryItemDTO>> retrieveMemories(
            String groupId,
            String customerId,
            String query,
            Set<String> memoryTypes,
            Double minConfidence,
            Boolean verifiedOnly,
            Integer recentDays) {
        return retrieveMemories(groupId, customerId, query, memoryTypes, minConfidence, verifiedOnly, recentDays, null);
    }

    private CompletableFuture<List<MemoryItemDTO>> retrieveMemories(
            String groupId,
            String customerId,
            String query,
            Set<String> memoryTypes,
            Double minConfidence,
            Boolean verifiedOnly,
            Integer recentDays,
            StructuredMemoryQuery structuredQuery) {
        StructuredMemoryQuery effectiveStructuredQuery = structuredQuery != null
                ? structuredQuery
                : StructuredMemoryQuery.builder()
                        .originalMessage(query)
                        .semanticQuery(query)
                        .filter(StructuredMemoryQuery.MemoryFilter.builder()
                                .memoryTypes(memoryTypes)
                                .minConfidence(minConfidence)
                                .verifiedOnly(verifiedOnly)
                                .recentDays(recentDays)
                                .build())
                        .planSource("LEGACY")
                        .planReason("compatibility_bridge")
                        .build();

        int maxCount = properties.getMemory().getMaxCount();
        double similarityThreshold = properties.getMemory().getSimilarityThreshold();
        boolean enableDeduplication = properties.getMemory().isEnableDeduplication();

        log.info("开始分层检索记忆 - groupId: {}, customerId: {}, maxCount: {}, threshold: {}",
                groupId, customerId, maxCount, similarityThreshold);

        // 1. 向量检索（请求更多数量以便过滤）
        int fetchCount = maxCount * 2;

        return vectorMemoryClient.requestMemories(
                        groupId,
                        customerId,
                        query,
                        fetchCount,
                        memoryTypes,
                        minConfidence,
                        verifiedOnly,
                        recentDays,
                        effectiveStructuredQuery)
                .thenApply(memories -> {
                    log.info("向量检索返回 {} 条记忆", memories.size());

                    // 2. 按相似度过滤（进行二次过滤，避免rerank后还是存在很多不相关文档，底层必须先做分数归一化，不然这层二次过滤会失真）
                    List<MemoryItemDTO> filtered = memories.stream()
                            .filter(m -> m.getSimilarity() != null && m.getSimilarity() >= similarityThreshold)
                            .collect(Collectors.toList());

                    log.info("相似度过滤后剩余 {} 条记忆", filtered.size());

                    // 3. 去重（如果启用）
                    List<MemoryItemDTO> deduplicated = filtered;
                    if (enableDeduplication) {
                        deduplicated = deduplicateMemories(filtered);
                        log.info("去重后剩余 {} 条记忆", deduplicated.size());
                    }

                    // 4. 按相似度排序并限制数量
                    List<MemoryItemDTO> result = deduplicated.stream()
                            .sorted(Comparator.comparing(MemoryItemDTO::getSimilarity).reversed())
                            .limit(maxCount)
                            .collect(Collectors.toList());

                    log.info("最终返回 {} 条记忆", result.size());
                    return result;
                })
                .exceptionally(ex -> {
                    log.error("记忆检索失败 - groupId: {}, customerId: {}", groupId, customerId, ex);
                    return new ArrayList<>();
                });
    }

    /**
     * 去重记忆
     * 策略：基于内容相似度，保留相似度最高的
     */
    private List<MemoryItemDTO> deduplicateMemories(List<MemoryItemDTO> memories) {
        if (memories.isEmpty()) {
            return memories;
        }

        List<MemoryItemDTO> deduplicated = new ArrayList<>();

        for (MemoryItemDTO memory : memories) {
            boolean isDuplicate = false;

            for (MemoryItemDTO existing : deduplicated) {
                if (isSimilarContent(memory.getContent(), existing.getContent())) {
                    isDuplicate = true;
                    // 如果新记忆相似度更高，替换现有记忆
                    if (memory.getSimilarity() > existing.getSimilarity()) {
                        deduplicated.remove(existing);
                        deduplicated.add(memory);
                    }
                    break;
                }
            }

            if (!isDuplicate) {
                deduplicated.add(memory);
            }
        }

        return deduplicated;
    }

    /**
     * 判断两个内容是否相似
     * 策略：完全相同或一个包含另一个（且长度相近）
     */
    private boolean isSimilarContent(String content1, String content2) {
        if (content1 == null || content2 == null) {
            return false;
        }

        // 如果内容完全相同
        if (content1.equals(content2)) {
            return true;
        }

        // 检查长度差异
        int len1 = content1.length();
        int len2 = content2.length();
        int minLen = Math.min(len1, len2);
        int maxLen = Math.max(len1, len2);

        // 长度差异超过 20% 则认为不相似
        if ((maxLen - minLen) * 1.0 / maxLen > 0.2) {
            return false;
        }

        // 检查包含关系（严格：必须一个完全包含另一个）
        return content1.contains(content2) || content2.contains(content1);
    }

    /**
     * 触发记忆提取
     */
    public void extractMemory(String groupId, String customerId, String userMessage, String aiResponse) {
        vectorMemoryClient.triggerMemoryExtraction(groupId, customerId, userMessage, aiResponse);
    }
}
