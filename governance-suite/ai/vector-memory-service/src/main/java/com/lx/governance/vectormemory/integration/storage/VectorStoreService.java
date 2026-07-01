package com.lx.governance.vectormemory.integration.storage;

import com.lx.governance.vectormemory.integration.ai.EmbeddingService;

import com.lx.governance.vectormemory.entity.AiGroupMemoryEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter.Expression;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 向量存储服务
 * 封装 Spring AI 的 VectorStore，提供向量存储和检索功能
 *
 * @author LeJinBo
 * @since 2026-05-13
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorStoreService {

    private final VectorStore groupMemoryVectorStore;

    @Qualifier("userMemoryVectorStore")
    private final VectorStore userMemoryVectorStore;

    /**
     * 保存群组记忆到 Milvus
     *
     * @param memory 群组记忆实体
     * @return embeddingId 向量数据库中的ID
     */
    @Retryable(maxAttempts = 3)
    public String saveGroupMemory(AiGroupMemoryEntity memory) {
        log.info("Saving group memory to Milvus: groupId={}, customerId={}, memoryType={}",
                memory.getGroupId(), memory.getCustomerId(), memory.getMemoryType());

        // 生成 UUID 作为 embeddingId
        String embeddingId = UUID.randomUUID().toString();
        addGroupMemoryDocument(memory, embeddingId);

        log.info("Group memory saved successfully: embeddingId={}", embeddingId);
        return embeddingId;
    }

    /**
     * 使用指定的 embeddingId 保存群组记忆。
     * 适合作为“写新向量、后续再切换 MySQL 指针”的基础能力。
     */
    @Retryable(maxAttempts = 3)
    public String saveGroupMemory(AiGroupMemoryEntity memory, String embeddingId) {
        log.info("Saving group memory to Milvus with fixed embeddingId: groupId={}, customerId={}, memoryType={}, embeddingId={}",
                memory.getGroupId(), memory.getCustomerId(), memory.getMemoryType(), embeddingId);
        addGroupMemoryDocument(memory, embeddingId);

        log.info("Group memory saved successfully: embeddingId={}", embeddingId);
        return embeddingId;
    }

    private void addGroupMemoryDocument(AiGroupMemoryEntity memory, String embeddingId) {
        
        // 构建 metadata
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("db_id", memory.getId());
        metadata.put("customer_id", memory.getCustomerId());
        metadata.put("group_id", memory.getGroupId());
        metadata.put("memory_type", memory.getMemoryType());
        metadata.put("memory_key", memory.getMemoryKey());
        metadata.put("confidence_score", memory.getConfidenceScore());
        metadata.put("extracted_at", memory.getExtractedAt() != null ? memory.getExtractedAt().toString() : null);

        // 创建 Document
        Document document = new Document(embeddingId, memory.getMemoryContent(), metadata);

        // 调用 VectorStore 保存
        groupMemoryVectorStore.add(List.of(document));
    }

    /**
     * 保存用户记忆到 Milvus
     *
     * @param customerId 用户ID
     * @param content    记忆内容
     * @param dbId       数据库主键ID
     * @return embeddingId 向量数据库中的ID
     */
    @Retryable(maxAttempts = 3)
    public String saveUserMemory(String customerId, String content, Long dbId) {
        log.info("Saving user memory to Milvus: customerId={}, dbId={}", customerId, dbId);

        // 生成 UUID 作为 embeddingId
        String embeddingId = UUID.randomUUID().toString();

        // 构建 metadata
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("db_id", dbId);
        metadata.put("customer_id", customerId);

        // 创建 Document
        Document document = new Document(embeddingId, content, metadata);

        // 调用 VectorStore 保存
        userMemoryVectorStore.add(List.of(document));

        log.info("User memory saved successfully: embeddingId={}", embeddingId);
        return embeddingId;
    }

    /**
     * 检索群组记忆
     *
     * @param query      查询文本
     * @param groupId    群组ID
     * @param customerId 用户ID
     * @param topK       返回结果数量
     * @return 相似文档列表
     */
    public List<Document> searchGroupMemories(String query, String groupId, String customerId, int topK) {
        log.info("Searching group memories: query={}, groupId={}, customerId={}, topK={}",
                query, groupId, customerId, topK);

        // 构建 Filter（group_id 和 customer_id）
        FilterExpressionBuilder filterBuilder = new FilterExpressionBuilder();
        Expression filterExpression = filterBuilder
                .and(
                        filterBuilder.eq("group_id", groupId),
                        filterBuilder.eq("customer_id", customerId)
                )
                .build();

        // 构建 SearchRequest（query, topK, similarityThreshold: 0.7）
        SearchRequest searchRequest = SearchRequest.query(query)
                .withTopK(topK)
                .withSimilarityThreshold(0.7)
                .withFilterExpression(filterExpression);

        // 调用 VectorStore 检索
        List<Document> results = groupMemoryVectorStore.similaritySearch(searchRequest);

        log.info("Group memory search completed: found {} results", results.size());
        return results;
    }

    /**
     * 检索用户记忆
     *
     * @param query      查询文本
     * @param customerId 用户ID
     * @param topK       返回结果数量
     * @return 相似文档列表
     */
    public List<Document> searchUserMemories(String query, String customerId, int topK) {
        log.info("Searching user memories: query={}, customerId={}, topK={}",
                query, customerId, topK);

        // 构建 Filter（customer_id）
        FilterExpressionBuilder filterBuilder = new FilterExpressionBuilder();
        Expression filterExpression = filterBuilder.eq("customer_id", customerId).build();

        // 构建 SearchRequest
        SearchRequest searchRequest = SearchRequest.query(query)
                .withTopK(topK)
                .withSimilarityThreshold(0.7)
                .withFilterExpression(filterExpression);

        // 调用 VectorStore 检索
        List<Document> results = userMemoryVectorStore.similaritySearch(searchRequest);

        log.info("User memory search completed: found {} results", results.size());
        return results;
    }

    /**
     * 删除群组记忆
     *
     * @param embeddingId 向量数据库中的ID
     */
    public void deleteGroupMemory(String embeddingId) {
        log.info("Deleting group memory from Milvus: embeddingId={}", embeddingId);

        groupMemoryVectorStore.delete(List.of(embeddingId));

        log.info("Group memory deleted successfully: embeddingId={}", embeddingId);
    }

    /**
     * 删除用户记忆
     *
     * @param embeddingId 向量数据库中的ID
     */
    public void deleteUserMemory(String embeddingId) {
        log.info("Deleting user memory from Milvus: embeddingId={}", embeddingId);

        userMemoryVectorStore.delete(List.of(embeddingId));

        log.info("User memory deleted successfully: embeddingId={}", embeddingId);
    }
}
