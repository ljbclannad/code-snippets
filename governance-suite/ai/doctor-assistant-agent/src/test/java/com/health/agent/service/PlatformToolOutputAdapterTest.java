package com.health.agent.service;

import com.health.agent.model.ToolEvidence;
import com.health.agent.model.ToolExecutionOutput;
import com.lx.governance.agentplatform.model.PlatformToolOutput;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PlatformToolOutputAdapterTest {

    private final PlatformToolOutputAdapter adapter = new PlatformToolOutputAdapter();

    @Test
    void shouldConvertLegacyToolExecutionOutputToPlatformProtocol() {
        PlatformToolOutput output = adapter.toPlatformOutput(
                "get_patient_profile",
                "查询患者档案",
                ToolExecutionOutput.builder()
                        .data(Map.of("customerId", "C001", "name", "张三"))
                        .summary("已获取患者基础档案")
                        .stateUpdates(Map.of("patient_profile", Map.of("customerId", "C001")))
                        .evidence(java.util.List.of(
                                ToolEvidence.builder()
                                        .source("PATIENT_PROFILE")
                                        .summary("已获取患者基础档案")
                                        .payload(Map.of("customerId", "C001"))
                                        .build()))
                        .build(),
                "patient_profile");

        assertEquals("get_patient_profile", output.getToolName());
        assertEquals("已获取患者基础档案", output.getSummary());
        assertEquals("C001", ((Map<?, ?>) output.getStateUpdates().get("patient_profile")).get("customerId"));
        assertFalse(output.getEvidences().isEmpty());
        assertEquals("PATIENT_PROFILE", output.getEvidences().get(0).getSource());
    }

    @Test
    void shouldBuildFallbackPlatformOutputForRawResult() {
        PlatformToolOutput output = adapter.toPlatformOutput(
                "calculate_bmi",
                "计算BMI指数",
                Map.of("bmi", 24.1),
                "bmi_assessment");

        assertEquals("calculate_bmi", output.getToolName());
        assertNotNull(output.getStateUpdates().get("bmi_assessment"));
        assertFalse(output.getEvidences().isEmpty());
        assertEquals("TOOL_OUTPUT", output.getEvidences().get(0).getSource());
    }
}
