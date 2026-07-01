package com.lx.governance.agentplatform.model;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单条 Agent handoff 中间产物。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformAgentHandoffItem {
    private String key;
    private PlatformHandoffItemType type;
    private String summary;
    private Object payload;
    private PlatformContextVisibility visibility;
    private String sourceAgentId;
    private String sourceToolName;
    private Map<String, Object> metadata;
}
