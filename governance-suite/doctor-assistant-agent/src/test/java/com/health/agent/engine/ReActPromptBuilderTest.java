package com.health.agent.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ReActPromptBuilderTest {

    private final ReActPromptBuilder promptBuilder = new ReActPromptBuilder();

    @Test
    void shouldDescribeStructuredFinalAnswerProtocol() {
        String prompt = promptBuilder.buildSystemPrompt(List.of());

        assertTrue(prompt.contains("\"summary\""));
        assertTrue(prompt.contains("\"riskLevel\""));
        assertTrue(prompt.contains("\"recommendedActions\""));
        assertTrue(prompt.contains("\"uncertainties\""));
        assertTrue(prompt.contains("\"supportingEvidence\""));
        assertTrue(prompt.contains("\"evidenceSources\""));
        assertTrue(prompt.contains("\"inferences\""));
    }

    @Test
    void shouldMentionWeightPlateauDetectionTool() {
        String prompt = promptBuilder.buildSystemPrompt(List.of(
            com.health.agent.tool.Tool.builder()
                .name("detect_weight_plateau")
                .description("识别是否进入减重停滞期")
                .riskLevel(com.health.agent.tool.ToolRiskLevel.LOW)
                .inputSchema(java.util.Map.of("weightRecords", java.util.Map.of("type", "array")))
                .build()
        ));

        assertTrue(prompt.contains("detect_weight_plateau"));
        assertTrue(prompt.contains("识别是否进入减重停滞期"));
    }

    @Test
    void shouldMentionAdherenceAnalysisTool() {
        String prompt = promptBuilder.buildSystemPrompt(List.of(
            com.health.agent.tool.Tool.builder()
                .name("analyze_adherence_signals")
                .description("分析饮食和运动依从性信号")
                .riskLevel(com.health.agent.tool.ToolRiskLevel.LOW)
                .inputSchema(java.util.Map.of("customerId", java.util.Map.of("type", "string")))
                .build()
        ));

        assertTrue(prompt.contains("analyze_adherence_signals"));
        assertTrue(prompt.contains("分析饮食和运动依从性信号"));
    }
}
