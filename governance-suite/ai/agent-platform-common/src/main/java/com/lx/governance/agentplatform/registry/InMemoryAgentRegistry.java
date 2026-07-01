package com.lx.governance.agentplatform.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Component;

import com.lx.governance.agentplatform.model.AgentAccessContext;
import com.lx.governance.agentplatform.model.AgentDefinition;
import com.lx.governance.agentplatform.model.AgentStatus;

/**
 * 首版注册中心先使用内存实现，后续可切换到配置中心或数据库。
 */
@Component
public class InMemoryAgentRegistry implements AgentRegistry {

    private final ConcurrentMap<String, AgentDefinition> definitions = new ConcurrentHashMap<>();

    @Override
    public AgentDefinition register(AgentDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("definition must not be null");
        }
        if (definition.getAgentId() == null || definition.getAgentId().isBlank()) {
            throw new IllegalArgumentException("agentId must not be blank");
        }
        String version = normalizedVersion(definition.getVersion());
        if (definition.getStatus() == AgentStatus.ENABLED) {
            disableOtherVersions(definition.getAgentId(), version);
        }
        AgentDefinition normalized = definition.toBuilder()
                .version(version)
                .build();
        definitions.put(storageKey(normalized.getAgentId(), version), normalized);
        return normalized;
    }

    @Override
    public Optional<AgentDefinition> findById(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return Optional.empty();
        }
        return listVersions(agentId).stream()
                .filter(definition -> definition.getStatus() == AgentStatus.ENABLED)
                .findFirst()
                .or(() -> listVersions(agentId).stream().findFirst());
    }

    @Override
    public List<AgentDefinition> listAll() {
        return definitions.values().stream()
                .filter(Objects::nonNull)
                .sorted((left, right) -> left.getAgentId().compareTo(right.getAgentId()))
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    @Override
    public List<AgentDefinition> listVersions(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return List.of();
        }
        return definitions.values().stream()
                .filter(Objects::nonNull)
                .filter(definition -> agentId.equals(definition.getAgentId()))
                .sorted((left, right) -> normalizedVersion(left.getVersion()).compareTo(normalizedVersion(right.getVersion())))
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    @Override
    public List<AgentDefinition> listVisible(AgentAccessContext accessContext) {
        return definitions.values().stream()
                .filter(Objects::nonNull)
                .filter(definition -> isVisible(definition, accessContext))
                .sorted((left, right) -> left.getAgentId().compareTo(right.getAgentId()))
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    @Override
    public Optional<AgentDefinition> findEnabledByCapability(
            String capability,
            String taskType,
            AgentAccessContext accessContext) {
        return listVisible(accessContext).stream()
                .filter(definition -> definition.getStatus() == AgentStatus.ENABLED)
                .filter(definition -> definition.getCapabilities() != null
                        && definition.getCapabilities().contains(capability))
                .filter(definition -> supportsTaskType(definition, taskType))
                .findFirst();
    }

    @Override
    public AgentDefinition updateStatus(String agentId, AgentStatus status) {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId must not be blank");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        AgentDefinition existing = findById(agentId)
                .orElseThrow(() -> new IllegalArgumentException("agent not found: " + agentId));
        if (status == AgentStatus.ENABLED) {
            disableOtherVersions(existing.getAgentId(), existing.getVersion());
        }
        AgentDefinition updated = existing.toBuilder()
                .status(status)
                .build();
        definitions.put(storageKey(updated.getAgentId(), normalizedVersion(updated.getVersion())), updated);
        return updated;
    }

    @Override
    public AgentDefinition switchVersion(String agentId, String version) {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId must not be blank");
        }
        String normalizedVersion = normalizedVersion(version);
        AgentDefinition target = definitions.get(storageKey(agentId, normalizedVersion));
        if (target == null) {
            throw new IllegalArgumentException("agent version not found: " + agentId + "@" + normalizedVersion);
        }
        disableOtherVersions(agentId, normalizedVersion);
        AgentDefinition updated = target.toBuilder()
                .status(AgentStatus.ENABLED)
                .build();
        definitions.put(storageKey(agentId, normalizedVersion), updated);
        return updated;
    }

    private void disableOtherVersions(String agentId, String keepVersion) {
        listVersions(agentId).stream()
                .filter(definition -> !normalizedVersion(definition.getVersion()).equals(keepVersion))
                .filter(definition -> definition.getStatus() == AgentStatus.ENABLED)
                .forEach(definition -> definitions.put(
                        storageKey(definition.getAgentId(), normalizedVersion(definition.getVersion())),
                        definition.toBuilder().status(AgentStatus.DISABLED).build()));
    }

    private String storageKey(String agentId, String version) {
        return agentId + "@" + normalizedVersion(version);
    }

    private String normalizedVersion(String version) {
        return version == null || version.isBlank() ? "default" : version;
    }

    private boolean supportsTaskType(AgentDefinition definition, String taskType) {
        if (definition == null || definition.getSupportedTaskTypes() == null || definition.getSupportedTaskTypes().isEmpty()) {
            return true;
        }
        if (taskType == null || taskType.isBlank() || "NONE".equals(taskType)) {
            return true;
        }
        return definition.getSupportedTaskTypes().contains(taskType);
    }

    private boolean isVisible(AgentDefinition definition, AgentAccessContext accessContext) {
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
