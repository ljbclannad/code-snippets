package com.lx.governance.agentplatform.registry;

import java.util.List;
import java.util.Optional;

import com.lx.governance.agentplatform.model.AgentAccessContext;
import com.lx.governance.agentplatform.model.AgentDefinition;
import com.lx.governance.agentplatform.model.AgentStatus;

/**
 * 平台统一 Agent 注册中心接口。
 */
public interface AgentRegistry {

    AgentDefinition register(AgentDefinition definition);

    Optional<AgentDefinition> findById(String agentId);

    List<AgentDefinition> listAll();

    List<AgentDefinition> listVersions(String agentId);

    List<AgentDefinition> listVisible(AgentAccessContext accessContext);

    Optional<AgentDefinition> findEnabledByCapability(
            String capability,
            String taskType,
            AgentAccessContext accessContext);

    AgentDefinition updateStatus(String agentId, AgentStatus status);

    AgentDefinition switchVersion(String agentId, String version);

    default AgentDefinition enable(String agentId) {
        return updateStatus(agentId, AgentStatus.ENABLED);
    }

    default AgentDefinition disable(String agentId) {
        return updateStatus(agentId, AgentStatus.DISABLED);
    }
}
