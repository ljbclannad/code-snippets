package com.lx.governance.agentplatform.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

/**
 * 平台统一工具输出载荷。
 */
@Value
@Builder(toBuilder = true)
public class PlatformToolOutput {

    String toolName;

    String summary;

    PlatformContextVisibility visibility;

    Object data;

    @Builder.Default
    Map<String, Object> stateUpdates = new LinkedHashMap<>();

    @Singular("evidence")
    List<PlatformContextEvidence> evidences;
}
