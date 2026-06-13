package com.lx.governance.profile.service;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class SemanticProfileFingerprint {

    String fieldPath;
    String semanticKey;
    String normalizedValue;
    String polarity;
    String semanticType;
}
