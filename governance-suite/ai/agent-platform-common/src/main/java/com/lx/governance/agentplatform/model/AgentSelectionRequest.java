package com.lx.governance.agentplatform.model;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import lombok.Builder;
import lombok.Value;

/**
 * 平台 selector 的统一输入。
 */
@Value
@Builder(toBuilder = true)
public class AgentSelectionRequest {

    String goal;

    String taskType;

    AgentAccessContext accessContext;

    @Builder.Default
    Set<String> contextSlots = new LinkedHashSet<>();

    @Builder.Default
    Set<String> riskSignals = new LinkedHashSet<>();

    @Builder.Default
    Set<String> availableTools = new LinkedHashSet<>();

    @Builder.Default
    Map<String, Object> rolloutConfig = new LinkedHashMap<>();
}
