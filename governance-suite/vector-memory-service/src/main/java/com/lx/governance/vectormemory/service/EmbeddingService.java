package com.lx.governance.vectormemory.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Embedding Service - 文本向量化服务
 * 封装 Spring AI 的 EmbeddingModel，提供文本向量化功能
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private final EmbeddingModel embeddingModel;

    /**
     * 生成单个文本的向量
     *
     * @param text 输入文本
     * @return 向量数组
     */
    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 2000))
    public float[] generateEmbedding(String text) {
        long startTime = System.currentTimeMillis();

        // 参数验证
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException("Text cannot be null or empty");
        }

        try {
            log.debug("Generating embedding for text, length: {}", text.length());

            // 调用 Spring AI EmbeddingModel
            EmbeddingResponse response = embeddingModel.embedForResponse(List.of(text));

            if (response == null || response.getResults().isEmpty()) {
                throw new RuntimeException("Failed to generate embedding: empty response");
            }

            // 提取向量
            float[] vector = response.getResults().get(0).getOutput();

            long duration = System.currentTimeMillis() - startTime;
            log.info("Generated embedding successfully - text_length: {}, vector_dimension: {}, duration_ms: {}",
                    text.length(), vector.length, duration);

            return vector;

        } catch (Exception e) {
            log.error("Failed to generate embedding for text (length: {}): {}", text.length(), e.getMessage(), e);
            throw new RuntimeException("Failed to generate embedding", e);
        }
    }

    /**
     * 批量生成文本向量
     *
     * @param texts 输入文本列表
     * @return 向量列表
     */
    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 2000))
    public List<float[]> generateEmbeddings(List<String> texts) {
        long startTime = System.currentTimeMillis();

        // 参数验证
        if (texts == null || texts.isEmpty()) {
            throw new IllegalArgumentException("Texts list cannot be null or empty");
        }

        // 验证每个文本非空
        for (int i = 0; i < texts.size(); i++) {
            if (!StringUtils.hasText(texts.get(i))) {
                throw new IllegalArgumentException("Text at index " + i + " is null or empty");
            }
        }

        try {
            log.debug("Generating embeddings for {} texts", texts.size());

            // 调用 Spring AI EmbeddingModel
            EmbeddingResponse response = embeddingModel.embedForResponse(texts);

            if (response == null || response.getResults().isEmpty()) {
                throw new RuntimeException("Failed to generate embeddings: empty response");
            }

            // 提取向量列表
            List<float[]> vectors = response.getResults().stream()
                    .map(result -> result.getOutput())
                    .collect(Collectors.toList());

            long duration = System.currentTimeMillis() - startTime;
            log.info("Generated embeddings successfully - text_count: {}, duration_ms: {}",
                    texts.size(), duration);

            return vectors;

        } catch (Exception e) {
            log.error("Failed to generate embeddings for {} texts: {}", texts.size(), e.getMessage(), e);
            throw new RuntimeException("Failed to generate embeddings", e);
        }
    }

    /**
     * 计算两个向量的余弦相似度
     *
     * @param vectorA 向量 A
     * @param vectorB 向量 B
     * @return 相似度值 [-1, 1]，值越大越相似
     */
    public double cosineSimilarity(float[] vectorA, float[] vectorB) {
        // 验证向量维度匹配
        if (vectorA == null || vectorB == null) {
            throw new IllegalArgumentException("Vectors cannot be null");
        }

        if (vectorA.length != vectorB.length) {
            throw new IllegalArgumentException(
                    String.format("Vector dimensions do not match: %d vs %d", vectorA.length, vectorB.length));
        }

        if (vectorA.length == 0) {
            throw new IllegalArgumentException("Vectors cannot be empty");
        }

        try {
            // 计算点积和范数
            double dotProduct = 0.0;
            double normA = 0.0;
            double normB = 0.0;

            for (int i = 0; i < vectorA.length; i++) {
                dotProduct += vectorA[i] * vectorB[i];
                normA += vectorA[i] * vectorA[i];
                normB += vectorB[i] * vectorB[i];
            }

            normA = Math.sqrt(normA);
            normB = Math.sqrt(normB);

            // 避免除零
            if (normA == 0.0 || normB == 0.0) {
                log.warn("One or both vectors have zero norm, returning 0.0 similarity");
                return 0.0;
            }

            // 计算余弦相似度
            double similarity = dotProduct / (normA * normB);

            log.debug("Calculated cosine similarity: {}", similarity);

            return similarity;

        } catch (Exception e) {
            log.error("Failed to calculate cosine similarity: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to calculate cosine similarity", e);
        }
    }
}
