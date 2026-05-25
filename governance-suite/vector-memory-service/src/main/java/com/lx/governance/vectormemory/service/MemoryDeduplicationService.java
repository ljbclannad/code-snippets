package com.lx.governance.vectormemory.service;

import com.lx.governance.vectormemory.config.MemoryRetrievalProperties;
import com.lx.governance.vectormemory.dto.MemoryItemDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 记忆去重服务
 * 实现多种去重策略：ID 去重、语义去重
 *
 * @author LeJinBo
 * @since 2026-05-25
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryDeduplicationService {

    private final MemoryRetrievalProperties properties;
    private final EmbeddingService embeddingService;

    /**
     * 合并并去重记忆列表
     * 应用多种去重策略：ID 去重 -> 语义去重
     *
     * @param redisMemories  Redis 缓存的记忆
     * @param milvusMemories Milvus 检索的记忆
     * @return 去重后的记忆列表
     */
    public List<MemoryItemDTO> mergeAndDeduplicate(List<MemoryItemDTO> redisMemories, List<MemoryItemDTO> milvusMemories) {
        long startTime = System.currentTimeMillis();

        // Step 1: ID 去重（基础去重）
        List<MemoryItemDTO> idDeduped = deduplicateById(redisMemories, milvusMemories);
        log.debug("ID 去重: {} -> {}", redisMemories.size() + milvusMemories.size(), idDeduped.size());

        // Step 2: 语义去重（如果启用）
        List<MemoryItemDTO> finalResult = idDeduped;
        if (properties.getDeduplication().isSemanticEnabled()) {
            finalResult = deduplicateBySemantic(idDeduped);
            log.debug("语义去重: {} -> {}", idDeduped.size(), finalResult.size());
        }

        long elapsedTime = System.currentTimeMillis() - startTime;
        log.info("去重完成: 原始={}, 最终={}, 耗时={}ms",
                redisMemories.size() + milvusMemories.size(), finalResult.size(), elapsedTime);

        return finalResult;
    }

    /**
     * 基于 ID 的去重
     * 如果 ID 重复，保留相似度更高的记忆
     */
    private List<MemoryItemDTO> deduplicateById(List<MemoryItemDTO> redisMemories, List<MemoryItemDTO> milvusMemories) {
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

    /**
     * 基于语义相似度的去重
     * 如果两条记忆的语义相似度 > 阈值，视为重复，保留相似度更高的
     */
    private List<MemoryItemDTO> deduplicateBySemantic(List<MemoryItemDTO> memories) {
        if (memories.size() <= 1) {
            return memories;
        }

        double threshold = properties.getDeduplication().getSemanticThreshold();
        List<MemoryItemDTO> result = new ArrayList<>();
        Set<Integer> removed = new HashSet<>();

        // 按相似度降序排序，优先保留相似度高的
        List<MemoryItemDTO> sorted = memories.stream()
                .sorted(Comparator.comparing(m -> m.getSimilarity() != null ? m.getSimilarity() : 0.0,
                        Comparator.reverseOrder()))
                .collect(Collectors.toList());

        for (int i = 0; i < sorted.size(); i++) {
            if (removed.contains(i)) {
                continue;
            }

            MemoryItemDTO current = sorted.get(i);
            result.add(current);

            // 与后续记忆比较
            for (int j = i + 1; j < sorted.size(); j++) {
                if (removed.contains(j)) {
                    continue;
                }

                MemoryItemDTO other = sorted.get(j);
                double similarity = calculateContentSimilarity(current.getContent(), other.getContent());

                if (similarity >= threshold) {
                    removed.add(j);
                    log.debug("语义去重: 移除相似记忆 (相似度={:.3f}): [{}] vs [{}]",
                            similarity, truncate(current.getContent()), truncate(other.getContent()));
                }
            }
        }

        return result;
    }

    /**
     * 计算两段文本的语义相似度
     * 使用余弦相似度
     */
    private double calculateContentSimilarity(String content1, String content2) {
        if (content1 == null || content2 == null || content1.isEmpty() || content2.isEmpty()) {
            return 0.0;
        }

        // 简单文本相似度：如果内容完全相同，返回 1.0
        if (content1.equals(content2)) {
            return 1.0;
        }

        // 如果内容长度差异过大，直接判定为不相似
        int len1 = content1.length();
        int len2 = content2.length();
        double lengthRatio = (double) Math.min(len1, len2) / Math.max(len1, len2);
        if (lengthRatio < 0.5) {
            return 0.0;
        }

        try {
            // 使用 embedding 计算语义相似度
            float[] embedding1 = embeddingService.generateEmbedding(content1);
            float[] embedding2 = embeddingService.generateEmbedding(content2);
            return embeddingService.cosineSimilarity(embedding1, embedding2);
        } catch (Exception e) {
            log.warn("计算语义相似度失败，使用简单文本相似度: {}", e.getMessage());
            return calculateSimpleTextSimilarity(content1, content2);
        }
    }

    /**
     * 简单文本相似度（基于字符重叠）
     * 作为 embedding 计算失败时的降级方案
     */
    private double calculateSimpleTextSimilarity(String text1, String text2) {
        Set<Character> chars1 = text1.chars().mapToObj(c -> (char) c).collect(Collectors.toSet());
        Set<Character> chars2 = text2.chars().mapToObj(c -> (char) c).collect(Collectors.toSet());

        Set<Character> intersection = new HashSet<>(chars1);
        intersection.retainAll(chars2);

        Set<Character> union = new HashSet<>(chars1);
        union.addAll(chars2);

        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    /**
     * 截断文本用于日志输出
     */
    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 50 ? text.substring(0, 50) + "..." : text;
    }
}
