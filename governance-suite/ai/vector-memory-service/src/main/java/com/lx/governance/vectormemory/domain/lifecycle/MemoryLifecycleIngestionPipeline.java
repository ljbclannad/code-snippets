package com.lx.governance.vectormemory.domain.lifecycle;

import com.lx.governance.vectormemory.domain.candidate.CandidateMemoryService;
import com.lx.governance.vectormemory.integration.storage.RedisMemoryIndexService;
import com.lx.governance.vectormemory.support.observability.MemoryLifecycleAuditService;
import com.lx.governance.vectormemory.support.observability.MemoryLifecycleMetricsService;

import org.springframework.stereotype.Service;

import com.lx.governance.vectormemory.dto.MemoryExtractionResult;
import com.lx.governance.vectormemory.dto.MemoryLifecycleDecision;
import com.lx.governance.vectormemory.dto.MemoryLifecycleDecision.LifecycleAction;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MemoryLifecycleIngestionPipeline {

    private final RedisMemoryIndexService redisMemoryIndexService;
    private final CandidateMemoryService candidateMemoryService;
    private final MemoryTypeNormalizer memoryTypeNormalizer;
    private final MemoryNoiseFilter memoryNoiseFilter;
    private final MemoryWorthinessEvaluator memoryWorthinessEvaluator;
    private final MemoryLifecycleAuditService memoryLifecycleAuditService;
    private final MemoryLifecycleMetricsService memoryLifecycleMetricsService;

    public MemoryLifecycleDecision ingest(String groupId,
                                          String customerId,
                                          MemoryExtractionResult.ExtractedMemory extractedMemory,
                                          String sourceMessage) {
        String normalizedMemoryType = memoryTypeNormalizer.normalize(extractedMemory.getCategory());

        MemoryNoiseFilter.NoiseFilterResult noiseFilterResult = memoryNoiseFilter.evaluate(extractedMemory);
        if (!noiseFilterResult.accepted()) {
            MemoryLifecycleDecision decision = MemoryLifecycleDecision.builder()
                    .groupId(groupId)
                    .customerId(customerId)
                    .extractedMemory(extractedMemory)
                    .normalizedMemoryType(normalizedMemoryType)
                    .sourceMessage(sourceMessage)
                    .action(LifecycleAction.REJECT_NOISE)
                    .lifecycleStatus("REJECTED")
                    .decisionReasonCode(noiseFilterResult.reasonCode())
                    .decisionSummary(noiseFilterResult.summary())
                    .build();
            memoryLifecycleAuditService.recordIngestionDecision(decision);
            memoryLifecycleMetricsService.recordIngestionDecision(decision);
            return decision;
        }

        MemoryWorthinessEvaluator.WorthinessResult worthinessResult =
                memoryWorthinessEvaluator.evaluate(extractedMemory, normalizedMemoryType);
        if (!worthinessResult.accepted()) {
            MemoryLifecycleDecision decision = MemoryLifecycleDecision.builder()
                    .groupId(groupId)
                    .customerId(customerId)
                    .extractedMemory(extractedMemory)
                    .normalizedMemoryType(normalizedMemoryType)
                    .sourceMessage(sourceMessage)
                    .action(LifecycleAction.REJECT_LOW_WORTHINESS)
                    .lifecycleStatus("REJECTED")
                    .decisionReasonCode(worthinessResult.reasonCode())
                    .worthinessScore(worthinessResult.score())
                    .decisionSummary(worthinessResult.summary())
                    .build();
            memoryLifecycleAuditService.recordIngestionDecision(decision);
            memoryLifecycleMetricsService.recordIngestionDecision(decision);
            return decision;
        }

        if (redisMemoryIndexService != null) {
            try {
                if (redisMemoryIndexService.isDuplicate(groupId, extractedMemory.getContent())) {
                    MemoryLifecycleDecision decision = MemoryLifecycleDecision.builder()
                            .groupId(groupId)
                            .customerId(customerId)
                            .extractedMemory(extractedMemory)
                            .normalizedMemoryType(normalizedMemoryType)
                            .sourceMessage(sourceMessage)
                            .action(LifecycleAction.REJECT_DUPLICATE)
                            .lifecycleStatus("REJECTED")
                            .decisionReasonCode("redis_duplicate")
                            .worthinessScore(worthinessResult.score())
                            .decisionSummary("memory was rejected by redis duplicate index")
                            .build();
                    memoryLifecycleAuditService.recordIngestionDecision(decision);
                    memoryLifecycleMetricsService.recordIngestionDecision(decision);
                    return decision;
                }
            } catch (Exception ex) {
                MemoryLifecycleDecision auditOnlyDecision = MemoryLifecycleDecision.builder()
                        .groupId(groupId)
                        .customerId(customerId)
                        .extractedMemory(extractedMemory)
                        .normalizedMemoryType(normalizedMemoryType)
                        .sourceMessage(sourceMessage)
                        .action(LifecycleAction.INGEST_CANDIDATE)
                        .lifecycleStatus("CANDIDATE")
                        .decisionReasonCode("redis_duplicate_check_failed")
                        .worthinessScore(worthinessResult.score())
                        .decisionSummary("redis duplicate check failed, continue with candidate ingestion")
                        .build();
                memoryLifecycleAuditService.recordIngestionDecision(auditOnlyDecision);
            }
        }

        MemoryLifecycleDecision decision = MemoryLifecycleDecision.builder()
                .groupId(groupId)
                .customerId(customerId)
                .extractedMemory(extractedMemory)
                .normalizedMemoryType(normalizedMemoryType)
                .sourceMessage(sourceMessage)
                .action(LifecycleAction.INGEST_CANDIDATE)
                .lifecycleStatus("CANDIDATE")
                .decisionReasonCode(worthinessResult.reasonCode())
                .worthinessScore(worthinessResult.score())
                .decisionSummary(worthinessResult.summary())
                .build();

        candidateMemoryService.processLifecycleDecision(decision);
        memoryLifecycleAuditService.recordIngestionDecision(decision);
        memoryLifecycleMetricsService.recordIngestionDecision(decision);
        return decision;
    }
}
