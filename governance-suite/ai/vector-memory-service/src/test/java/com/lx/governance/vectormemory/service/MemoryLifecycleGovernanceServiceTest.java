package com.lx.governance.vectormemory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.lx.governance.vectormemory.dto.MemoryLifecycleHistoryView;
import com.lx.governance.vectormemory.dto.MemoryLifecycleReplayView;
import com.lx.governance.vectormemory.entity.AiGroupMemoryCandidateEntity;
import com.lx.governance.vectormemory.entity.AiGroupMemoryEntity;
import com.lx.governance.vectormemory.entity.AiGroupMemoryLifecycleEventEntity;
import com.lx.governance.vectormemory.mapper.AiGroupMemoryCandidateMapper;
import com.lx.governance.vectormemory.mapper.AiGroupMemoryConflictMapper;
import com.lx.governance.vectormemory.mapper.AiGroupMemoryLifecycleEventMapper;
import com.lx.governance.vectormemory.mapper.AiGroupMemoryMapper;
import com.lx.governance.vectormemory.mapper.AiGroupMemoryReviewAuditMapper;

@ExtendWith(MockitoExtension.class)
class MemoryLifecycleGovernanceServiceTest {

    @Mock
    private AiGroupMemoryCandidateMapper candidateMapper;
    @Mock
    private AiGroupMemoryConflictMapper conflictMapper;
    @Mock
    private AiGroupMemoryReviewAuditMapper reviewAuditMapper;
    @Mock
    private AiGroupMemoryMapper memoryMapper;
    @Mock
    private AiGroupMemoryLifecycleEventMapper lifecycleEventMapper;
    @Mock
    private ConfirmedMemoryMaintenanceService confirmedMemoryMaintenanceService;
    @Mock
    private RedisMemoryIndexService redisMemoryIndexService;
    @Mock
    private VectorStoreService vectorStoreService;
    @Mock
    private MemoryLifecycleAuditService memoryLifecycleAuditService;
    @Mock
    private MemoryLifecycleMetricsService memoryLifecycleMetricsService;

    private MemoryLifecycleGovernanceService governanceService;

    @BeforeEach
    void setUp() {
        governanceService = new MemoryLifecycleGovernanceService(
                candidateMapper,
                conflictMapper,
                reviewAuditMapper,
                memoryMapper,
                lifecycleEventMapper,
                confirmedMemoryMaintenanceService,
                redisMemoryIndexService,
                vectorStoreService,
                memoryLifecycleAuditService,
                memoryLifecycleMetricsService);
    }

    @Test
    void shouldBuildReplayViewFromScopedEvents() {
        AiGroupMemoryLifecycleEventEntity event = new AiGroupMemoryLifecycleEventEntity();
        event.setEventType("INGESTION_DECISION");
        when(lifecycleEventMapper.selectRecentByScope("customer-1", "group-1", "PREFERENCE", 20))
                .thenReturn(List.of(event));

        MemoryLifecycleReplayView view = governanceService.buildReplayView("group-1", "customer-1", "PREFERENCE", 20);

        assertEquals(1, view.getEvents().size());
        assertEquals("INGESTION_DECISION", view.getEvents().get(0).getEventType());
    }

    @Test
    void shouldLoadMemoryHistoryByMemoryId() {
        AiGroupMemoryEntity memory = buildMemory(3L);
        when(memoryMapper.selectById(3L)).thenReturn(memory);
        when(lifecycleEventMapper.selectByEntity("CONFIRMED", 3L)).thenReturn(List.of(new AiGroupMemoryLifecycleEventEntity()));

        MemoryLifecycleHistoryView historyView = governanceService.getMemoryHistory(3L);

        assertEquals(3L, historyView.getMemory().getId());
        assertEquals(1, historyView.getEvents().size());
    }

