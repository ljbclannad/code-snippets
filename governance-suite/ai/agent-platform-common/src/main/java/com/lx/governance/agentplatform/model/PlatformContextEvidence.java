package com.lx.governance.agentplatform.model;

import lombok.Builder;
import lombok.Value;

/**
 * 平台统一证据记录。
 */
@Value
@Builder(toBuilder = true)
public class PlatformContextEvidence {

    String evidenceKey;

    String source;

    String summary;

    PlatformContextVisibility visibility;

    Object payload;
}
