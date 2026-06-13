package com.lx.governance.profile.service;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class SemanticProfileConflictAnalysis {

    boolean duplicate;
    boolean conflict;
    String conflictType;
    String conflictReason;
    double conflictScore;

    public static SemanticProfileConflictAnalysis noConflict() {
        return SemanticProfileConflictAnalysis.builder()
                .duplicate(false)
                .conflict(false)
                .conflictType("NONE")
                .conflictReason("no_conflict")
                .conflictScore(0.0)
                .build();
    }
}
