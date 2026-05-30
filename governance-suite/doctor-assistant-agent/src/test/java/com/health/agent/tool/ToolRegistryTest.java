package com.health.agent.tool;

import com.health.agent.service.FollowUpTaskService;
import com.health.agent.service.HealthKnowledgeService;
import com.health.agent.service.PatientService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class ToolRegistryTest {

    @Mock
    private PatientService patientService;

    @Mock
    private FollowUpTaskService followUpTaskService;

    @Mock
    private HealthKnowledgeService healthKnowledgeService;

    private ToolRegistry toolRegistry;

    @BeforeEach
    void setUp() {
        toolRegistry = new ToolRegistry();
        ReflectionTestUtils.setField(toolRegistry, "patientService", patientService);
        ReflectionTestUtils.setField(toolRegistry, "followUpTaskService", followUpTaskService);
        ReflectionTestUtils.setField(toolRegistry, "healthKnowledgeService", healthKnowledgeService);
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
}
