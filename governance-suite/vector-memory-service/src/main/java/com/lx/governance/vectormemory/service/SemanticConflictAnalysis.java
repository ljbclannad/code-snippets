package com.lx.governance.vectormemory.service;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class SemanticConflictAnalysis {

    boolean duplicate;
    boolean conflict;
    String conflictType;
    String conflictReason;
    double conflictScore;

    public static SemanticConflictAnalysis noConflict() {
        return SemanticConflictAnalysis.builder()
                .duplicate(false)
                .conflict(false)
                .conflictType("NONE")
                .conflictReason("no_conflict")
                .conflictScore(0.0)
                .build();
    }
}
