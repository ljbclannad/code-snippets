package com.lx.governance.vectormemory.domain.retrieval;

import com.lx.governance.vectormemory.integration.ai.BgeRerankClient;
import com.lx.governance.vectormemory.integration.ai.EmbeddingService;

import com.lx.governance.vectormemory.config.MemoryRetrievalProperties;
import com.lx.governance.vectormemory.dto.MemoryItemDTO;
import com.lx.governance.vectormemory.enums.RetrievalSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 记忆重排序服务
 * 基于语义相关性对检索结果进行重新排序
 *
 * @author LeJinBo
 * @since 2026-05-25
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryRerankService {

    private final EmbeddingService embeddingService;
    private final BgeRerankClient bgeRerankClient;
    private final MemoryRetrievalProperties properties;

    /**
     * 对记忆列表进行重排序
     * 策略：计算每条记忆与查询的语义相似度，按相似度降序排序
     *
     * @param query     查询文本
     * @param memories  候选记忆列表
     * @param topK      返回的最终数量
     * @return 重排序后的记忆列表
     */
    public List<MemoryItemDTO> rerank(String query, List<MemoryItemDTO> memories, int topK) {
        return rerank(query, memories, topK, Map.of());
    }

    public List<MemoryItemDTO> rerank(String query, List<MemoryItemDTO> memories, int topK,
                                      Map<String, float[]> embeddingCache) {
        if (useExternalBgeProvider()) {
            try {
                return bgeRerankClient.rerank(query, memories, topK);
            } catch (Exception e) {
                log.error("External BGE rerank failed: {}", e.getMessage(), e);
                if (!properties.getRerank().isFallbackToEmbedding()) {
                    return memories.stream().limit(topK).collect(Collectors.toList());
                }
                log.warn("Falling back to embedding-based rerank");
            }
        }

        return rerankWithEmbeddings(query, memories, topK, embeddingCache);
    }

    private List<MemoryItemDTO> rerankWithEmbeddings(String query, List<MemoryItemDTO> memories, int topK,
                                                     Map<String, float[]> embeddingCache) {
        long startTime = System.currentTimeMillis();

        if (memories == null || memories.isEmpty()) {
            log.debug("No memories to rerank");
            return new ArrayList<>();
        }

        if (memories.size() <= topK) {
            log.debug("Memory count ({}) <= topK ({}), reranking all", memories.size(), topK);
        }

        try {
            log.info("Starting rerank: query length={}, candidate count={}, topK={}",
                    query.length(), memories.size(), topK);

            // Step 1: 生成查询向量
            float[] queryVector = embeddingService.generateEmbedding(query);
            log.debug("Generated query embedding: dimension={}", queryVector.length);

            // Step 2: 批量生成记忆内容向量
            List<String> memoryContents = memories.stream()
                    .map(MemoryItemDTO::getContent)
                    .collect(Collectors.toList());

            List<float[]> memoryVectors = resolveMemoryVectors(memoryContents, embeddingCache);
            log.debug("Generated {} memory embeddings", memoryVectors.size());

            // Step 3: 计算每条记忆与查询的相似度
            for (int i = 0; i < memories.size(); i++) {
                MemoryItemDTO memory = memories.get(i);
                float[] memoryVector = memoryVectors.get(i);

                // 计算余弦相似度
                double rerankScore = embeddingService.cosineSimilarity(queryVector, memoryVector);

                // 更新相似度分数（覆盖原有的 Milvus 相似度或 Redis 的 0.0）
                memory.setSimilarity(rerankScore);

                log.debug("Memory[{}] rerank score: {} (original: {})",
                        i, rerankScore, memory.getSimilarity());
            }

            // Step 4: 按重排序分数降序排序
            List<MemoryItemDTO> reranked = memories.stream()
                    .sorted(Comparator.comparing(MemoryItemDTO::getSimilarity).reversed())
                    .limit(topK)
                    .collect(Collectors.toList());

            long duration = System.currentTimeMillis() - startTime;
            log.info("Rerank completed: input={}, output={}, duration={}ms",
                    memories.size(), reranked.size(), duration);

            // 打印 top 3 的相似度分数（用于调试）
            if (log.isInfoEnabled() && !reranked.isEmpty()) {
                int displayCount = Math.min(3, reranked.size());
                StringBuilder sb = new StringBuilder("Top ").append(displayCount).append(" rerank scores: ");
                for (int i = 0; i < displayCount; i++) {
                    sb.append(String.format("%.4f", reranked.get(i).getSimilarity()));
                    if (i < displayCount - 1) sb.append(", ");
                }
                log.info(sb.toString());
            }

            return reranked;

        } catch (Exception e) {
            log.error("Rerank failed, returning original order: {}", e.getMessage(), e);
            // 降级：返回原始顺序的 topK 结果
            return memories.stream()
                    .limit(topK)
                    .collect(Collectors.toList());
        }
    }

    /**
     * 简化版重排序：仅对相似度为 0.0 的记忆（来自 Redis 缓存）进行打分
     * 其他记忆保留 Milvus 的相似度分数
     *
     * @param query     查询文本
     * @param memories  候选记忆列表
     * @param topK      返回的最终数量
     * @return 重排序后的记忆列表
     */
    public List<MemoryItemDTO> rerankPartial(String query, List<MemoryItemDTO> memories, int topK) {
        return rerankPartial(query, memories, topK, Map.of());
    }

    public List<MemoryItemDTO> rerankPartial(String query, List<MemoryItemDTO> memories, int topK,
                                             Map<String, float[]> embeddingCache) {
        if (useExternalBgeProvider()) {
            try {
                return rerankPartialWithExternalProvider(query, memories, topK);
            } catch (Exception e) {
                log.error("External partial BGE rerank failed: {}", e.getMessage(), e);
                if (!properties.getRerank().isFallbackToEmbedding()) {
                    return memories.stream().limit(topK).collect(Collectors.toList());
                }
                log.warn("Falling back to embedding-based partial rerank");
            }
        }

        return rerankPartialWithEmbeddings(query, memories, topK, embeddingCache);
    }

    private List<MemoryItemDTO> rerankPartialWithEmbeddings(String query, List<MemoryItemDTO> memories, int topK,
                                                            Map<String, float[]> embeddingCache) {
        long startTime = System.currentTimeMillis();

        if (memories == null || memories.isEmpty()) {
            return new ArrayList<>();
        }

        try {
            // partial 策略按来源挑选候选，而不是依赖 similarity 是否为空。
            // RECENT_CACHE 的初始分数只是启发式时间/置信度分，需要重新映射到当前 query 的语义坐标系。
            List<MemoryItemDTO> needRerank = memories.stream()
                    .filter(this::shouldRerankInPartialMode)
                    .collect(Collectors.toList());

            if (needRerank.isEmpty()) {
                log.debug("No memories need partial reranking");
                return memories.stream()
                        .sorted(Comparator.comparing(MemoryItemDTO::getSimilarity).reversed())
                        .limit(topK)
                        .collect(Collectors.toList());
            }

            log.info("Partial rerank: total={}, need_rerank={}", memories.size(), needRerank.size());

            // 生成查询向量
            float[] queryVector = embeddingService.generateEmbedding(query);

            // 批量生成需要重排序的记忆向量
            List<String> contents = needRerank.stream()
                    .map(MemoryItemDTO::getContent)
                    .collect(Collectors.toList());

            List<float[]> vectors = resolveMemoryVectors(contents, embeddingCache);

            // 计算相似度
            for (int i = 0; i < needRerank.size(); i++) {
                double score = embeddingService.cosineSimilarity(queryVector, vectors.get(i));
                needRerank.get(i).setSimilarity(score);
            }

            // 排序并返回 topK
            List<MemoryItemDTO> result = memories.stream()
                    .sorted(Comparator.comparing(MemoryItemDTO::getSimilarity).reversed())
                    .limit(topK)
                    .collect(Collectors.toList());

            long duration = System.currentTimeMillis() - startTime;
            log.info("Partial rerank completed: reranked={}, duration={}ms", needRerank.size(), duration);

            return result;

        } catch (Exception e) {
            log.error("Partial rerank failed: {}", e.getMessage(), e);
            return memories.stream().limit(topK).collect(Collectors.toList());
        }
    }

    private List<MemoryItemDTO> rerankPartialWithExternalProvider(String query, List<MemoryItemDTO> memories, int topK) {
        if (memories == null || memories.isEmpty()) {
            return new ArrayList<>();
        }

        List<MemoryItemDTO> needRerank = memories.stream()
                .filter(this::shouldRerankInPartialMode)
                .collect(Collectors.toList());

        if (needRerank.isEmpty()) {
            return memories.stream()
                    .sorted(Comparator.comparing(MemoryItemDTO::getSimilarity).reversed())
                    .limit(topK)
                    .collect(Collectors.toList());
        }

        List<MemoryItemDTO> rescored = bgeRerankClient.rerank(query, needRerank, needRerank.size());
        Map<Long, Double> similarityById = rescored.stream()
                .filter(memory -> memory.getId() != null && memory.getSimilarity() != null)
                .collect(Collectors.toMap(MemoryItemDTO::getId, MemoryItemDTO::getSimilarity, (left, right) -> right));

        for (MemoryItemDTO memory : needRerank) {
            if (memory.getId() == null) {
                continue;
            }
            Double similarity = similarityById.get(memory.getId());
            if (similarity != null) {
                memory.setSimilarity(similarity);
            }
        }

        return memories.stream()
                .sorted(Comparator.comparing(MemoryItemDTO::getSimilarity).reversed())
                .limit(topK)
                .collect(Collectors.toList());
    }

    private boolean shouldRerankInPartialMode(MemoryItemDTO memory) {
        return memory != null
                && RetrievalSource.RECENT_CACHE.name().equalsIgnoreCase(memory.getSource());
    }

    private boolean useExternalBgeProvider() {
        String provider = properties.getRerank().getProvider();
        return "bge-http".equalsIgnoreCase(provider);
    }

    private List<float[]> resolveMemoryVectors(List<String> memoryContents, Map<String, float[]> embeddingCache) {
        Map<String, float[]> reusableCache = embeddingCache != null
                ? new HashMap<>(embeddingCache)
                : new HashMap<>();
        List<float[]> vectors = new ArrayList<>(memoryContents.size());
        List<String> missingContents = new ArrayList<>();

        for (String content : memoryContents) {
            float[] cached = reusableCache.get(content);
            if (cached != null) {
                vectors.add(cached);
            } else {
                vectors.add(null);
                missingContents.add(content);
            }
        }

        if (!missingContents.isEmpty()) {
            List<float[]> generated = embeddingService.generateEmbeddings(missingContents);
            int generatedIndex = 0;
            for (int i = 0; i < memoryContents.size(); i++) {
                if (vectors.get(i) != null) {
                    continue;
                }
                float[] vector = generated.get(generatedIndex++);
                String content = memoryContents.get(i);
                vectors.set(i, vector);
                reusableCache.put(content, vector);
            }
        }

        return vectors;
    }
}
