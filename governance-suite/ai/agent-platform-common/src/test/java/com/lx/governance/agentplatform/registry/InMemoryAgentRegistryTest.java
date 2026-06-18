package com.lx.governance.agentplatform.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.lx.governance.agentplatform.model.AgentAccessContext;
import com.lx.governance.agentplatform.model.AgentDefinition;
import com.lx.governance.agentplatform.model.AgentStatus;
import com.lx.governance.agentplatform.model.AgentType;

class InMemoryAgentRegistryTest {

    private InMemoryAgentRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new InMemoryAgentRegistry();
        registry.register(AgentDefinition.builder()
                .agentId("doctor.WeightAnalysisAgent")
                .displayName("WeightAnalysisAgent")
                .agentType(AgentType.SUB_AGENT)
                .riskLevel("MEDIUM")
                .owner("test")
                .version("v1")
                .status(AgentStatus.ENABLED)
                .build());
        registry.register(AgentDefinition.builder()
                .agentId("doctor.WeightAnalysisAgent")
                .displayName("WeightAnalysisAgent")
                .agentType(AgentType.SUB_AGENT)
                .riskLevel("MEDIUM")
                .owner("test")
                .version("v2")
                .status(AgentStatus.DISABLED)
                .build());
    }

    @Test
    void shouldDisableRegisteredAgent() {
        AgentDefinition updated = registry.disable("doctor.WeightAnalysisAgent");

        assertEquals(AgentStatus.DISABLED, updated.getStatus());
        assertEquals(AgentStatus.DISABLED,
                registry.findById("doctor.WeightAnalysisAgent").orElseThrow().getStatus());
    }

    @Test
    void shouldEnableDisabledAgent() {
        registry.disable("doctor.WeightAnalysisAgent");

        AgentDefinition updated = registry.enable("doctor.WeightAnalysisAgent");

        assertEquals(AgentStatus.ENABLED, updated.getStatus());
    }

    @Test
    void shouldRejectUnknownAgentWhenUpdatingStatus() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> registry.disable("doctor.UnknownAgent"));

        assertEquals("agent not found: doctor.UnknownAgent", error.getMessage());
    }

    @Test
    void shouldSwitchActiveVersionForLogicalAgent() {
        AgentDefinition updated = registry.switchVersion("doctor.WeightAnalysisAgent", "v2");

        assertEquals("v2", updated.getVersion());
        assertEquals(AgentStatus.ENABLED, updated.getStatus());
        assertEquals("v2", registry.findById("doctor.WeightAnalysisAgent").orElseThrow().getVersion());
        assertEquals(AgentStatus.DISABLED,
                registry.listVersions("doctor.WeightAnalysisAgent").stream()
                        .filter(definition -> "v1".equals(definition.getVersion()))
                        .findFirst()
                        .orElseThrow()
                        .getStatus());
    }

    @Test
    void shouldRejectUnknownVersionWhenSwitching() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> registry.switchVersion("doctor.WeightAnalysisAgent", "v9"));

        assertEquals("agent version not found: doctor.WeightAnalysisAgent@v9", error.getMessage());
    }

    @Test
    void shouldFilterVisibleAgentsByGroupUserAndTenant() {
        registry.register(AgentDefinition.builder()
                .agentId("doctor.RiskTriageAgent")
                .displayName("RiskTriageAgent")
                .agentType(AgentType.SUB_AGENT)
                .riskLevel("HIGH")
                .owner("test")
                .version("v1")
                .visibleGroupId("G-001")
                .visibleUserId("U-001")
                .visibleTenantId("T-001")
                .status(AgentStatus.ENABLED)
                .build());

        AgentAccessContext matched = AgentAccessContext.builder()
                .groupId("G-001")
                .userId("U-001")
                .tenantId("T-001")
                .build();
        AgentAccessContext rejected = AgentAccessContext.builder()
                .groupId("G-002")
                .userId("U-001")
                .tenantId("T-001")
                .build();

        assertTrue(registry.listVisible(matched).stream()
                .anyMatch(definition -> "doctor.RiskTriageAgent".equals(definition.getAgentId())));
        assertFalse(registry.listVisible(rejected).stream()
                .anyMatch(definition -> "doctor.RiskTriageAgent".equals(definition.getAgentId())));
    }
}