    @Test
    void shouldArchiveMemoryAndEvictRedis() {
        AiGroupMemoryEntity memory = buildMemory(4L);
        when(memoryMapper.selectById(4L)).thenReturn(memory);

        governanceService.archiveMemory(4L, "doctor-a", "manual_archive", "expired by reviewer");

        verify(memoryMapper).updateLifecycleState(eq(4L), eq("ARCHIVED"), eq(0.12d),
                eq("LONG_TERM"), eq(memory.getExpiresAt()), eq("manual_archive"), any(), eq("expired by reviewer"), any());
        verify(redisMemoryIndexService).removeFromHashIndex("group-1", "用户喜欢清淡饮食");
        verify(memoryLifecycleMetricsService).recordArchive();
    }

    @Test
    void shouldRestoreMemoryAndWarmRedis() {
        AiGroupMemoryEntity memory = buildMemory(5L);
        when(memoryMapper.selectById(5L)).thenReturn(memory);

        governanceService.restoreMemory(5L, "doctor-a", "manual_restore", "resume default recall");

        verify(memoryMapper).updateLifecycleState(eq(5L), eq("CONFIRMED"), eq(0.12d),
                eq("LONG_TERM"), eq(memory.getExpiresAt()), eq("manual_restore"), isNull(), isNull(), any());
        verify(redisMemoryIndexService).addToHashIndex("group-1", "用户喜欢清淡饮食");
        verify(redisMemoryIndexService).cacheRecentMemory(eq("group-1"), eq(5L), eq("用户喜欢清淡饮食"), anyDouble());
    }

    @Test
    void shouldDeleteMemoryFromAllStores() {
        AiGroupMemoryEntity memory = buildMemory(6L);
        memory.setEmbeddingId("emb-6");
        when(memoryMapper.selectById(6L)).thenReturn(memory);

        governanceService.deleteMemory(6L, "doctor-a", "manual_delete", "bad memory");

        verify(memoryMapper).deleteById(6L);
        verify(vectorStoreService).deleteGroupMemory("emb-6");
        verify(redisMemoryIndexService).removeRecentMemory("group-1", 6L, "用户喜欢清淡饮食");
        verify(memoryLifecycleMetricsService).recordDelete();
    }

    @Test
    void shouldMergeMemoriesIntoTargetAndArchiveSource() {
        AiGroupMemoryEntity source = buildMemory(7L);
        source.setSourceCount(2);
        source.setEvidenceCount(2);
        source.setSourceMessage("source");
        AiGroupMemoryEntity target = buildMemory(8L);
        target.setSourceCount(3);
        target.setEvidenceCount(4);
        target.setSourceMessage("target");

        when(memoryMapper.selectById(7L)).thenReturn(source);
        when(memoryMapper.selectById(8L)).thenReturn(target);

        governanceService.mergeMemories(7L, 8L, "doctor-a", "manual_merge", "merge duplicates");

        verify(confirmedMemoryMaintenanceService).refreshConfirmedMemory(any(AiGroupMemoryEntity.class));
        verify(memoryMapper).updateConfirmedMemory(source);
        verify(redisMemoryIndexService).removeFromHashIndex("group-1", "用户喜欢清淡饮食");
        verify(vectorStoreService, never()).deleteGroupMemory(any());
    }

    private AiGroupMemoryEntity buildMemory(Long id) {
        AiGroupMemoryEntity memory = new AiGroupMemoryEntity();
        memory.setId(id);
        memory.setCustomerId("customer-1");
        memory.setGroupId("group-1");
        memory.setMemoryType("PREFERENCE");
        memory.setMemoryContent("用户喜欢清淡饮食");
        memory.setRetentionPolicy("LONG_TERM");
        memory.setDecayScore(BigDecimal.valueOf(0.12d));
        memory.setExpiresAt(LocalDateTime.now().plusDays(7));
        memory.setExtractedAt(LocalDateTime.now().minusDays(1));
        memory.setIsDeleted(0);
        return memory;
    }
}
