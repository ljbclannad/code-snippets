package com.lx.governance.vectormemory.domain.candidate;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class SemanticMemoryFingerprint {

    String normalizedKey;
    String normalizedValue;
    String polarity;
    String semanticType;
    String semanticSubject;
    boolean historical;
    boolean conditional;
}
