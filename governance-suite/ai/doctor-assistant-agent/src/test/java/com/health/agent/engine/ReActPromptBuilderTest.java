package com.health.agent.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import com.health.agent.dto.agent.AgentExecutionCommand;
import com.health.agent.dto.patient.MemorySnapshot;
import com.health.agent.dto.patient.TaskType;
import com.health.agent.scene.doctor.prompt.DoctorScenePromptTemplate;
import com.lx.governance.agentplatform.model.PromptRenderRequest;
import com.lx.governance.agentplatform.model.PromptTemplateDescriptor;
import com.lx.governance.agentplatform.model.PromptTemplateType;
import com.lx.governance.agentplatform.runtime.InMemoryPromptRegistry;
import com.lx.governance.agentplatform.runtime.PromptTemplateRenderer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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

    @Test
    void shouldExposeDescriptorVersionInRenderedPrompt() {
        InMemoryPromptRegistry registry = new InMemoryPromptRegistry(List.of(new PromptTemplateRenderer() {
            @Override
            public PromptTemplateDescriptor descriptor() {
                return PromptTemplateDescriptor.builder()
                    .scene(ReActPromptBuilder.DOCTOR_SCENE)
                    .agentId(ReActPromptBuilder.DOCTOR_AGENT)
                    .version("doctor-react-prompt-v3")
                    .type(PromptTemplateType.SUB_AGENT_SYSTEM)
                    .description("test sub-agent prompt")
                    .build();
            }

            @Override
            public String render(PromptRenderRequest request) {
                return "registry-sub-agent-prompt-v3";
            }
        }));
        org.springframework.test.util.ReflectionTestUtils.setField(promptBuilder, "promptRegistry", registry);

        RenderedPrompt renderedPrompt = promptBuilder.renderSubAgentSystemPromptFromCatalog(null, List.of());

        assertEquals("registry-sub-agent-prompt-v3", renderedPrompt.getContent());
        assertEquals("doctor-react-prompt-v3", renderedPrompt.getDescriptor().getVersion());
        assertEquals(PromptTemplateType.SUB_AGENT_SYSTEM, renderedPrompt.getDescriptor().getType());
    }

    @Test
    void shouldKeepOnlyTaskAndAnalysisFocusInPatientUserPrompt() {
        AgentExecutionCommand command = AgentExecutionCommand.builder()
                .goal("分析患者本轮随访风险")
                .customerId("customer-1")
                .taskType(TaskType.PATIENT_FOLLOW_UP_ANALYSIS)
                .patientMessage("最近晚饭后很容易饿，还担心血糖波动")
                .triggerDecision(Map.of(
                        "summary", "命中随访分析升级",
                        "reasons", List.of("TREND_ANALYSIS_REQUEST", "FOLLOW_UP_SIGNAL"),
                        "route", "DOCTOR_ASSISTANT_AGENT",
                        "taskType", "PATIENT_FOLLOW_UP_ANALYSIS"))
                .profileSnapshot(Map.of(
                        "basicInfo", Map.of("age", 35, "gender", "女"),
                        "healthProfile", Map.of("bloodSugar", "6.8", "bmi", "28.1"),
                        "behaviorPreferences", Map.of("dietaryPreferences", List.of("低糖", "少油"))))
                .sessionContext(Map.of(
                        "currentTopic", "血糖管理",
                        "userIntent", "饮食建议",
                        "conversationPhase", "consulting",
                        "pendingQuestions", List.of("最近一周晚饭后血糖是多少"),
                        "mentionedEntities", List.of("血糖", "晚饭")))
                .memorySnapshot(MemorySnapshot.builder()
                        .shortTermMemories(List.of("当前话题: 血糖管理", "用户意图: 饮食建议"))
                        .longTermMemories(List.of("长期记忆: 用户晚饭后容易加餐", "长期记忆: 用户偏好低糖饮食"))
                        .build())
                .build();

        String prompt = promptBuilder.buildUserPrompt(command);

        assertTrue(prompt.contains("Goal: 分析患者本轮随访风险"));
        assertTrue(prompt.contains("Task Type: PATIENT_FOLLOW_UP_ANALYSIS"));
        assertTrue(prompt.contains("Task-specific Analysis Focus:"));
        assertFalse(prompt.contains("患者晚饭后容易加餐"));
        assertFalse(prompt.contains("血糖管理"));
        assertFalse(prompt.contains("命中随访分析升级"));
        assertFalse(prompt.contains("担心血糖波动"));
        assertFalse(prompt.contains("bmi"));
    }
}
