package com.health.agent.engine;

import java.util.List;

import com.health.agent.dto.agent.AgentExecutionCommand;
import com.health.agent.engine.subagent.AgentSubAgent;
import com.health.agent.model.AgentContext;
import com.lx.governance.agentplatform.model.PromptTemplateType;
import com.lx.governance.agentplatform.model.ToolDescriptor;

/**
 * 场景级 Prompt 提供器，供运行时注入使用。
 */
public interface AgentPromptProvider {

    String buildSubAgentSystemPromptFromCatalog(AgentSubAgent subAgent, List<ToolDescriptor> availableTools);

    String buildUserPrompt(String goal, String customerId);

    String buildUserPrompt(AgentExecutionCommand command);

    String buildProtocolRepairPrompt(String errorMessage);

    String buildContextPromptSection(AgentContext context);

    default String currentPromptVersion(PromptTemplateType type) {
        return "unknown";
    }
}
