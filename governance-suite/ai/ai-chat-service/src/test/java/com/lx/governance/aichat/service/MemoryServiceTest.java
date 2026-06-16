package com.lx.governance.aichat.service;

import com.lx.governance.aichat.client.VectorMemoryClientFixed;
import com.lx.governance.aichat.config.ContextBudgetProperties;
import com.lx.governance.aichat.dto.MemoryItemDTO;
import com.lx.governance.aichat.dto.StructuredMemoryQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * MemoryService 测试类
 */
class MemoryServiceTest {

    @Mock
    private VectorMemoryClientFixed vectorMemoryClient;

    private MemoryService memoryService;
    private ContextBudgetProperties properties;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        properties = new ContextBudgetProperties();
        ContextBudgetProperties.MemoryConfig memoryConfig = new ContextBudgetProperties.MemoryConfig();
        memoryConfig.setMaxCount(5);
        memoryConfig.setSimilarityThreshold(0.7);
        memoryConfig.setEnableDeduplication(true);
        properties.setMemory(memoryConfig);

        memoryService = new MemoryService(vectorMemoryClient, properties);
    }

    @Test
    void testRetrieveMemories_success() throws Exception {
        // 准备测试数据
        List<MemoryItemDTO> mockMemories = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            MemoryItemDTO memory = new MemoryItemDTO();
            memory.setId((long) i);
            memory.setContent("Memory content " + i);
            memory.setSimilarity(0.9 - i * 0.05); // 0.9, 0.85, 0.8, ...
            mockMemories.add(memory);
        }

        when(vectorMemoryClient.requestMemories(anyString(), anyString(), anyString(), anyInt(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(mockMemories));

        // 执行测试
        List<MemoryItemDTO> result = memoryService.retrieveMemories("group1", "customer1", "test query")
                .get();

        // 验证结果
        assertNotNull(result);
        assertTrue(result.size() <= 5); // 不超过 maxCount
        assertTrue(result.stream().allMatch(m -> m.getSimilarity() >= 0.7)); // 都满足阈值

        // 验证按相似度降序排列
        for (int i = 0; i < result.size() - 1; i++) {
            assertTrue(result.get(i).getSimilarity() >= result.get(i + 1).getSimilarity());
        }

        verify(vectorMemoryClient).requestMemories(eq("group1"), eq("customer1"), eq("test query"), eq(10),
                isNull(), isNull(), isNull(), isNull(), any(StructuredMemoryQuery.class));
    }

    @Test
    void testRetrieveMemories_filterBySimilarity() throws Exception {
        // 准备测试数据 - 部分低于阈值
        List<MemoryItemDTO> mockMemories = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            MemoryItemDTO memory = new MemoryItemDTO();
            memory.setId((long) i);
            memory.setContent("Memory " + i);
            memory.setSimilarity(0.5 + i * 0.1); // 0.5, 0.6, 0.7, 0.8, 0.9
            mockMemories.add(memory);
        }

        when(vectorMemoryClient.requestMemories(anyString(), anyString(), anyString(), anyInt(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(mockMemories));

        // 执行测试
        List<MemoryItemDTO> result = memoryService.retrieveMemories("group1", "customer1", "query")
                .get();

        // 验证结果 - 只有 0.7, 0.8, 0.9 三条
        assertEquals(3, result.size());
        assertTrue(result.stream().allMatch(m -> m.getSimilarity() >= 0.7));
    }

    @Test
    void testRetrieveMemories_deduplication() throws Exception {
        // 准备测试数据 - 包含重复内容
        List<MemoryItemDTO> mockMemories = new ArrayList<>();

        MemoryItemDTO memory1 = new MemoryItemDTO();
        memory1.setId(1L);
        memory1.setContent("用户喜欢喝咖啡");
        memory1.setSimilarity(0.9);
        mockMemories.add(memory1);

        MemoryItemDTO memory2 = new MemoryItemDTO();
        memory2.setId(2L);
        memory2.setContent("用户喜欢喝咖啡"); // 完全相同
        memory2.setSimilarity(0.85);
        mockMemories.add(memory2);

        MemoryItemDTO memory3 = new MemoryItemDTO();
        memory3.setId(3L);
        memory3.setContent("用户喜欢喝茶");
        memory3.setSimilarity(0.8);
        mockMemories.add(memory3);

        when(vectorMemoryClient.requestMemories(anyString(), anyString(), anyString(), anyInt(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(mockMemories));

        // 执行测试
        List<MemoryItemDTO> result = memoryService.retrieveMemories("group1", "customer1", "query")
                .get();

        // 验证结果 - 重复的应该被去除，保留相似度高的
        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(m -> m.getId() == 1L)); // 保留相似度更高的
        assertTrue(result.stream().anyMatch(m -> m.getId() == 3L));
    }

    @Test
    void testRetrieveMemories_emptyResult() throws Exception {
        when(vectorMemoryClient.requestMemories(anyString(), anyString(), anyString(), anyInt(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(new ArrayList<>()));

        List<MemoryItemDTO> result = memoryService.retrieveMemories("group1", "customer1", "query")
                .get();

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testRetrieveMemories_clientError() throws Exception {
        when(vectorMemoryClient.requestMemories(anyString(), anyString(), anyString(), anyInt(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Client error")));

        List<MemoryItemDTO> result = memoryService.retrieveMemories("group1", "customer1", "query")
                .get();

        // 应该返回空列表而不是抛出异常
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testRetrieveMemories_withoutDeduplication() throws Exception {
        // 禁用去重
        properties.getMemory().setEnableDeduplication(false);

        List<MemoryItemDTO> mockMemories = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            MemoryItemDTO memory = new MemoryItemDTO();
            memory.setId((long) i);
            memory.setContent("Same content"); // 相同内容
            memory.setSimilarity(0.9 - i * 0.05);
            mockMemories.add(memory);
        }

        when(vectorMemoryClient.requestMemories(anyString(), anyString(), anyString(), anyInt(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(mockMemories));

        List<MemoryItemDTO> result = memoryService.retrieveMemories("group1", "customer1", "query")
                .get();

        // 不去重，应该保留所有记忆
        assertEquals(3, result.size());
    }

    @Test
    void testExtractMemory() {
        // 执行测试
        memoryService.extractMemory("group1", "customer1", "user message", "ai response");

        // 验证调用
        verify(vectorMemoryClient).triggerMemoryExtraction(
                eq("group1"),
                eq("customer1"),
                eq("user message"),
                eq("ai response")
        );
    }
}
