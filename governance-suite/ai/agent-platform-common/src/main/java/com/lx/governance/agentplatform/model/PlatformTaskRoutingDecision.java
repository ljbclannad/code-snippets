package com.lx.governance.agentplatform.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 平台统一入口路由判定结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformTaskRoutingDecision {

    private PlatformConversationRouteType route;

    private String taskType;

    private String targetAgentId;

    private String workflowCode;

    private PlatformRoutingTriggerMode triggerMode;

    private AgentSelectionMode selectionMode;

    @Builder.Default
    private List<String> reasons = new ArrayList<>();

    @Builder.Default
    private Map<String, String> layerDecisions = new LinkedHashMap<>();

    private String summary;

    public boolean shouldUpgradeToTask() {
        return route == PlatformConversationRouteType.TASK;
    }
}
