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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.lx.governance.vectormemory.config.MemoryLifecycleProperties;
import com.lx.governance.vectormemory.entity.AiGroupMemoryEntity;
import com.lx.governance.vectormemory.mapper.AiGroupMemoryMapper;

@ExtendWith(MockitoExtension.class)
class MemoryCompactionServiceTest {

    @Mock
    private AiGroupMemoryMapper memoryMapper;
    @Mock
    private ConfirmedMemoryMaintenanceService confirmedMemoryMaintenanceService;
    @Mock
    private VectorStoreService vectorStoreService;
    @Mock
    private MemoryLifecycleMetricsService memoryLifecycleMetricsService;
    @Mock
    private MemoryLifecycleAuditService memoryLifecycleAuditService;

    private MemoryCompactionService memoryCompactionService;

    @BeforeEach
    void setUp() {
        MemoryLifecycleProperties properties = new MemoryLifecycleProperties();
        memoryCompactionService = new MemoryCompactionService(
                memoryMapper,
                new SemanticMemoryConflictDetector(),
                confirmedMemoryMaintenanceService,
                vectorStoreService,
                new MemoryRetentionPolicy(properties),
                properties,
                memoryLifecycleMetricsService,
                memoryLifecycleAuditService);
    }

    @Test
    void shouldCreateSummaryMemoryFromRepeatedEvidence() {
        AiGroupMemoryEntity first = buildEvidence(1L, "用户喜欢清淡饮食", 3, 3);
        AiGroupMemoryEntity second = buildEvidence(2L, "用户喜欢清淡", 2, 2);

        when(memoryMapper.selectCompactionCandidates(100)).thenReturn(List.of(first, second));
        when(memoryMapper.selectSummaryByGroupKey(eq("group-1"), eq("customer-1"), any())).thenReturn(null);
        when(vectorStoreService.saveGroupMemory(any(AiGroupMemoryEntity.class))).thenReturn("summary-emb-1");

        MemoryCompactionService.CompactionBatchResult result = memoryCompactionService.compactCandidates(100);

        assertEquals(1, result.created());
        verify(memoryMapper).updateCompactionMetadata(eq(1L), eq("EVIDENCE"), any(), eq(2L), eq("compaction_evidence_linked"));
        verify(memoryMapper).updateCompactionMetadata(eq(2L), eq("EVIDENCE"), any(), eq(2L), eq("compaction_evidence_linked"));

        ArgumentCaptor<AiGroupMemoryEntity> captor = ArgumentCaptor.forClass(AiGroupMemoryEntity.class);
        verify(memoryMapper).insert(captor.capture());
        AiGroupMemoryEntity summary = captor.getValue();
        assertEquals("SUMMARY", summary.getMemoryRepresentation());
        assertEquals(2L, summary.getSummaryOfMemoryId());
        verify(vectorStoreService).saveGroupMemory(any(AiGroupMemoryEntity.class));
    }

    @Test
    void shouldUpdateExistingSummaryMemoryWhenCompactionRunsAgain() {
        AiGroupMemoryEntity evidence = buildEvidence(1L, "用户喜欢清淡饮食", 3, 3);
        AiGroupMemoryEntity existingSummary = new AiGroupMemoryEntity();
        existingSummary.setId(10L);
        existingSummary.setCustomerId("customer-1");
        existingSummary.setGroupId("group-1");
        existingSummary.setMemoryType("PREFERENCE");
        existingSummary.setMemoryContent("摘要记忆：用户喜欢清淡饮食");
        existingSummary.setMemoryRepresentation("SUMMARY");

        when(memoryMapper.selectCompactionCandidates(100)).thenReturn(List.of(evidence));
        when(memoryMapper.selectSummaryByGroupKey(eq("group-1"), eq("customer-1"), any())).thenReturn(existingSummary);

        MemoryCompactionService.CompactionBatchResult result = memoryCompactionService.compactCandidates(100);

        assertEquals(1, result.updated());
        verify(confirmedMemoryMaintenanceService).refreshConfirmedMemory(any(AiGroupMemoryEntity.class));
        verify(memoryMapper, never()).insert(any());
    }

    private AiGroupMemoryEntity buildEvidence(Long id, String content, int evidenceCount, int sourceCount) {
        AiGroupMemoryEntity entity = new AiGroupMemoryEntity();
        entity.setId(id);
        entity.setCustomerId("customer-1");
        entity.setGroupId("group-1");
        entity.setMemoryType("PREFERENCE");
        entity.setMemoryKey("用户喜欢");
        entity.setMemoryContent(content);
        entity.setMemoryRepresentation("EVIDENCE");
        entity.setConfidenceScore(BigDecimal.valueOf(0.92));
        entity.setEvidenceCount(evidenceCount);
        entity.setSourceCount(sourceCount);
        entity.setExtractedAt(LocalDateTime.now().minusDays(20));
        return entity;
    }
}
