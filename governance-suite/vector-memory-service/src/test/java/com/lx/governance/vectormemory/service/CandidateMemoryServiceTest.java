package com.lx.governance.vectormemory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyDouble;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.lx.governance.vectormemory.dto.MemoryExtractionResult;
import com.lx.governance.vectormemory.entity.AiGroupMemoryEntity;
import com.lx.governance.vectormemory.entity.AiGroupMemoryCandidateEntity;
import com.lx.governance.vectormemory.entity.AiGroupMemoryConflictEntity;
import com.lx.governance.vectormemory.mapper.AiGroupMemoryMapper;
import com.lx.governance.vectormemory.mapper.AiGroupMemoryCandidateMapper;

@ExtendWith(MockitoExtension.class)
class CandidateMemoryServiceTest {

    @Mock
    private AiGroupMemoryCandidateMapper candidateMapper;
    @Mock
    private com.lx.governance.vectormemory.mapper.AiGroupMemoryConflictMapper conflictMapper;
    @Mock
    private AiGroupMemoryMapper confirmedMemoryMapper;
    @Mock
    private VectorStoreService vectorStoreService;
    @Mock
    private RedisMemoryIndexService redisMemoryIndexService;

    private CandidateMemoryService candidateMemoryService;

    @BeforeEach
    void setUp() {
        candidateMemoryService = new CandidateMemoryService(
                candidateMapper,
                conflictMapper,
                confirmedMemoryMapper,
                vectorStoreService,
                redisMemoryIndexService);
    }

    @Test
    void shouldCreateCandidateWhenNoExistingCandidateOrConflict() {
        MemoryExtractionResult.ExtractedMemory extracted = new MemoryExtractionResult.ExtractedMemory();
        extracted.setContent("用户喜欢清淡饮食");
        extracted.setCategory("preference");
        extracted.setConfidence(0.82);

        when(candidateMapper.selectLatestByNormalized(eq("customer-1"), eq("group-1"), eq("用户喜欢清淡饮食"), eq("用户喜欢清淡饮食")))
                .thenReturn(null);
        when(candidateMapper.selectActiveByCustomerId("customer-1", "group-1")).thenReturn(List.of());

        candidateMemoryService.processCandidate("group-1", "customer-1", extracted, "source");

        verify(candidateMapper).insert(any(AiGroupMemoryCandidateEntity.class));
        verify(candidateMapper, never()).incrementHitCount(any(), any(), any(), any());
    }

    @Test
    void shouldPromoteCandidateWhenHitCountExceedsFiveAndConfidenceHigh() {
        MemoryExtractionResult.ExtractedMemory extracted = new MemoryExtractionResult.ExtractedMemory();
        extracted.setContent("用户喜欢清淡饮食");
        extracted.setCategory("preference");
        extracted.setConfidence(0.86);

        AiGroupMemoryCandidateEntity existing = new AiGroupMemoryCandidateEntity();
        existing.setId(1L);
        existing.setCustomerId("customer-1");
        existing.setGroupId("group-1");
        existing.setMemoryType("PREFERENCE");
        existing.setMemoryKey("用户喜欢清淡饮");
        existing.setNormalizedKey("用户喜欢清淡饮食");
        existing.setNormalizedValue("用户喜欢清淡饮食");
        existing.setPolarity("POSITIVE");
        existing.setMemoryContent("用户喜欢清淡饮食");
        existing.setConfidenceScore(BigDecimal.valueOf(0.82));
        existing.setStatus("CANDIDATE");
        existing.setHitCount(5);
        existing.setLastSeenAt(LocalDateTime.now().minusDays(1));

        when(candidateMapper.selectLatestByNormalized(eq("customer-1"), eq("group-1"), eq("用户喜欢清淡饮食"), eq("用户喜欢清淡饮食")))
                .thenReturn(existing);
        when(candidateMapper.selectById(1L)).thenReturn(existing);
        when(vectorStoreService.saveGroupMemory(any(AiGroupMemoryEntity.class))).thenReturn("embedding-1");
        when(conflictMapper.selectByCandidateId(1L)).thenReturn(List.of());

        candidateMemoryService.processCandidate("group-1", "customer-1", extracted, "source");

        verify(candidateMapper).incrementHitCount(eq(1L), any(), any(), any());
        verify(confirmedMemoryMapper).insert(any(AiGroupMemoryEntity.class));
        verify(vectorStoreService).saveGroupMemory(any(AiGroupMemoryEntity.class));
        verify(confirmedMemoryMapper).updateEmbeddingId(100L, "embedding-1");
        verify(redisMemoryIndexService).addToHashIndex("group-1", "用户喜欢清淡饮食");
        verify(redisMemoryIndexService).cacheRecentMemory(eq("group-1"), eq(100L), eq("用户喜欢清淡饮食"), anyDouble());
        verify(candidateMapper).updateStatus(eq(1L), eq("CONFIRMED"), eq("SYSTEM"), any(), eq(100L));
    }

