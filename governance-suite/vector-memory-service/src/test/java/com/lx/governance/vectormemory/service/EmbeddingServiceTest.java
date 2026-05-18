package com.lx.governance.vectormemory.service;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EmbeddingService 单元测试
 * 测试文本向量生成和余弦相似度计算功能
 *
 * @author LeJinBo
 * @since 2026-05-13
 */
@Slf4j
@SpringBootTest
class EmbeddingServiceTest {

    @Autowired
    private EmbeddingService embeddingService;

    /**
     * 测试单个文本向量生成
     */
    @Test
    void testGenerateEmbedding() {
        // 输入文本
        String text = "我喜欢清淡饮食，不吃辣";

        // 生成向量
        float[] embedding = embeddingService.generateEmbedding(text);

        // 验证：embedding 不为 null
        assertNotNull(embedding, "Embedding should not be null");

        // 验证：维度为 1024 (DashScope text-embedding-v3 model)
        assertEquals(1024, embedding.length, "Embedding dimension should be 1024");

        // 打印前 5 个向量值
        log.info("Generated embedding for text: '{}'", text);
        log.info("Embedding dimension: {}", embedding.length);
        log.info("First 5 values: [{}, {}, {}, {}, {}]",
                embedding[0], embedding[1], embedding[2], embedding[3], embedding[4]);
    }

    /**
     * 测试余弦相似度计算
     */
    @Test
    void testCosineSimilarity() {
        // 文本1：我喜欢清淡饮食
        String text1 = "我喜欢清淡饮食";
        float[] embedding1 = embeddingService.generateEmbedding(text1);

        // 文本2：我喜欢清淡的食物
        String text2 = "我喜欢清淡的食物";
        float[] embedding2 = embeddingService.generateEmbedding(text2);

        // 文本3：我喜欢运动健身
        String text3 = "我喜欢运动健身";
        float[] embedding3 = embeddingService.generateEmbedding(text3);

        // 计算相似度
        double similarity12 = embeddingService.cosineSimilarity(embedding1, embedding2);
        double similarity13 = embeddingService.cosineSimilarity(embedding1, embedding3);

        // 打印相似度
        log.info("Cosine similarity between '{}' and '{}': {}", text1, text2, similarity12);
        log.info("Cosine similarity between '{}' and '{}': {}", text1, text3, similarity13);

        // 验证：相似文本的相似度更高（similarity12 > similarity13）
        assertTrue(similarity12 > similarity13,
                String.format("Similar texts should have higher similarity: %.4f > %.4f", similarity12, similarity13));

        // 验证：相似度在合理范围内 [-1, 1]
        assertTrue(similarity12 >= -1.0 && similarity12 <= 1.0, "Similarity should be in range [-1, 1]");
        assertTrue(similarity13 >= -1.0 && similarity13 <= 1.0, "Similarity should be in range [-1, 1]");
    }
}
