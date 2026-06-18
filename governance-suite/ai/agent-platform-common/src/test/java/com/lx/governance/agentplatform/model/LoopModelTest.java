package com.lx.governance.agentplatform.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LoopModelTest {

    @Test
    void shouldBuildLoopContextAndOutcome() {
        LoopContext<String> context = LoopContext.<String>builder()
                .runId("run-1")
                .workflowId("doctor-react-loop")
                .goal("分析患者随访任务")
                .startStep(0)
                .maxSteps(8)
                .startedAt(1000L)
                .state("state-1")
                .build();

        LoopOutcome<String, String> outcome = LoopOutcome.<String, String>builder()
                .runId("run-1")
                .terminationReason(LoopTerminationReason.FINISHED)
                .runStatus(RunStatus.COMPLETED)
                .completedSteps(3)
                .result("done")
                .state("state-1")
                .endedAt(2000L)
                .build();

        assertEquals("run-1", context.getRunId());
        assertEquals(8, context.getMaxSteps());
        assertEquals(LoopTerminationReason.FINISHED, outcome.getTerminationReason());
        assertEquals(RunStatus.COMPLETED, outcome.getRunStatus());
        assertEquals("done", outcome.getResult());
    }
}
