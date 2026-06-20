package com.lx.governance.vectormemory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
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

import com.lx.governance.vectormemory.dto.MemoryExtractionResult;
import com.lx.governance.vectormemory.entity.AiGroupMemoryCandidateEntity;
import com.lx.governance.vectormemory.entity.AiGroupMemoryConflictEntity;
import com.lx.governance.vectormemory.entity.AiGroupMemoryEntity;
import com.lx.governance.vectormemory.entity.AiGroupMemoryReviewAuditEntity;
import com.lx.governance.vectormemory.mapper.AiGroupMemoryCandidateMapper;
import com.lx.governance.vectormemory.mapper.AiGroupMemoryConflictMapper;
import com.lx.governance.vectormemory.mapper.AiGroupMemoryMapper;
import com.lx.governance.vectormemory.mapper.AiGroupMemoryReviewAuditMapper;

@ExtendWith(MockitoExtension.class)
class CandidateMemoryServiceTest {

    private final MemoryTypeNormalizer memoryTypeNormalizer = new MemoryTypeNormalizer();

    @Mock
    private AiGroupMemoryCandidateMapper candidateMapper;
    @Mock
    private AiGroupMemoryConflictMapper conflictMapper;
    @Mock
    private AiGroupMemoryMapper confirmedMemoryMapper;
    @Mock
    private VectorStoreService vectorStoreService;
    @Mock
    private RedisMemoryIndexService redisMemoryIndexService;
    @Mock
    private AiGroupMemoryReviewAuditMapper reviewAuditMapper;
    @Mock
    private MemoryConflictResolver memoryConflictResolver;
    @Mock
    private MemoryMergeService memoryMergeService;
    @Mock
    private ConfirmedMemoryMaintenanceService confirmedMemoryMaintenanceService;
    @Mock
    private MemoryRetentionPolicy memoryRetentionPolicy;
    @Mock
    private MemoryLifecycleAuditService memoryLifecycleAuditService;
    @Mock
    private MemoryLifecycleMetricsService memoryLifecycleMetricsService;

    private CandidateMemoryService candidateMemoryService;

    @BeforeEach
    void setUp() {
        candidateMemoryService = new CandidateMemoryService(
                candidateMapper,
                conflictMapper,
                confirmedMemoryMapper,
                vectorStoreService,
                redisMemoryIndexService,
                new SemanticMemoryConflictDetector(),
                reviewAuditMapper,
                memoryTypeNormalizer,
                memoryConflictResolver,
                memoryMergeService,
                confirmedMemoryMaintenanceService,
                memoryRetentionPolicy,
                memoryLifecycleAuditService,
                memoryLifecycleMetricsService);
    }

    @Test
    void shouldCreateCandidateWhenNoExistingCandidateOrConflict() {
        MemoryExtractionResult.ExtractedMemory extracted = new MemoryExtractionResult.ExtractedMemory();
        extracted.setContent("用户喜欢清淡饮食");
        extracted.setCategory("preference");
        extracted.setConfidence(0.82);

        SemanticMemoryConflictDetector detector = new SemanticMemoryConflictDetector();
        SemanticMemoryFingerprint fingerprint = detector.fingerprint("PREFERENCE", extracted.getContent());

        when(candidateMapper.selectLatestByNormalized(eq("customer-1"), eq("group-1"),
                eq(fingerprint.getNormalizedKey()), eq(fingerprint.getNormalizedValue())))
                .thenReturn(null);
        when(candidateMapper.selectActiveByCustomerId("customer-1", "group-1")).thenReturn(List.of());
        when(confirmedMemoryMapper.selectByGroupIdAndCustomerId("group-1", "customer-1")).thenReturn(List.of());

        candidateMemoryService.processCandidate("group-1", "customer-1", extracted, "source");

        verify(candidateMapper).insert(any(AiGroupMemoryCandidateEntity.class));
        verify(candidateMapper, never()).incrementHitCount(any(), any(), any(), any(), any(), any(), any());
        verify(conflictMapper, never()).insert(any());
    }

