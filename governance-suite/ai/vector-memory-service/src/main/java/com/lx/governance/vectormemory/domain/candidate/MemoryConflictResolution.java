package com.lx.governance.vectormemory.domain.candidate;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class MemoryConflictResolution {

    MemoryRelationType relationType;
    ResolutionStrategy strategy;
    String reasonCode;
    String summary;
    double score;

    public enum ResolutionStrategy {
        ABSORB_EXISTING,
        UPDATE_EXISTING_PREFERENCE,
        APPEND_AS_VERSION,
        APPEND_WITH_TIME_WINDOW,
        REQUIRE_REVIEW
    }
}
