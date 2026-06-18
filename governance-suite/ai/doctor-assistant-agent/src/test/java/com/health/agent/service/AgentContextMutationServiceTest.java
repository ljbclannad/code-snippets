package com.health.agent.service;

import com.health.agent.model.ActionDecision;
import com.health.agent.model.ActionResult;
import com.health.agent.model.AgentContext;
import com.lx.governance.agentplatform.model.PlatformContextEvidence;
import com.lx.governance.agentplatform.model.PlatformContextVisibility;
import com.lx.governance.agentplatform.model.PlatformToolError;
import com.lx.governance.agentplatform.model.PlatformToolOutput;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentContextMutationServiceTest {

    private final AgentContextMutationService service = new AgentContextMutationService();

    @Test
    void shouldApplyPlatformToolOutputIntoAgentContext() {
        AgentContext context = AgentContext.initialize("C001", "D001", "test");
        ActionDecision action = ActionDecision.builder()
                .toolName("get_patient_profile")
                .input(Map.of("customerId", "C001"))
                .reason("test")
                .build();
        PlatformToolOutput output = PlatformToolOutput.builder()
                .toolName("get_patient_profile")
                .summary("已获取患者基础档案")
                .visibility(PlatformContextVisibility.DOCTOR_VISIBLE)
                .data(Map.of("customerId", "C001"))
                .stateUpdates(Map.of("patient_profile", Map.of("customerId", "C001")))
                .evidence(PlatformContextEvidence.builder()
                        .evidenceKey("get_patient_profile")
                        .source("PATIENT_PROFILE")
                        .summary("已获取患者基础档案")
                        .visibility(PlatformContextVisibility.AUDIT_ONLY)
                        .payload(Map.of("customerId", "C001"))
                        .build())
                .build();

        service.applyActionResult(context, action, ActionResult.success(output), "{}");

        assertEquals(PlatformContextVisibility.DOCTOR_VISIBLE,
                context.getSlots().get("patient_profile").getVisibility());
        assertEquals(1, context.getEvidenceRecords().size());
        assertEquals(PlatformContextVisibility.AUDIT_ONLY,
                context.getEvidenceRecords().get(0).getVisibility());
    }

    @Test
    void shouldFormatStructuredPlatformToolErrorObservation() {
        String observation = service.formatObservation(ActionResult.builder()
                .success(false)
                .output(PlatformToolError.builder()
                        .code("TIMEOUT")
                        .message("工具超时")
                        .retriable(true)
                        .build())
                .errorMessage("工具超时")
                .needsApproval(false)
                .build());

        assertTrue(observation.contains("\"code\":\"TIMEOUT\""));
        assertTrue(observation.contains("\"retriable\":true"));
    }
}