    @Test
    void shouldPromoteCandidateWhenHitCountExceedsFiveAndConfidenceHigh() {
        MemoryExtractionResult.ExtractedMemory extracted = new MemoryExtractionResult.ExtractedMemory();
        extracted.setContent("用户喜欢清淡饮食");
        extracted.setCategory("preference");
        extracted.setConfidence(0.86);

        SemanticMemoryConflictDetector detector = new SemanticMemoryConflictDetector();
        SemanticMemoryFingerprint fingerprint = detector.fingerprint("PREFERENCE", extracted.getContent());

        AiGroupMemoryCandidateEntity existing = new AiGroupMemoryCandidateEntity();
        existing.setId(1L);
        existing.setCustomerId("customer-1");
        existing.setGroupId("group-1");
        existing.setMemoryType("PREFERENCE");
        existing.setMemoryKey("用户喜欢清淡饮");
        existing.setNormalizedKey(fingerprint.getNormalizedKey());
        existing.setNormalizedValue(fingerprint.getNormalizedValue());
        existing.setPolarity(fingerprint.getPolarity());
        existing.setMemoryContent("用户喜欢清淡饮食");
        existing.setConfidenceScore(BigDecimal.valueOf(0.82));
        existing.setStatus("CANDIDATE");
        existing.setHitCount(5);
        existing.setLastSeenAt(LocalDateTime.now().minusDays(1));

        when(candidateMapper.selectLatestByNormalized(eq("customer-1"), eq("group-1"),
                eq(fingerprint.getNormalizedKey()), eq(fingerprint.getNormalizedValue())))
                .thenReturn(existing);
        when(candidateMapper.selectById(1L)).thenReturn(existing);
        when(vectorStoreService.saveGroupMemory(any(AiGroupMemoryEntity.class))).thenReturn("embedding-1");
        when(conflictMapper.selectByCandidateId(1L)).thenReturn(List.of());

        candidateMemoryService.processCandidate("group-1", "customer-1", extracted, "source");

        verify(candidateMapper).incrementHitCount(eq(1L), any(), any(), any(), any(), any(), any());
        verify(confirmedMemoryMapper).insert(any(AiGroupMemoryEntity.class));
        verify(vectorStoreService).saveGroupMemory(any(AiGroupMemoryEntity.class));
        verify(confirmedMemoryMapper).updateEmbeddingId(100L, "embedding-1");
        verify(redisMemoryIndexService).addToHashIndex("group-1", "用户喜欢清淡饮食");
        verify(redisMemoryIndexService).cacheRecentMemory(eq("group-1"), eq(100L), eq("用户喜欢清淡饮食"), anyDouble());
        verify(candidateMapper).updateStatus(eq(1L), eq("CONFIRMED"), eq("SYSTEM"), any(), eq(100L));
        verify(reviewAuditMapper).insert(any(AiGroupMemoryReviewAuditEntity.class));
    }

    @Test
    void shouldMarkPolarityConflictAgainstExistingCandidate() {
        MemoryExtractionResult.ExtractedMemory extracted = new MemoryExtractionResult.ExtractedMemory();
        extracted.setContent("用户不吃辣");
        extracted.setCategory("preference");
        extracted.setConfidence(0.86);

        SemanticMemoryConflictDetector detector = new SemanticMemoryConflictDetector();
        SemanticMemoryFingerprint fingerprint = detector.fingerprint("PREFERENCE", extracted.getContent());

        when(candidateMapper.selectLatestByNormalized(eq("customer-1"), eq("group-1"),
                eq(fingerprint.getNormalizedKey()), eq(fingerprint.getNormalizedValue())))
                .thenReturn(null);

        AiGroupMemoryCandidateEntity conflictTarget = new AiGroupMemoryCandidateEntity();
        conflictTarget.setId(2L);
        conflictTarget.setCustomerId("customer-1");
        conflictTarget.setGroupId("group-1");
        conflictTarget.setMemoryType("PREFERENCE");
        conflictTarget.setNormalizedKey(fingerprint.getNormalizedKey());
        conflictTarget.setNormalizedValue("辣");
        conflictTarget.setPolarity("POSITIVE");
        conflictTarget.setStatus("CANDIDATE");

        when(candidateMapper.selectActiveByCustomerId("customer-1", "group-1")).thenReturn(List.of(conflictTarget));
        when(confirmedMemoryMapper.selectByGroupIdAndCustomerId("group-1", "customer-1")).thenReturn(List.of());

        candidateMemoryService.processCandidate("group-1", "customer-1", extracted, "source");

        ArgumentCaptor<AiGroupMemoryConflictEntity> captor = ArgumentCaptor.forClass(AiGroupMemoryConflictEntity.class);
        verify(conflictMapper).insert(captor.capture());
        assertEquals("CANDIDATE", captor.getValue().getConflictTargetType());
        assertEquals(2L, captor.getValue().getConflictTargetId());
        assertEquals("semantic_polarity_opposition", captor.getValue().getConflictReason());
        assertEquals(0, BigDecimal.valueOf(0.98).compareTo(captor.getValue().getConflictScore()));
    }

