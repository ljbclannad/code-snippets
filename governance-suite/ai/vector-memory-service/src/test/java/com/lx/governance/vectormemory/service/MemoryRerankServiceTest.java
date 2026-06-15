package com.lx.governance.vectormemory.service;

import com.lx.governance.vectormemory.config.MemoryRetrievalProperties;
import com.lx.governance.vectormemory.dto.MemoryItemDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * MemoryRerankService 单元测试
 *
 * @author LeJinBo
 * @since 2026-05-25
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MemoryRerankServiceTest {

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private BgeRerankClient bgeRerankClient;

    private MemoryRerankService rerankService;
    private MemoryRetrievalProperties properties;

    private List<MemoryItemDTO> testMemories;
    private String testQuery;

    @BeforeEach
    void setUp() {
        testQuery = "如何实现分布式事务？";
        properties = new MemoryRetrievalProperties();
        rerankService = new MemoryRerankService(embeddingService, bgeRerankClient, properties);

        testMemories = new ArrayList<>();

        // Redis 缓存的记忆（相似度为 0.0）
        testMemories.add(MemoryItemDTO.builder()
                .id(1L)
                .content("今天天气怎么样？")
                .similarity(0.0)
                .source("RECENT_CACHE")
                .build());

        testMemories.add(MemoryItemDTO.builder()
                .id(2L)
                .content("明天会下雨吗？")
                .similarity(0.0)
                .source("RECENT_CACHE")
                .build());

        // Milvus 检索的记忆（已有相似度）
        testMemories.add(MemoryItemDTO.builder()
                .id(3L)
                .content("Seata 的 AT 模式原理是什么？")
                .similarity(0.85)
                .source("EXTRACTED")
                .build());

        testMemories.add(MemoryItemDTO.builder()
                .id(4L)
                .content("如何配置 Seata 服务端？")
                .similarity(0.80)
                .source("EXTRACTED")
                .build());
    }

    @Test
    void testRerankPartial_shouldOnlyRerankRedisMemories() {
        // Mock embedding service
        float[] queryVector = new float[]{0.1f, 0.2f, 0.3f};
        float[] memory1Vector = new float[]{0.05f, 0.1f, 0.15f}; // 低相似度
        float[] memory2Vector = new float[]{0.08f, 0.12f, 0.18f}; // 低相似度

        when(embeddingService.generateEmbedding(testQuery)).thenReturn(queryVector);
        when(embeddingService.generateEmbeddings(anyList())).thenReturn(List.of(memory1Vector, memory2Vector));
        when(embeddingService.cosineSimilarity(any(), any())).thenReturn(0.3, 0.25);

        // 执行部分重排序
        List<MemoryItemDTO> result = rerankService.rerankPartial(testQuery, testMemories, 3);

        // 验证结果
        assertNotNull(result);
        assertEquals(3, result.size());

        // 验证排序：Milvus 记忆应该排在前面（相似度更高）
        assertEquals(3L, result.get(0).getId()); // Seata AT 模式 (0.85)
        assertEquals(4L, result.get(1).getId()); // Seata 配置 (0.80)
        // 第三个应该是 Redis 记忆中相似度较高的
        assertTrue(result.get(2).getSimilarity() > 0.0);
    }

    @Test
    void testRerankPartial_withEmptyList_shouldReturnEmpty() {
        List<MemoryItemDTO> result = rerankService.rerankPartial(testQuery, new ArrayList<>(), 5);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testRerankPartial_withNoRedisMemories_shouldReturnSortedMilvusResults() {
        // 只有 Milvus 记忆
        List<MemoryItemDTO> milvusOnly = List.of(
                testMemories.get(2), // similarity: 0.85
                testMemories.get(3)  // similarity: 0.80
        );

        List<MemoryItemDTO> result = rerankService.rerankPartial(testQuery, milvusOnly, 2);

        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals(3L, result.get(0).getId()); // 相似度更高的排在前面
        assertEquals(4L, result.get(1).getId());
    }

    @Test
    void testRerank_shouldRerankAllMemories() {
        // Mock embedding service
        float[] queryVector = new float[]{0.1f, 0.2f, 0.3f};
        List<float[]> memoryVectors = List.of(
                new float[]{0.05f, 0.1f, 0.15f},  // memory 1
                new float[]{0.08f, 0.12f, 0.18f}, // memory 2
                new float[]{0.09f, 0.18f, 0.27f}, // memory 3
                new float[]{0.08f, 0.16f, 0.24f}  // memory 4
        );

        when(embeddingService.generateEmbedding(testQuery)).thenReturn(queryVector);
        when(embeddingService.generateEmbeddings(anyList())).thenReturn(memoryVectors);
        when(embeddingService.cosineSimilarity(any(), any()))
                .thenReturn(0.3, 0.25, 0.95, 0.90); // 模拟相似度分数

        // 执行全量重排序
        List<MemoryItemDTO> result = rerankService.rerank(testQuery, testMemories, 3);

        // 验证结果
        assertNotNull(result);
        assertEquals(3, result.size());

        // 验证所有记忆都被重新打分
        for (MemoryItemDTO memory : result) {
            assertNotNull(memory.getSimilarity());
            assertTrue(memory.getSimilarity() > 0.0);
        }

        // 验证排序：相似度最高的在前面
        assertTrue(result.get(0).getSimilarity() >= result.get(1).getSimilarity());
        assertTrue(result.get(1).getSimilarity() >= result.get(2).getSimilarity());
    }

    @Test
    void testRerank_withException_shouldReturnOriginalOrder() {
        // Mock embedding service 抛出异常
        when(embeddingService.generateEmbedding(testQuery)).thenThrow(new RuntimeException("Embedding service error"));

        // 执行重排序
        List<MemoryItemDTO> result = rerankService.rerank(testQuery, testMemories, 3);

        // 验证降级：返回原始顺序的前 3 条
        assertNotNull(result);
        assertEquals(3, result.size());
        assertEquals(1L, result.get(0).getId());
        assertEquals(2L, result.get(1).getId());
        assertEquals(3L, result.get(2).getId());
    }

    @Test
    void testRerank_shouldUseExternalProviderWhenConfigured() {
        properties.getRerank().setProvider("bge-http");
        List<MemoryItemDTO> expected = List.of(testMemories.get(2), testMemories.get(3));
        when(bgeRerankClient.rerank(eq(testQuery), anyList(), anyInt())).thenReturn(expected);

        List<MemoryItemDTO> result = rerankService.rerank(testQuery, testMemories, 2);

        assertEquals(expected, result);
    }

    @Test
    void testRerank_shouldFallbackToEmbeddingWhenExternalProviderFails() {
        properties.getRerank().setProvider("bge-http");
        properties.getRerank().setFallbackToEmbedding(true);

        float[] queryVector = new float[]{0.1f, 0.2f, 0.3f};
        List<float[]> memoryVectors = List.of(
                new float[]{0.05f, 0.1f, 0.15f},
                new float[]{0.08f, 0.12f, 0.18f},
                new float[]{0.09f, 0.18f, 0.27f},
                new float[]{0.08f, 0.16f, 0.24f}
        );

        when(bgeRerankClient.rerank(eq(testQuery), anyList(), anyInt()))
                .thenThrow(new RuntimeException("remote rerank error"));
        when(embeddingService.generateEmbedding(testQuery)).thenReturn(queryVector);
        when(embeddingService.generateEmbeddings(anyList())).thenReturn(memoryVectors);
        when(embeddingService.cosineSimilarity(any(), any())).thenReturn(0.3, 0.25, 0.95, 0.90);

        List<MemoryItemDTO> result = rerankService.rerank(testQuery, testMemories, 2);

        assertEquals(2, result.size());
        assertEquals(3L, result.get(0).getId());
        assertEquals(4L, result.get(1).getId());
    }
}
