package com.health.agent.engine.subagent;

import com.health.agent.dto.patient.TaskType;
import com.health.agent.model.AgentContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SubAgentCoordinatorTest {

    private SubAgentCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = new SubAgentCoordinator();
        ReflectionTestUtils.setField(coordinator, "dataCollectionSubAgent", new DataCollectionSubAgent());
        ReflectionTestUtils.setField(coordinator, "weightAnalysisSubAgent", new WeightAnalysisSubAgent());
        ReflectionTestUtils.setField(coordinator, "carePlanningSubAgent", new CarePlanningSubAgent());
    }

    @Test
    void shouldSelectDataCollectionAgentWhenContextIsEmpty() {
        AgentContext context = AgentContext.initialize("C001", "D001", "分析患者减重进展");

        assertEquals("DataCollectionAgent", coordinator.select("分析患者减重进展", context).name());
    }

    @Test
    void shouldSelectWeightAnalysisAgentWhenWeightDataExistsButAnalysisMissing() {
        AgentContext context = AgentContext.initialize("C001", "D001", "判断是否进入平台期");
        context.putSlot("weight_history", Map.of("count", 3), "test");

        assertEquals("WeightAnalysisAgent", coordinator.select("判断是否进入平台期", context).name());
    }

    @Test
    void shouldSelectCarePlanningAgentWhenAnalysisAlreadyExists() {
        AgentContext context = AgentContext.initialize("C001", "D001", "给出下一步随访建议");
        context.putSlot("plateau_assessment", Map.of("isPlateau", true), "test");

        assertEquals("CarePlanningAgent", coordinator.select("给出下一步随访建议", context).name());
    }

    @Test
    void shouldSelectWeightAnalysisAgentWhenPreloadedSnapshotsAlreadyContainSignals() {
        AgentContext context = AgentContext.initialize("C001", "D001", "判断是否进入平台期");
        context.putSlot("patient_task_request", Map.of("taskType", TaskType.PATIENT_FOLLOW_UP_ANALYSIS.name()), "test");
        context.putSlot("profile_snapshot", Map.of("age", 32, "gender", "female"), "test");
        context.putSlot("memory_snapshot", Map.of(
                "shortTermMemories", java.util.List.of("近7天体重在68.4kg到68.7kg之间波动"),
                "longTermMemories", java.util.List.of("3个月前开始减重管理")), "test");
        context.putSlot("recent_follow_up_signals", java.util.List.of("近7天体重在68.4kg到68.7kg之间波动"), "test");

        assertEquals("WeightAnalysisAgent", coordinator.select("判断是否进入平台期", context).name());
    }

    @Test
    void shouldPreferCarePlanningForNextFollowUpTaskWhenEvidenceIsReady() {
        AgentContext context = AgentContext.initialize("C001", "D001", "给出下一步随访建议");
        context.putSlot("patient_task_request",
                Map.of("taskType", TaskType.NEXT_FOLLOW_UP_ACTION_RECOMMENDATION.name()), "test");
        context.putSlot("patient_profile", Map.of("age", 30), "test");
        context.putSlot("session_context", Map.of("phase", "follow_up"), "test");
        context.putSlot("chat_history", java.util.List.of("患者最近一周执行较稳定"), "test");
        context.putSlot("plateau_assessment", Map.of("isPlateau", false), "test");
        context.putSlot("weight_trend_analysis", Map.of("trend", "DOWN"), "test");

        assertEquals("CarePlanningAgent", coordinator.select("给出下一步随访建议", context).name());
    }
}
