package com.health.agent.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredOutputTest {

    @Test
    void shouldRenderStructuredOutputToReadableSummary() {
        StructuredFinalAnswer answer = StructuredFinalAnswer.builder()
            .summary("患者近30天减重2.5kg。")
            .keyFindings(List.of("体重整体下降"))
            .riskLevel("LOW")
            .recommendedActions(List.of("继续当前饮食计划"))
            .needsAttention(false)
            .uncertainties(List.of("缺少完整运动记录"))
            .build();

        assertTrue(answer.toTextSummary().contains("风险等级: LOW"));
    }
}
