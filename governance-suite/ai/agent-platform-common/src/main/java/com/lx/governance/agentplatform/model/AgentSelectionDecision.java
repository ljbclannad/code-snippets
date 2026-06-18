package com.lx.governance.agentplatform.model;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 平台 selector 的统一输出。
 * <p>
 * 用于把单 agent、串行和并行执行计划统一成一个决策对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentSelectionDecision {

    /** 选择模式。 */
    private AgentSelectionMode mode;

    /** 被选中的 agent 列表。 */
    @Builder.Default
    private List<String> selectedAgentIds = new ArrayList<>();

    /** 命中的原因。 */
    @Builder.Default
    private List<String> reasons = new ArrayList<>();

    public String primaryAgentId() {
        return selectedAgentIds == null || selectedAgentIds.isEmpty() ? null : selectedAgentIds.get(0);
    }
}
