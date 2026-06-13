package com.health.agent.engine.subagent;

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
}
