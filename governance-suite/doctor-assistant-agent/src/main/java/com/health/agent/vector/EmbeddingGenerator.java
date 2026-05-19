package com.health.agent.vector;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Embedding 生成器
 *
 * 注意：这是一个简化的实现，使用随机向量模拟
 * 生产环境应该使用真实的 Embedding 模型（如 OpenAI Embeddings、BGE、M3E 等）
 */
@Slf4j
@Component
public class EmbeddingGenerator {

    @Value("${milvus.dimension:768}")
    private int dimension;

    @Value("${embedding.use-mock:true}")
    private boolean useMock;

    private final Random random = new Random(42); // 固定种子，保证可重复性

    /**
     * 生成文本的向量表示
     */
    public List<Float> generateEmbedding(String text) {
        if (useMock) {
            return generateMockEmbedding(text);
        } else {
            // TODO: 集成真实的 Embedding 模型
            // 例如：调用 OpenAI Embeddings API 或本地 BGE 模型
            return generateMockEmbedding(text);
        }
    }

    /**
     * 生成 Mock 向量（用于测试）
     *
     * 使用文本的哈希值作为种子，确保相同文本生成相同向量
     */
    private List<Float> generateMockEmbedding(String text) {
        // 使用文本哈希值作为种子
        Random textRandom = new Random(text.hashCode());

        List<Float> embedding = new ArrayList<>(dimension);
        for (int i = 0; i < dimension; i++) {
            // 生成 [-1, 1] 范围内的随机数
            embedding.add(textRandom.nextFloat() * 2 - 1);
        }

        // L2 归一化
        float norm = 0;
        for (float v : embedding) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);

        List<Float> normalized = new ArrayList<>(dimension);
        for (float v : embedding) {
            normalized.add(v / norm);
        }

        log.debug("生成 Mock Embedding: textLength={}, dimension={}", text.length(), dimension);
        return normalized;
    }

    /**
     * 批量生成向量
     */
    public List<List<Float>> batchGenerateEmbedding(List<String> texts) {
        List<List<Float>> embeddings = new ArrayList<>();
        for (String text : texts) {
            embeddings.add(generateEmbedding(text));
        }
        return embeddings;
    }
}
