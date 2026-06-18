package com.lx.governance.agentplatform.runtime;

import com.lx.governance.agentplatform.model.AgentSelectionDecision;
import com.lx.governance.agentplatform.model.AgentSelectionRequest;

/**
 * 平台统一 Agent Selector。
 * <p>
 * 负责根据请求上下文选择一个或多个可执行 agent。
 */
public interface AgentSelector {

    AgentSelectionDecision select(AgentSelectionRequest request);
}
