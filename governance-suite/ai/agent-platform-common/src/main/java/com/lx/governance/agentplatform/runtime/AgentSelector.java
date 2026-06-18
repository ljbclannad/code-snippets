package com.lx.governance.agentplatform.runtime;

import com.lx.governance.agentplatform.model.AgentSelectionDecision;
import com.lx.governance.agentplatform.model.AgentSelectionRequest;

/**
 * 平台统一 Agent Selector。
 */
public interface AgentSelector {

    AgentSelectionDecision select(AgentSelectionRequest request);
}
