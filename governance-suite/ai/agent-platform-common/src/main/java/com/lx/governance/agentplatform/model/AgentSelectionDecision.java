package com.lx.governance.agentplatform.model;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 平台 selector 的统一输出。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentSelectionDecision {

    private AgentSelectionMode mode;

    @Builder.Default
    private List<String> selectedAgentIds = new ArrayList<>();

    @Builder.Default
    private List<String> reasons = new ArrayList<>();

    public String primaryAgentId() {
        return selectedAgentIds == null || selectedAgentIds.isEmpty() ? null : selectedAgentIds.get(0);
    }
}
