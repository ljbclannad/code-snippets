package com.lx.governance.vectormemory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.lx.governance.vectormemory.dto.MemoryExtractionResult;
import com.lx.governance.vectormemory.dto.MemoryLifecycleDecision;

@ExtendWith(MockitoExtension.class)
class MemoryLifecycleIngestionPipelineTest {

    @Mock
    private RedisMemoryIndexService redisMemoryIndexService;
    @Mock
    private CandidateMemoryService candidateMemoryService;
    @Mock
    private MemoryLifecycleAuditService memoryLifecycleAuditService;
    @Mock
    private MemoryLifecycleMetricsService memoryLifecycleMetricsService;

    private MemoryLifecycleIngestionPipeline pipeline;

    @BeforeEach
    void setUp() {
        pipeline = new MemoryLifecycleIngestionPipeline(
                redisMemoryIndexService,
                candidateMemoryService,
                new MemoryTypeNormalizer(),
                new MemoryNoiseFilter(),
                new MemoryWorthinessEvaluator(),
                memoryLifecycleAuditService,
                memoryLifecycleMetricsService);
    }

    @Test
    void shouldRejectLowConfidenceMemoryBeforeCandidateIngestion() {
        MemoryExtractionResult.ExtractedMemory memory = new MemoryExtractionResult.ExtractedMemory();
        memory.setContent("用户可能以后想运动");
        memory.setCategory("goal");
        memory.setConfidence(0.40d);

        MemoryLifecycleDecision decision = pipeline.ingest("group-1", "customer-1", memory, "source");

        assertEquals(MemoryLifecycleDecision.LifecycleAction.REJECT_LOW_WORTHINESS, decision.getAction());
        assertEquals("low_confidence", decision.getDecisionReasonCode());
        verify(candidateMemoryService, never()).processLifecycleDecision(any());
        verify(memoryLifecycleAuditService).recordIngestionDecision(decision);
    }

    @Test
    void shouldRejectRedisDuplicateBeforeCandidateIngestion() {
        MemoryExtractionResult.ExtractedMemory memory = new MemoryExtractionResult.ExtractedMemory();
        memory.setContent("用户喜欢清淡饮食");
        memory.setCategory("preference");
        memory.setConfidence(0.85d);

        when(redisMemoryIndexService.isDuplicate("group-1", "用户喜欢清淡饮食")).thenReturn(true);

        MemoryLifecycleDecision decision = pipeline.ingest("group-1", "customer-1", memory, "source");

        assertEquals(MemoryLifecycleDecision.LifecycleAction.REJECT_DUPLICATE, decision.getAction());
        assertEquals("redis_duplicate", decision.getDecisionReasonCode());
        verify(candidateMemoryService, never()).processLifecycleDecision(any());
        verify(memoryLifecycleAuditService).recordIngestionDecision(decision);
    }

    @Test
    void shouldIngestCandidateWhenMemoryPassesFilters() {
        MemoryExtractionResult.ExtractedMemory memory = new MemoryExtractionResult.ExtractedMemory();
        memory.setContent("用户喜欢清淡饮食");
        memory.setCategory("preference");
        memory.setConfidence(0.85d);

        when(redisMemoryIndexService.isDuplicate("group-1", "用户喜欢清淡饮食")).thenReturn(false);

        MemoryLifecycleDecision decision = pipeline.ingest("group-1", "customer-1", memory, "source");

        assertEquals(MemoryLifecycleDecision.LifecycleAction.INGEST_CANDIDATE, decision.getAction());
        assertEquals("accepted_for_candidate", decision.getDecisionReasonCode());
        verify(candidateMemoryService).processLifecycleDecision(any(MemoryLifecycleDecision.class));
        verify(memoryLifecycleAuditService).recordIngestionDecision(eq(decision));
    }
}
