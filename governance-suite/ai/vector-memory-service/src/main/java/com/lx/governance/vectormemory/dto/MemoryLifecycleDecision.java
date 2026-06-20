package com.lx.governance.vectormemory.dto;

import java.math.BigDecimal;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MemoryLifecycleDecision {

    private String groupId;
    private String customerId;
    private MemoryExtractionResult.ExtractedMemory extractedMemory;
    private String normalizedMemoryType;
    private String sourceMessage;
    private LifecycleAction action;
    private String lifecycleStatus;
    private String decisionReasonCode;
    private BigDecimal worthinessScore;
    private String decisionSummary;

    public enum LifecycleAction {
        INGEST_CANDIDATE,
        REJECT_NOISE,
        REJECT_LOW_WORTHINESS,
        REJECT_DUPLICATE
    }
}
