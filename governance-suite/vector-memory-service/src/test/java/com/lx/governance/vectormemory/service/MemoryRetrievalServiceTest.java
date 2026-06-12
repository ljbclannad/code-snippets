package com.lx.governance.vectormemory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;

import com.lx.governance.vectormemory.config.MemoryRetrievalProperties;
import com.lx.governance.vectormemory.dto.MemoryItemDTO;
import com.lx.governance.vectormemory.entity.AiGroupMemoryEntity;

@ExtendWith(MockitoExtension.class)
class MemoryRetrievalServiceTest {

    @Mock
    private VectorStoreService vectorStoreService;
    @Mock
    private com.lx.governance.vectormemory.mapper.AiGroupMemoryMapper memoryMapper;
    @Mock
    private RedisMemoryIndexService redisMemoryIndexService;
    @Mock
    private MemoryRerankService rerankService;
    @Mock
    private MemoryDeduplicationService deduplicationService;

    @InjectMocks
    private MemoryRetrievalService memoryRetrievalService;

    @BeforeEach
    void setUp() {
        MemoryRetrievalProperties properties = new MemoryRetrievalProperties();
        MemoryRetrievalProperties.RerankConfig rerankConfig = new MemoryRetrievalProperties.RerankConfig();
        rerankConfig.setEnabled(false);
        properties.setRerank(rerankConfig);
        org.springframework.test.util.ReflectionTestUtils.setField(memoryRetrievalService, "properties", properties);
    }

    @Test
    void shouldFilterByMemoryTypeAndMinConfidence() {
        AiGroupMemoryEntity dietMemory = buildEntity(1L, "DIET", 0.92, LocalDateTime.now().minusDays(1));
        AiGroupMemoryEntity symptomMemory = buildEntity(2L, "SYMPTOM", 0.65, LocalDateTime.now().minusDays(1));

        mockMilvusAndMapper(List.of(dietMemory, symptomMemory));
        when(deduplicationService.mergeAndDeduplicate(anyList(), anyList()))
                .thenAnswer(invocation -> invocation.getArgument(1));

        List<MemoryItemDTO> results = memoryRetrievalService.retrieveGroupMemories(
                "晚饭怎么吃",
                "group-1",
                "customer-1",
                5,
                Set.of("DIET"),
                0.8,
                false,
                null);

        assertEquals(1, results.size());
        assertEquals("DIET", results.get(0).getSource());
        assertTrue(results.get(0).getConfidenceScore() >= 0.8);
    }

    @Test
    void shouldFilterByVerifiedOnly() {
        AiGroupMemoryEntity verifiedMemory = buildEntity(1L, "DIET", 0.91, LocalDateTime.now().minusDays(1));
        AiGroupMemoryEntity unverifiedMemory = buildEntity(2L, "DIET", 0.70, LocalDateTime.now().minusDays(1));

        mockMilvusAndMapper(List.of(verifiedMemory, unverifiedMemory));
        when(deduplicationService.mergeAndDeduplicate(anyList(), anyList()))
                .thenAnswer(invocation -> invocation.getArgument(1));

        List<MemoryItemDTO> results = memoryRetrievalService.retrieveGroupMemories(
                "晚饭怎么吃",
                "group-1",
                "customer-1",
                5,
                null,
                null,
                true,
                null);

        assertEquals(1, results.size());
        assertTrue(Boolean.TRUE.equals(results.get(0).getVerified()));
    }

    @Test
    void shouldFilterByRecentDaysBeforeRerank() {
        AiGroupMemoryEntity recentMemory = buildEntity(1L, "DIET", 0.91, LocalDateTime.now().minusDays(2));
        AiGroupMemoryEntity oldMemory = buildEntity(2L, "DIET", 0.95, LocalDateTime.now().minusDays(20));

        mockMilvusAndMapper(List.of(recentMemory, oldMemory));
        when(deduplicationService.mergeAndDeduplicate(anyList(), anyList()))
                .thenAnswer(invocation -> invocation.getArgument(1));

        List<MemoryItemDTO> results = memoryRetrievalService.retrieveGroupMemories(
                "晚饭怎么吃",
                "group-1",
                "customer-1",
                5,
                null,
                null,
                false,
                7);

        assertEquals(1, results.size());
        assertEquals(1L, results.get(0).getId());
        verify(rerankService, never()).rerank(eq("晚饭怎么吃"), anyList(), anyInt());
    }

    private void mockMilvusAndMapper(List<AiGroupMemoryEntity> entities) {
        List<Document> documents = entities.stream()
                .map(entity -> new Document(
                        entity.getEmbeddingId(),
                        entity.getMemoryContent(),
                        java.util.Map.of("distance", 0.1)))
                .toList();

        when(vectorStoreService.searchGroupMemories(eq("晚饭怎么吃"), eq("group-1"), eq("customer-1"), anyInt()))
                .thenReturn(documents);
        when(memoryMapper.selectByEmbeddingIds(anyList())).thenReturn(entities);
        when(redisMemoryIndexService.getRecentMemories(eq("group-1"), anyInt())).thenReturn(List.of());
    }

    private AiGroupMemoryEntity buildEntity(Long id, String type, double confidence, LocalDateTime extractedAt) {
        AiGroupMemoryEntity entity = new AiGroupMemoryEntity();
        entity.setId(id);
        entity.setEmbeddingId("embedding-" + id);
        entity.setGroupId("group-1");
        entity.setCustomerId("customer-1");
        entity.setMemoryType(type);
        entity.setMemoryContent(type + "-memory-" + id);
        entity.setConfidenceScore(BigDecimal.valueOf(confidence));
        entity.setExtractedAt(extractedAt);
        return entity;
    }
}
