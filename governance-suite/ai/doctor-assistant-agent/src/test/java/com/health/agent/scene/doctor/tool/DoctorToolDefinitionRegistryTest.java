package com.health.agent.scene.doctor.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.lx.governance.agentplatform.model.ToolDefinition;
import com.lx.governance.agentplatform.model.ToolIdempotencyPolicy;

class DoctorToolDefinitionRegistryTest {

    @Test
    void shouldExposePlatformLevelToolDefinitions() {
        DoctorToolDefinitionRegistry registry = new DoctorToolDefinitionRegistry();

        ToolDefinition getProfile = registry.findDefinition("get_patient_profile").orElseThrow();
        assertEquals("doctor-assistant-agent", getProfile.getOwner());
        assertEquals("LOW", getProfile.getRiskLevel());
        assertEquals(3_000L, getProfile.getTimeoutMs());
        assertEquals(2, getProfile.getRetryPolicy().getMaxAttempts());
        assertEquals(ToolIdempotencyPolicy.IDEMPOTENT, getProfile.getIdempotencyPolicy());
        assertTrue(getProfile.isAuditEnabled());
        assertEquals(20, getProfile.getQpsLimit());
        assertEquals(3, getProfile.getCircuitBreakerFailureThreshold());

        ToolDefinition createFollowUp = registry.findDefinition("create_follow_up_task").orElseThrow();
        assertEquals(ToolIdempotencyPolicy.NON_IDEMPOTENT, createFollowUp.getIdempotencyPolicy());
        assertEquals(4_000L, createFollowUp.getTimeoutMs());
        assertEquals(1, createFollowUp.getRetryPolicy().getMaxAttempts());
        assertEquals(5, createFollowUp.getQpsLimit());
        assertEquals(2, createFollowUp.getCircuitBreakerFailureThreshold());

        assertTrue(registry.listToolDescriptors().stream()
                .anyMatch(descriptor -> "recommend_medical_review".equals(descriptor.getToolName())));
    }
}
