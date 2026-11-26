package com.health.agent.tool;

import com.health.agent.model.ChatMessage;
import com.health.agent.model.FollowUpTask;
import com.health.agent.model.HealthKnowledge;
import com.health.agent.model.PatientProfile;
import com.health.agent.model.PrescriptionRecord;
import com.health.agent.model.ToolExecutionOutput;
import com.health.agent.model.WeightRecord;
import com.health.agent.service.tool.FollowUpTaskService;
import com.health.agent.service.tool.HealthKnowledgeService;
import com.health.agent.service.tool.PatientService;
import com.health.agent.service.tool.AdherenceAnalysisService;
import com.health.agent.service.tool.WeightPlateauService;
import com.health.agent.scene.doctor.tool.DoctorToolDefinitionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolRegistryTest {

    @Mock
    private PatientService patientService;

    @Mock
    private FollowUpTaskService followUpTaskService;

    @Mock
    private HealthKnowledgeService healthKnowledgeService;

    private WeightPlateauService weightPlateauService;
    private AdherenceAnalysisService adherenceAnalysisService;

    private ToolRegistry toolRegistry;

    @BeforeEach
    void setUp() {
        weightPlateauService = new WeightPlateauService();
        ReflectionTestUtils.setField(weightPlateauService, "windowDays", 14);
        ReflectionTestUtils.setField(weightPlateauService, "maxWeightChangeKg", 0.5d);
        ReflectionTestUtils.setField(weightPlateauService, "recentTrendDays", 7);
        ReflectionTestUtils.setField(weightPlateauService, "recentDownwardThresholdKg", 0.2d);

        adherenceAnalysisService = new AdherenceAnalysisService();
        ReflectionTestUtils.setField(adherenceAnalysisService, "analysisWindowDays", 14);
        ReflectionTestUtils.setField(adherenceAnalysisService, "minWeightRecords", 3);

        toolRegistry = new ToolRegistry(new DoctorToolDefinitionRegistry());
        ReflectionTestUtils.setField(toolRegistry, "patientService", patientService);
        ReflectionTestUtils.setField(toolRegistry, "followUpTaskService", followUpTaskService);
        ReflectionTestUtils.setField(toolRegistry, "healthKnowledgeService", healthKnowledgeService);
        ReflectionTestUtils.setField(toolRegistry, "weightPlateauService", weightPlateauService);
        ReflectionTestUtils.setField(toolRegistry, "adherenceAnalysisService", adherenceAnalysisService);
        toolRegistry.registerTools();
    }

    @Test
    void shouldExposeWeightManagementFocusedTools() {
        Set<String> toolNames = toolRegistry.getAllTools().stream()
            .map(Tool::getName)
            .collect(Collectors.toSet());

        assertTrue(toolNames.contains("get_patient_profile"));
        assertTrue(toolNames.contains("get_weight_history"));
        assertTrue(toolNames.contains("get_chat_history"));
        assertTrue(toolNames.contains("get_prescription_history"));
        assertTrue(toolNames.contains("calculate_bmi"));
        assertTrue(toolNames.contains("analyze_weight_trend"));
        assertTrue(toolNames.contains("detect_weight_plateau"));
        assertTrue(toolNames.contains("analyze_adherence_signals"));
        assertTrue(toolNames.contains("get_health_knowledge"));
        assertTrue(toolNames.contains("search_medical_knowledge"));
        assertTrue(toolNames.contains("search_medication_guidance"));
        assertTrue(toolNames.contains("search_follow_up_protocol"));
        assertTrue(toolNames.contains("search_red_flag_guideline"));
        assertTrue(toolNames.contains("triage_red_flags"));
        assertTrue(toolNames.contains("analyze_medication_adherence"));
        assertTrue(toolNames.contains("summarize_symptom_timeline"));
        assertTrue(toolNames.contains("assess_follow_up_urgency"));
        assertTrue(toolNames.contains("generate_patient_education_points"));
        assertTrue(toolNames.contains("check_self_monitoring_completeness"));
        assertTrue(toolNames.contains("extract_structured_symptoms"));
        assertTrue(toolNames.contains("screen_mental_health_risk"));
        assertTrue(toolNames.contains("recommend_follow_up_data_requests"));
        assertTrue(toolNames.contains("send_reminder"));
        assertTrue(toolNames.contains("create_follow_up_task"));
        assertTrue(toolNames.contains("send_health_report"));
        assertTrue(toolNames.contains("draft_weight_management_plan"));
        assertTrue(toolNames.contains("recommend_medical_review"));
    }

    @Test
    void shouldRemoveGenericMedicalAssistantTools() {
        Set<String> toolNames = toolRegistry.getAllTools().stream()
            .map(Tool::getName)
            .collect(Collectors.toSet());

        assertFalse(toolNames.contains("search_similar_cases"));
        assertFalse(toolNames.contains("draft_prescription"));
        assertFalse(toolNames.contains("suggest_diagnosis"));
    }

    @Test
    void shouldReturnStructuredToolExecutionOutputForCoreLowRiskTools() {
        PatientProfile profile = PatientProfile.builder()
            .customerId("C001")
            .name("张三")
            .age(35)
            .gender("女")
            .height(165.0)
            .currentWeight(62.0)
            .targetWeight(55.0)
            .bmi(22.8)
            .chronicDiseases("[\"高血压\"]")
            .build();
        when(patientService.getProfile("C001")).thenReturn(profile);

        WeightRecord w1 = WeightRecord.builder().recordDate(LocalDate.now().minusDays(14)).weight(80.0).bmi(29.4).build();
        WeightRecord w2 = WeightRecord.builder().recordDate(LocalDate.now().minusDays(7)).weight(80.1).bmi(29.4).build();
        WeightRecord w3 = WeightRecord.builder().recordDate(LocalDate.now()).weight(80.0).bmi(29.4).build();
        when(patientService.getWeightHistory("C001", 30)).thenReturn(List.of(w1, w2, w3));
        when(patientService.getWeightHistory("C001", 14)).thenReturn(List.of(w1, w2, w3));

        ChatMessage chat = ChatMessage.builder()
            .sender("USER")
            .message("我最近有在快走，也在控制饮食，但这几天有点焦虑和失眠，偶尔忘记吃药")
            .createdAt(LocalDateTime.now())
            .build();
        when(patientService.getChatHistory("C001", 20)).thenReturn(List.of(chat));
        when(patientService.getChatHistory("C001", 10)).thenReturn(List.of(chat));
        when(followUpTaskService.getTasksByCustomerId("C001", 10)).thenReturn(List.of());
        PrescriptionRecord prescription = PrescriptionRecord.builder()
            .customerId("C001")
            .medication("二甲双胍")
            .dosage("0.5g bid")
            .createdAt(LocalDateTime.now())
            .build();
        when(patientService.getPrescriptionHistory("C001", 10)).thenReturn(List.of(prescription));
        HealthKnowledge kbKnowledge = HealthKnowledge.builder()
            .title("高血压运动建议")
            .category("EXERCISE")
            .content("建议每周进行中等强度有氧运动。")
            .source("kb")
            .trustLevel("HIGH")
            .hitReason("hybrid")
            .score(0.91)
            .applicableScope("doctor_assistant")
            .build();
        when(healthKnowledgeService.getByTopic("高血压运动", 3)).thenReturn(List.of(kbKnowledge));
        when(healthKnowledgeService.getByTopic("高血压", 3)).thenReturn(List.of(kbKnowledge));
        when(healthKnowledgeService.searchMedicalKnowledge("高血压运动建议", 5)).thenReturn(List.of(kbKnowledge));
        when(healthKnowledgeService.searchMedicationGuidance("二甲双胍 禁忌", 5)).thenReturn(List.of(kbKnowledge));
        when(healthKnowledgeService.searchFollowUpProtocol("高血压 随访", 5)).thenReturn(List.of(kbKnowledge));
        when(healthKnowledgeService.searchRedFlagGuideline("胸痛 红旗", 5)).thenReturn(List.of(kbKnowledge));

        ToolExecutionOutput profileOutput = assertInstanceOf(
            ToolExecutionOutput.class,
            toolRegistry.getTool("get_patient_profile").getExecutor().execute(Map.of("customerId", "C001"))
        );
        assertTrue(profileOutput.getStateUpdates().containsKey("patient_profile"));

        ToolExecutionOutput weightOutput = assertInstanceOf(
            ToolExecutionOutput.class,
            toolRegistry.getTool("get_weight_history").getExecutor().execute(Map.of("customerId", "C001", "days", 30))
        );
        assertTrue(weightOutput.getStateUpdates().containsKey("weight_history"));

        ToolExecutionOutput plateauOutput = assertInstanceOf(
            ToolExecutionOutput.class,
            toolRegistry.getTool("detect_weight_plateau").getExecutor().execute(Map.of("weightRecords", List.of(
                Map.of("date", LocalDate.now().minusDays(13).toString(), "weight", 80.0),
                Map.of("date", LocalDate.now().minusDays(6).toString(), "weight", 80.1),
                Map.of("date", LocalDate.now().toString(), "weight", 80.0)
            )))
        );
        assertTrue(plateauOutput.getStateUpdates().containsKey("plateau_assessment"));
        Map<?, ?> plateauAssessment = assertInstanceOf(Map.class, plateauOutput.getData());
        assertEquals("PLATEAU", plateauAssessment.get("reasonCode"));
        assertEquals(14, plateauAssessment.get("windowDays"));

        ToolExecutionOutput adherenceOutput = assertInstanceOf(
            ToolExecutionOutput.class,
            toolRegistry.getTool("analyze_adherence_signals").getExecutor().execute(Map.of("customerId", "C001"))
        );
        assertTrue(adherenceOutput.getStateUpdates().containsKey("adherence_analysis"));

        ToolExecutionOutput triageOutput = assertInstanceOf(
            ToolExecutionOutput.class,
            toolRegistry.getTool("triage_red_flags").getExecutor().execute(Map.of("customerId", "C001"))
        );
        assertTrue(triageOutput.getStateUpdates().containsKey("red_flag_triage"));

        ToolExecutionOutput medicationOutput = assertInstanceOf(
            ToolExecutionOutput.class,
            toolRegistry.getTool("analyze_medication_adherence").getExecutor().execute(Map.of("customerId", "C001"))
        );
        assertTrue(medicationOutput.getStateUpdates().containsKey("medication_adherence_analysis"));

        ToolExecutionOutput mentalHealthOutput = assertInstanceOf(
            ToolExecutionOutput.class,
            toolRegistry.getTool("screen_mental_health_risk").getExecutor().execute(Map.of("customerId", "C001"))
        );
        assertTrue(mentalHealthOutput.getStateUpdates().containsKey("mental_health_risk_screen"));

        ToolExecutionOutput followUpDataOutput = assertInstanceOf(
            ToolExecutionOutput.class,
            toolRegistry.getTool("recommend_follow_up_data_requests").getExecutor().execute(Map.of("customerId", "C001"))
        );
        assertTrue(followUpDataOutput.getStateUpdates().containsKey("follow_up_data_requests"));

        ToolExecutionOutput knowledgeOutput = assertInstanceOf(
            ToolExecutionOutput.class,
            toolRegistry.getTool("get_health_knowledge").getExecutor().execute(Map.of("topic", "高血压运动"))
        );
        List<?> knowledgeRows = assertInstanceOf(List.class, knowledgeOutput.getData());
        Map<?, ?> firstKnowledge = assertInstanceOf(Map.class, knowledgeRows.get(0));
        assertEquals("HIGH", firstKnowledge.get("trustLevel"));
        assertEquals("hybrid", firstKnowledge.get("hitReason"));
        assertNotNull(firstKnowledge.get("score"));

        ToolExecutionOutput educationOutput = assertInstanceOf(
            ToolExecutionOutput.class,
            toolRegistry.getTool("generate_patient_education_points").getExecutor().execute(Map.of("topic", "高血压"))
        );
        Map<?, ?> educationData = assertInstanceOf(Map.class, educationOutput.getData());
        assertTrue(educationData.containsKey("evidence"));

        ToolExecutionOutput medicalKnowledgeOutput = assertInstanceOf(
            ToolExecutionOutput.class,
            toolRegistry.getTool("search_medical_knowledge").getExecutor().execute(Map.of("query", "高血压运动建议"))
        );
        assertTrue(medicalKnowledgeOutput.getStateUpdates().containsKey("medical_knowledge"));

        ToolExecutionOutput medicationGuidanceOutput = assertInstanceOf(
            ToolExecutionOutput.class,
            toolRegistry.getTool("search_medication_guidance").getExecutor().execute(Map.of("query", "二甲双胍 禁忌"))
        );
        assertTrue(medicationGuidanceOutput.getStateUpdates().containsKey("medication_guidance"));

        ToolExecutionOutput followUpProtocolOutput = assertInstanceOf(
            ToolExecutionOutput.class,
            toolRegistry.getTool("search_follow_up_protocol").getExecutor().execute(Map.of("query", "高血压 随访"))
        );
        assertTrue(followUpProtocolOutput.getStateUpdates().containsKey("follow_up_protocol"));

        ToolExecutionOutput redFlagGuidelineOutput = assertInstanceOf(
            ToolExecutionOutput.class,
            toolRegistry.getTool("search_red_flag_guideline").getExecutor().execute(Map.of("query", "胸痛 红旗"))
        );
        assertTrue(redFlagGuidelineOutput.getStateUpdates().containsKey("red_flag_guideline"));
    }
}
