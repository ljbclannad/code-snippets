package com.lx.governance.agentplatform.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

class RunModelTest {

    @Test
    void shouldBuildRunWithStepsAndEvents() {
        RunStep step = RunStep.builder()
                .stepIndex(1)
                .stepId("step-1")
                .nodeType("agent_call")
                .nodeName("doctor-analysis")
                .agentId("doctor.WeightAnalysisAgent")
                .status(RunStatus.RUNNING)
                .input(Map.of("goal", "判断是否进入平台期"))
                .startedAt(1000L)
                .build();

        RunEvent event = RunEvent.builder()
                .eventId("evt-1")
                .runId("run-1")
                .stepId("step-1")
                .eventType(RunEventType.STEP_STARTED)
                .message("weight analysis started")
                .createdAt(1001L)
                .build();

        Run run = Run.builder()
                .runId("run-1")
                .conversationId("conv-1")
                .runType(RunType.TASK)
                .status(RunStatus.RUNNING)
                .input(Map.of("customerId", "C001"))
                .step(step)
                .event(event)
                .createdAt(999L)
                .startedAt(1000L)
                .build();

        assertEquals(RunType.TASK, run.getRunType());
        assertEquals(RunStatus.RUNNING, run.getStatus());
        assertEquals(1, run.getSteps().size());
        assertEquals(1, run.getEvents().size());
        assertEquals("doctor.WeightAnalysisAgent", run.getSteps().get(0).getAgentId());
        assertEquals(RunEventType.STEP_STARTED, run.getEvents().get(0).getEventType());
    }
}
