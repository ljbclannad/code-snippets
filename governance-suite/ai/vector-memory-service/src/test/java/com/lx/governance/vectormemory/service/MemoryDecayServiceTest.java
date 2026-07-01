package com.lx.governance.vectormemory.service;

import com.lx.governance.vectormemory.application.governance.*;
import com.lx.governance.vectormemory.application.ingestion.*;
import com.lx.governance.vectormemory.application.retrieval.*;
import com.lx.governance.vectormemory.domain.candidate.*;
import com.lx.governance.vectormemory.domain.lifecycle.*;
import com.lx.governance.vectormemory.domain.retrieval.*;
import com.lx.governance.vectormemory.integration.ai.*;
import com.lx.governance.vectormemory.integration.storage.*;
import com.lx.governance.vectormemory.support.observability.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.lx.governance.vectormemory.config.MemoryLifecycleProperties;
import com.lx.governance.vectormemory.entity.AiGroupMemoryEntity;
import com.lx.governance.vectormemory.mapper.AiGroupMemoryMapper;

@ExtendWith(MockitoExtension.class)
class MemoryDecayServiceTest {

    @Mock
    private AiGroupMemoryMapper memoryMapper;
    @Mock
    private RedisMemoryIndexService redisMemoryIndexService;
    @Mock
    private VectorStoreService vectorStoreService;
    @Mock
    private MemoryLifecycleMetricsService memoryLifecycleMetricsService;
    @Mock
    private MemoryLifecycleAuditService memoryLifecycleAuditService;

    private MemoryDecayService decayService;

    @BeforeEach
    void setUp() {
        MemoryLifecycleProperties properties = new MemoryLifecycleProperties();
        decayService = new MemoryDecayService(
                memoryMapper,
                new MemoryRetentionPolicy(properties),
                redisMemoryIndexService,
                vectorStoreService,
                properties,
                memoryLifecycleMetricsService,
                memoryLifecycleAuditService);
    }

    @Test
    void shouldMarkExpiredMemoryAsDecaying() {
        AiGroupMemoryEntity memory = new AiGroupMemoryEntity();
        memory.setId(1L);
        memory.setMemoryType("SYMPTOM");
        memory.setLifecycleStatus("CONFIRMED");
        memory.setRetentionPolicy("TIME_WINDOW");
        memory.setExpiresAt(LocalDateTime.now().minusDays(1));

        when(memoryMapper.selectLifecycleScanCandidates(100)).thenReturn(List.of(memory));

        MemoryDecayService.DecayBatchResult result = decayService.processLifecycleMaintenance(100);

        assertEquals(1, result.decayed());
        verify(memoryMapper).updateLifecycleState(eq(1L), eq("DECAYING"), any(), eq("TIME_WINDOW"), any(), eq("ttl_expired"), any(), any(), any());
        verify(vectorStoreService, never()).deleteGroupMemory(any());
    }

    @Test
    void shouldArchiveDecayingMemoryAndEvictRedis() {
        AiGroupMemoryEntity memory = new AiGroupMemoryEntity();
        memory.setId(2L);
        memory.setGroupId("group-1");
        memory.setMemoryContent("用户喜欢清淡饮食");
        memory.setMemoryType("PREFERENCE");
        memory.setLifecycleStatus("DECAYING");
        memory.setRetentionPolicy("LATEST_WINS");
        memory.setLastReinforcedAt(LocalDateTime.now().minusDays(220));
        memory.setUpdateTime(LocalDateTime.now().minusDays(40));

        when(memoryMapper.selectLifecycleScanCandidates(100)).thenReturn(List.of(memory));

        MemoryDecayService.DecayBatchResult result = decayService.processLifecycleMaintenance(100);

        assertEquals(1, result.archived());
        verify(memoryMapper).updateLifecycleState(eq(2L), eq("ARCHIVED"), any(), eq("LATEST_WINS"), any(), eq("low_value_archived"), any(), any(), any());
        verify(redisMemoryIndexService).removeFromHashIndex("group-1", "用户喜欢清淡饮食");
    }

    @Test
    void shouldDeleteArchivedMemoryFromAllIndexes() {
        AiGroupMemoryEntity memory = new AiGroupMemoryEntity();
        memory.setId(3L);
        memory.setGroupId("group-2");
        memory.setMemoryContent("用户服用二甲双胍");
        memory.setEmbeddingId("emb-3");
        memory.setMemoryType("MEDICATION");
        memory.setLifecycleStatus("ARCHIVED");
        memory.setArchivedAt(LocalDateTime.now().minusDays(100));

        when(memoryMapper.selectLifecycleScanCandidates(100)).thenReturn(List.of(memory));

        MemoryDecayService.DecayBatchResult result = decayService.processLifecycleMaintenance(100);

        assertEquals(1, result.deleted());
        verify(memoryMapper).deleteById(3L);
        verify(vectorStoreService).deleteGroupMemory("emb-3");
        verify(redisMemoryIndexService).removeRecentMemory("group-2", 3L, "用户服用二甲双胍");
    }
}
