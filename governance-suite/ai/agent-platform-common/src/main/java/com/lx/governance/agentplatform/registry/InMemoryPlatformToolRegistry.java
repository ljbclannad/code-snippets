package com.lx.governance.agentplatform.registry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.lx.governance.agentplatform.model.AgentAccessContext;
import com.lx.governance.agentplatform.model.ToolDefinition;
import com.lx.governance.agentplatform.model.ToolDescriptor;

/**
 * 内存版平台工具注册中心。
 */
public class InMemoryPlatformToolRegistry implements PlatformToolRegistry {

    private final Map<String, ToolDefinition> definitions = new LinkedHashMap<>();

    @Override
    public void register(ToolDefinition definition) {
        if (definition == null || definition.getToolName() == null || definition.getToolName().isBlank()) {
            return;
        }
        definitions.put(definition.getToolName(), definition);
    }

    @Override
    public Optional<ToolDefinition> findDefinition(String toolName) {
        return Optional.ofNullable(definitions.get(toolName));
    }

    @Override
    public Optional<ToolDefinition> findVisibleDefinition(String toolName, AgentAccessContext accessContext) {
        return findDefinition(toolName)
                .filter(definition -> isVisible(definition, accessContext));
    }

    @Override
    public List<ToolDefinition> listDefinitions() {
        return List.copyOf(definitions.values());
    }

    @Override
    public List<ToolDefinition> listVisibleDefinitions(AgentAccessContext accessContext) {
        return definitions.values().stream()
                .filter(Objects::nonNull)
                .filter(definition -> isVisible(definition, accessContext))
                .toList();
    }

    @Override
    public List<ToolDescriptor> listToolDescriptors() {
        return definitions.values().stream()
                .map(definition -> ToolDescriptor.builder()
                        .toolName(definition.getToolName())
                        .description(definition.getDescription())
                        .riskLevel(definition.getRiskLevel())
                        .inputSchema(definition.getSchema())
                        .build())
                .toList();
    }

    private boolean isVisible(ToolDefinition definition, AgentAccessContext accessContext) {
        if (definition == null) {
            return false;
        }
        return matchesScope(definition.getVisibleGroupIds(), accessContext != null ? accessContext.getGroupId() : null)
                && matchesScope(definition.getVisibleUserIds(), accessContext != null ? accessContext.getUserId() : null)
                && matchesScope(definition.getVisibleTenantIds(), accessContext != null ? accessContext.getTenantId() : null);
    }

    private boolean matchesScope(List<String> allowedValues, String actualValue) {
        if (allowedValues == null || allowedValues.isEmpty()) {
            return true;
        }
        if (actualValue == null || actualValue.isBlank()) {
            return false;
        }
        return allowedValues.contains(actualValue);
    }
}
