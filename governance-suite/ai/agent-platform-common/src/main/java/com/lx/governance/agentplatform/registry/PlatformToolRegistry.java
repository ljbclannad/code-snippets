package com.lx.governance.agentplatform.registry;

import java.util.List;
import java.util.Optional;

import com.lx.governance.agentplatform.model.AgentAccessContext;
import com.lx.governance.agentplatform.model.ToolDefinition;
import com.lx.governance.agentplatform.model.ToolDescriptor;

/**
 * 平台统一工具注册中心。
 */
public interface PlatformToolRegistry {

    void register(ToolDefinition definition);

    Optional<ToolDefinition> findDefinition(String toolName);

    Optional<ToolDefinition> findVisibleDefinition(String toolName, AgentAccessContext accessContext);

    List<ToolDefinition> listDefinitions();

    List<ToolDefinition> listVisibleDefinitions(AgentAccessContext accessContext);

    List<ToolDescriptor> listToolDescriptors();
}
