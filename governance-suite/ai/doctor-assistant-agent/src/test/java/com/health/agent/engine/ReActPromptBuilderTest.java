package com.health.agent.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.health.agent.scene.doctor.prompt.DoctorScenePromptTemplate;
import com.lx.governance.agentplatform.model.PromptRenderRequest;
import com.lx.governance.agentplatform.model.PromptTemplateDescriptor;
import com.lx.governance.agentplatform.model.PromptTemplateType;
import com.lx.governance.agentplatform.runtime.InMemoryPromptRegistry;
import com.lx.governance.agentplatform.runtime.PromptTemplateRenderer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ReActPromptBuilderTest {

    private final ReActPromptBuilder promptBuilder = buildPromptBuilder();

    private ReActPromptBuilder buildPromptBuilder() {
        ReActPromptBuilder builder = new ReActPromptBuilder();
        org.springframework.test.util.ReflectionTestUtils.setField(
            builder,
            "doctorScenePromptTemplate",
            new DoctorScenePromptTemplate());
        return builder;
    }

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

    @Test
    void shouldUsePromptRegistryForCatalogPrompt() {
        InMemoryPromptRegistry registry = new InMemoryPromptRegistry(List.of(new PromptTemplateRenderer() {
            @Override
            public PromptTemplateDescriptor descriptor() {
                return PromptTemplateDescriptor.builder()
                    .scene(ReActPromptBuilder.DOCTOR_SCENE)
                    .agentId(ReActPromptBuilder.DOCTOR_AGENT)
                    .version("doctor-react-prompt-v2")
                    .type(PromptTemplateType.SYSTEM)
                    .description("test system prompt")
                    .build();
            }

            @Override
            public String render(PromptRenderRequest request) {
                return "registry-system-prompt-v2";
            }
        }));
        org.springframework.test.util.ReflectionTestUtils.setField(promptBuilder, "promptRegistry", registry);

        String prompt = promptBuilder.buildSystemPromptFromCatalog(List.of());

        assertEquals("registry-system-prompt-v2", prompt);
        assertEquals("doctor-react-prompt-v2", promptBuilder.currentPromptVersion(PromptTemplateType.SYSTEM));
    }
}
