package com.lx.governance.vectormemory.service;

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
