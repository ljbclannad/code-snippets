package com.lx.governance.vectormemory.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.lx.governance.vectormemory.config.MemoryRetrievalProperties;
import com.lx.governance.vectormemory.dto.MemoryItemDTO;
import com.lx.governance.vectormemory.enums.RetrievalSource;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
    public List<MemoryItemDTO> mergeAndDeduplicate(
            List<MemoryItemDTO> redisMemories,
            List<MemoryItemDTO> milvusMemories,
            List<MemoryItemDTO> keywordMemories) {
        return mergeAndDeduplicateWithCache(redisMemories, milvusMemories, keywordMemories).memories();
    }

    public DeduplicationResult mergeAndDeduplicateWithCache(
            List<MemoryItemDTO> redisMemories,
            List<MemoryItemDTO> milvusMemories,
            List<MemoryItemDTO> keywordMemories) {
        long startTime = System.currentTimeMillis();

        // Step 1: ID 去重（基础去重）
        List<MemoryItemDTO> idDeduped = deduplicateById(redisMemories, milvusMemories, keywordMemories);
        log.debug("ID 去重: {} -> {}",
                redisMemories.size() + milvusMemories.size() + keywordMemories.size(), idDeduped.size());

        // Step 2: 语义去重（如果启用）
        List<MemoryItemDTO> finalResult = idDeduped;
        Map<String, float[]> embeddingCache = new HashMap<>();
        if (properties.getDeduplication().isSemanticEnabled()) {
            embeddingCache = prepareEmbeddingCache(idDeduped);
            finalResult = deduplicateBySemantic(idDeduped, embeddingCache);
            log.debug("语义去重: {} -> {}", idDeduped.size(), finalResult.size());
        }

        long elapsedTime = System.currentTimeMillis() - startTime;
        log.info("去重完成: 原始={}, 最终={}, 耗时={}ms",
                redisMemories.size() + milvusMemories.size() + keywordMemories.size(), finalResult.size(), elapsedTime);

        return new DeduplicationResult(finalResult, embeddingCache);
    }

    /**
     * 基于 ID 的去重
     * 如果 ID 重复，保留相似度更高的记忆
     */
    private List<MemoryItemDTO> deduplicateById(
            List<MemoryItemDTO> redisMemories,
            List<MemoryItemDTO> milvusMemories,
            List<MemoryItemDTO> keywordMemories) {
        Map<Long, MemoryItemDTO> memoryMap = new HashMap<>();

        addOrReplace(memoryMap, redisMemories);
        addOrReplace(memoryMap, milvusMemories);
        addOrReplace(memoryMap, keywordMemories);

        return new ArrayList<>(memoryMap.values());
    }

    private void addOrReplace(Map<Long, MemoryItemDTO> memoryMap, List<MemoryItemDTO> memories) {
        for (MemoryItemDTO memory : memories) {
            if (memory.getId() == null) {
                continue;
            }
            MemoryItemDTO existing = memoryMap.get(memory.getId());
            if (existing == null) {
                memoryMap.put(memory.getId(), memory);
                continue;
            }

            // 同一个 memoryId 出现多次时，先按来源统一归一化分数，再保留更高的那条
            double existingSimilarity = normalizeSimilarity(existing);
            double newSimilarity = normalizeSimilarity(memory);
            if (newSimilarity > existingSimilarity) {
                memoryMap.put(memory.getId(), memory);
            }
        }
    }

    /**
     * 对不同召回源的相似度做统一口径归一化。
     * RECENT_CACHE / KEYWORD 在进入 DTO 前已按各自规则映射到 [0,1]；
     * 其他来源默认视为向量召回结果，应用向量缩放系数后再比较。
     */
    private double normalizeSimilarity(MemoryItemDTO memory) {
        if (memory == null || memory.getSimilarity() == null) {
            return 0.0;
        }

        double similarity = clamp01(memory.getSimilarity());
        String source = memory.getSource();

        if (RetrievalSource.RECENT_CACHE.name().equalsIgnoreCase(source)
                || RetrievalSource.KEYWORD.name().equalsIgnoreCase(source)) {
            return similarity;
        }

        return clamp01(similarity * properties.getHybrid().getWeights().getVectorScale());
    }

    private double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    /**
     * 基于语义相似度的去重
     * 如果两条记忆的语义相似度 > 阈值，视为重复，保留相似度更高的
     */
    private List<MemoryItemDTO> deduplicateBySemantic(List<MemoryItemDTO> memories, Map<String, float[]> embeddingCache) {
        if (memories.size() <= 1) {
            return memories;
        }

        double threshold = properties.getDeduplication().getSemanticThreshold();
        List<MemoryItemDTO> result = new ArrayList<>();

        // 按相似度降序排序，优先保留相似度高的
        List<MemoryItemDTO> sorted = memories.stream()
                .sorted(Comparator.comparing(m -> m.getSimilarity() != null ? m.getSimilarity() : 0.0,
                        Comparator.reverseOrder()))
                .collect(Collectors.toList());
        boolean[] removed = new boolean[sorted.size()];
        int[] contentLengths = buildContentLengths(sorted);
        NavigableMap<Integer, List<Integer>> lengthIndex = buildLengthIndex(contentLengths);

        for (int i = 0; i < sorted.size(); i++) {
            if (removed[i]) {
                continue;
            }

            MemoryItemDTO current = sorted.get(i);
            result.add(current);
            int currentLength = contentLengths[i];

            // 与后续记忆比较
            for (int j : collectCandidateIndices(lengthIndex, currentLength, i)) {
                if (removed[j]) {
                    continue;
                }

                MemoryItemDTO other = sorted.get(j);
                double similarity = calculateContentSimilarity(
                        current.getContent(), other.getContent(), embeddingCache);

                if (similarity >= threshold) {
                    removed[j] = true;
                    log.debug("语义去重: 移除相似记忆 (相似度={:.3f}): [{}] vs [{}]",
                            similarity, truncate(current.getContent()), truncate(other.getContent()));
                }
            }
        }

        return result;
    }

    private int[] buildContentLengths(List<MemoryItemDTO> memories) {
        int[] lengths = new int[memories.size()];
        for (int i = 0; i < memories.size(); i++) {
            String content = memories.get(i).getContent();
            lengths[i] = content != null ? content.length() : 0;
        }
        return lengths;
    }

    private NavigableMap<Integer, List<Integer>> buildLengthIndex(int[] contentLengths) {
        NavigableMap<Integer, List<Integer>> lengthIndex = new TreeMap<>();
        for (int i = 0; i < contentLengths.length; i++) {
            lengthIndex.computeIfAbsent(contentLengths[i], key -> new ArrayList<>()).add(i);
        }
        return lengthIndex;
    }

    /**
     * 仅收集长度可能通过快速剪枝的候选，避免扫描整个后缀区间。
     */
    private List<Integer> collectCandidateIndices(
            NavigableMap<Integer, List<Integer>> lengthIndex,
            int currentLength,
            int currentIndex) {
        if (currentLength <= 0) {
            return List.of();
        }

        int minLength = (int) Math.ceil(currentLength * 0.5d);
        int maxLength = currentLength * 2;
        List<Integer> candidates = new ArrayList<>();

        for (List<Integer> indices : lengthIndex.subMap(minLength, true, maxLength, true).values()) {
            for (int index : indices) {
                if (index > currentIndex) {
                    candidates.add(index);
                }
            }
        }

        return candidates;
    }

    /**
     * 为本次语义去重批量预生成 embedding，避免在两两比较时重复远程调用。
     */
    private Map<String, float[]> prepareEmbeddingCache(List<MemoryItemDTO> memories) {
        Map<String, float[]> embeddingCache = new HashMap<>();

        List<String> uniqueContents = memories.stream()
                .map(MemoryItemDTO::getContent)
                .filter(content -> content != null && !content.isEmpty())
                .distinct()
                .collect(Collectors.toList());

        if (uniqueContents.size() <= 1) {
            return embeddingCache;
        }

        try {
            List<float[]> embeddings = embeddingService.generateEmbeddings(uniqueContents);
            for (int i = 0; i < uniqueContents.size(); i++) {
                embeddingCache.put(uniqueContents.get(i), embeddings.get(i));
            }
            log.debug("语义去重预生成 embedding 完成: contentCount={}", embeddingCache.size());
        } catch (Exception e) {
            log.warn("批量生成 embedding 失败，将在比较时降级: {}", e.getMessage());
        }

        return embeddingCache;
    }

    /**
     * 计算两段文本的语义相似度
     * 使用余弦相似度
     */
    private double calculateContentSimilarity(String content1, String content2, Map<String, float[]> embeddingCache) {
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
            // 优先复用本次去重阶段批量生成的 embedding，避免重复远程调用
            float[] embedding1 = resolveEmbedding(content1, embeddingCache);
            float[] embedding2 = resolveEmbedding(content2, embeddingCache);
            return embeddingService.cosineSimilarity(embedding1, embedding2);
        } catch (Exception e) {
            log.warn("计算语义相似度失败，使用简单文本相似度: {}", e.getMessage());
            return calculateSimpleTextSimilarity(content1, content2);
        }
    }

    private float[] resolveEmbedding(String content, Map<String, float[]> embeddingCache) {
        if (embeddingCache != null) {
            float[] cached = embeddingCache.get(content);
            if (cached != null) {
                return cached;
            }
        }

        float[] embedding = embeddingService.generateEmbedding(content);
        if (embeddingCache != null) {
            embeddingCache.put(content, embedding);
        }
        return embedding;
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

    public record DeduplicationResult(
            List<MemoryItemDTO> memories,
            Map<String, float[]> embeddingCache) {
    }
}
