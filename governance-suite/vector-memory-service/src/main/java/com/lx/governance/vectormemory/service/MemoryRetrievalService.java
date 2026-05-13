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

    /**
     * 检索群组记忆
     * 结合向量相似度和数据库完整信息
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

        // Step 1: 调用向量检索
        List<Document> documents = vectorStoreService.searchGroupMemories(query, groupId, customerId, topK);

        if (documents.isEmpty()) {
            log.info("No similar memories found in vector store");
            return new ArrayList<>();
        }

        // Step 2: 提取 embeddingIds
        List<String> embeddingIds = documents.stream()
                .map(Document::getId)
                .collect(Collectors.toList());

        log.debug("Found {} embedding IDs from vector search: {}", embeddingIds.size(), embeddingIds);

        // Step 3: 查询完整信息
        List<AiGroupMemoryEntity> memoryEntities = memoryMapper.selectByEmbeddingIds(embeddingIds);

        if (memoryEntities.isEmpty()) {
            log.warn("No memory entities found in database for embedding IDs: {}", embeddingIds);
            return new ArrayList<>();
        }

        // Step 4: 构建 embeddingId -> similarity 映射
        // similarity = 1.0 - distance (distance 存储在 Document metadata 中)
        Map<String, Double> similarityMap = new HashMap<>();
        for (Document doc : documents) {
            String embeddingId = doc.getId();
            // 从 metadata 中获取 distance，如果不存在则使用默认值 0.0
            Object distanceObj = doc.getMetadata().get("distance");
            double distance = 0.0;
            if (distanceObj instanceof Number) {
                distance = ((Number) distanceObj).doubleValue();
            }
            double similarity = 1.0 - distance;
            similarityMap.put(embeddingId, similarity);
            log.debug("Embedding ID: {}, distance: {}, similarity: {}", embeddingId, distance, similarity);
        }

        // Step 5: 转换为 MemoryItemDTO 列表
        List<MemoryItemDTO> results = memoryEntities.stream()
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

        long elapsedTime = System.currentTimeMillis() - startTime;
        log.info("Memory retrieval completed: found {} memories in {} ms", results.size(), elapsedTime);

        return results;
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
