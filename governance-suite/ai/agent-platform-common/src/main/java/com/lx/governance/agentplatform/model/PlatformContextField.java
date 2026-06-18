package com.lx.governance.agentplatform.model;

import lombok.Builder;
import lombok.Value;

/**
 * 平台上下文字段。
 */
@Value
@Builder(toBuilder = true)
public class PlatformContextField {

    String fieldKey;

    PlatformContextDomain domain;

    PlatformContextVisibility visibility;

    String source;

    Object value;
}