    @Test
    void shouldMarkConflictWhenExistingActiveCandidateHasSameKeyButDifferentValue() {
        MemoryExtractionResult.ExtractedMemory extracted = new MemoryExtractionResult.ExtractedMemory();
        extracted.setContent("用户喜欢重辣饮食");
        extracted.setCategory("preference");
        extracted.setConfidence(0.86);

        when(candidateMapper.selectLatestByNormalized(eq("customer-1"), eq("group-1"), eq("用户喜欢重辣饮食"), eq("用户喜欢重辣饮食")))
                .thenReturn(null);

        AiGroupMemoryCandidateEntity conflictTarget = new AiGroupMemoryCandidateEntity();
        conflictTarget.setId(2L);
        conflictTarget.setCustomerId("customer-1");
        conflictTarget.setGroupId("group-1");
        conflictTarget.setMemoryType("PREFERENCE");
        conflictTarget.setNormalizedKey("用户喜欢清淡饮食");
        conflictTarget.setNormalizedValue("用户喜欢清淡饮食");
        conflictTarget.setStatus("CANDIDATE");
        when(candidateMapper.selectActiveByCustomerId("customer-1", "group-1")).thenReturn(List.of(conflictTarget));

        candidateMemoryService.processCandidate("group-1", "customer-1", extracted, "source");

        verify(candidateMapper).insert(any(AiGroupMemoryCandidateEntity.class));
        verify(conflictMapper).insert(any());
    }

    @Test
    void shouldConfirmCandidateIntoFormalMemoryOnManualConfirm() {
        AiGroupMemoryCandidateEntity candidate = new AiGroupMemoryCandidateEntity();
        candidate.setId(9L);
        candidate.setCustomerId("customer-1");
        candidate.setGroupId("group-1");
        candidate.setMemoryType("PREFERENCE");
        candidate.setMemoryKey("用户喜欢清淡饮");
        candidate.setMemoryContent("用户喜欢清淡饮食");
        candidate.setConfidenceScore(BigDecimal.valueOf(0.91));
        candidate.setStatus("CONFLICT");

        AiGroupMemoryConflictEntity conflict = new AiGroupMemoryConflictEntity();
        conflict.setId(7L);
        conflict.setCandidateId(9L);
        conflict.setResolutionStatus("OPEN");
        when(candidateMapper.selectById(9L)).thenReturn(candidate);
        when(vectorStoreService.saveGroupMemory(any(AiGroupMemoryEntity.class))).thenReturn("embedding-9");
        when(conflictMapper.selectByCandidateId(9L)).thenReturn(List.of(conflict));

        candidateMemoryService.confirmCandidate(9L, "doctor-a");

        verify(confirmedMemoryMapper).insert(any(AiGroupMemoryEntity.class));
        verify(confirmedMemoryMapper).updateEmbeddingId(100L, "embedding-9");
        verify(redisMemoryIndexService).addToHashIndex("group-1", "用户喜欢清淡饮食");
        verify(redisMemoryIndexService).cacheRecentMemory(eq("group-1"), eq(100L), eq("用户喜欢清淡饮食"), anyDouble());
        verify(conflictMapper).updateResolutionStatus(7L, "RESOLVED");
        verify(candidateMapper).updateStatus(eq(9L), eq("CONFIRMED"), eq("doctor-a"), any(), eq(100L));
    }
}