    @Test
    void shouldMarkSemanticConflictAgainstConfirmedMemory() {
        MemoryExtractionResult.ExtractedMemory extracted = new MemoryExtractionResult.ExtractedMemory();
        extracted.setContent("用户喜欢重辣饮食");
        extracted.setCategory("preference");
        extracted.setConfidence(0.86);

        SemanticMemoryConflictDetector detector = new SemanticMemoryConflictDetector();
        SemanticMemoryFingerprint fingerprint = detector.fingerprint("PREFERENCE", extracted.getContent());

        when(candidateMapper.selectLatestByNormalized(eq("customer-1"), eq("group-1"),
                eq(fingerprint.getNormalizedKey()), eq(fingerprint.getNormalizedValue())))
                .thenReturn(null);
        when(candidateMapper.selectActiveByCustomerId("customer-1", "group-1")).thenReturn(List.of());

        AiGroupMemoryEntity confirmed = new AiGroupMemoryEntity();
        confirmed.setId(8L);
        confirmed.setGroupId("group-1");
        confirmed.setCustomerId("customer-1");
        confirmed.setMemoryType("PREFERENCE");
        confirmed.setMemoryContent("用户喜欢清淡饮食");
        when(confirmedMemoryMapper.selectByGroupIdAndCustomerId("group-1", "customer-1"))
                .thenReturn(List.of(confirmed));

        candidateMemoryService.processCandidate("group-1", "customer-1", extracted, "source");

        ArgumentCaptor<AiGroupMemoryConflictEntity> captor = ArgumentCaptor.forClass(AiGroupMemoryConflictEntity.class);
        verify(conflictMapper).insert(captor.capture());
        assertEquals("CONFIRMED", captor.getValue().getConflictTargetType());
        assertEquals(8L, captor.getValue().getConflictTargetId());
        assertEquals("semantic_value_mismatch", captor.getValue().getConflictReason());
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
        verify(conflictMapper).updateResolutionStatus(7L, "RESOLVED", "manual_confirm", null);
        verify(candidateMapper).updateStatus(eq(9L), eq("CONFIRMED"), eq("doctor-a"), any(), eq(100L));
        verify(reviewAuditMapper).insert(any(AiGroupMemoryReviewAuditEntity.class));
    }

    @Test
    void shouldUpdateExistingPreferenceWhenManualConfirmFindsLatestWinsConflict() {
        AiGroupMemoryCandidateEntity candidate = new AiGroupMemoryCandidateEntity();
        candidate.setId(10L);
        candidate.setCustomerId("customer-1");
        candidate.setGroupId("group-1");
        candidate.setMemoryType("PREFERENCE");
        candidate.setMemoryKey("用户喜欢重辣饮");
        candidate.setMemoryContent("用户喜欢重辣饮食");
        candidate.setConfidenceScore(BigDecimal.valueOf(0.91));
        candidate.setStatus("CONFLICT");
        candidate.setSourceCount(2);
        candidate.setEvidenceCount(2);

        AiGroupMemoryEntity confirmed = new AiGroupMemoryEntity();
        confirmed.setId(12L);
        confirmed.setCustomerId("customer-1");
        confirmed.setGroupId("group-1");
        confirmed.setMemoryType("PREFERENCE");
        confirmed.setMemoryContent("用户喜欢清淡饮食");

        SemanticMemoryConflictDetector detector = new SemanticMemoryConflictDetector();
        SemanticMemoryFingerprint fingerprint = detector.fingerprint("PREFERENCE", candidate.getMemoryContent());
        MemoryConflictResolution resolution = MemoryConflictResolution.builder()
                .relationType(MemoryRelationType.CONFLICT)
                .strategy(MemoryConflictResolution.ResolutionStrategy.UPDATE_EXISTING_PREFERENCE)
                .reasonCode("preference_latest_wins")
                .summary("latest preference should win")
                .score(0.92d)
                .build();

        AiGroupMemoryEntity merged = new AiGroupMemoryEntity();
        merged.setId(12L);
        merged.setCustomerId("customer-1");
        merged.setGroupId("group-1");
        merged.setMemoryType("PREFERENCE");
        merged.setMemoryContent("用户喜欢重辣饮食");

        when(candidateMapper.selectById(10L)).thenReturn(candidate);
        when(confirmedMemoryMapper.selectByGroupIdAndCustomerId("group-1", "customer-1"))
                .thenReturn(List.of(confirmed));
        when(memoryConflictResolver.resolve(eq("PREFERENCE"), any(), eq(candidate), eq(confirmed)))
                .thenReturn(resolution);
        when(memoryMergeService.mergeAsLatestPreference(eq(confirmed), eq(candidate), eq("preference_latest_wins")))
                .thenReturn(merged);

        candidateMemoryService.confirmCandidate(10L, "doctor-a");

        verify(confirmedMemoryMaintenanceService).refreshConfirmedMemory(merged);
        verify(candidateMapper).updateStatus(eq(10L), eq("CONFIRMED"), eq("doctor-a"), any(), eq(12L));
        verify(reviewAuditMapper).insert(any(AiGroupMemoryReviewAuditEntity.class));
        verify(confirmedMemoryMapper, never()).insert(any(AiGroupMemoryEntity.class));
    }
}
