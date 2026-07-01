package com.lx.governance.agentplatform.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.lx.governance.agentplatform.model.LoopContext;
import com.lx.governance.agentplatform.model.LoopOutcome;
import com.lx.governance.agentplatform.model.LoopTerminationReason;
import com.lx.governance.agentplatform.model.RunStatus;

class AbstractPlatformLoopExecutorTest {

    @Test
    void shouldContinueUntilTerminateDecision() {
        List<Integer> executedSteps = new ArrayList<>();
        AbstractPlatformLoopExecutor<List<Integer>, String> executor = new AbstractPlatformLoopExecutor<>() {
            @Override
            protected LoopStepDecision<List<Integer>, String> executeStep(
                    LoopContext<List<Integer>> loopContext,
                    int step,
                    int stepNumber) {
                loopContext.getState().add(stepNumber);
                executedSteps.add(stepNumber);
                if (stepNumber == 2) {
                    return LoopStepDecision.terminate(LoopOutcome.<List<Integer>, String>builder()
                            .runId(loopContext.getRunId())
                            .terminationReason(LoopTerminationReason.FINISHED)
                            .runStatus(RunStatus.COMPLETED)
                            .completedSteps(stepNumber)
                            .result("done")
                            .state(loopContext.getState())
                            .endedAt(2000L)
                            .build());
                }
                return LoopStepDecision.continueLoop();
            }

            @Override
            protected LoopOutcome<List<Integer>, String> onStepException(
                    LoopContext<List<Integer>> loopContext,
                    int step,
                    int stepNumber,
                    Exception e) {
                throw new IllegalStateException(e);
            }

            @Override
            protected LoopOutcome<List<Integer>, String> onMaxSteps(LoopContext<List<Integer>> loopContext) {
                return LoopOutcome.<List<Integer>, String>builder()
                        .runId(loopContext.getRunId())
                        .terminationReason(LoopTerminationReason.MAX_STEPS)
                        .runStatus(RunStatus.DEGRADED)
                        .completedSteps(loopContext.getMaxSteps())
                        .state(loopContext.getState())
                        .endedAt(3000L)
                        .build();
            }
        };

        LoopOutcome<List<Integer>, String> outcome = executor.executeLoop(LoopContext.<List<Integer>>builder()
                .runId("run-1")
                .workflowId("wf-1")
                .goal("goal")
                .startStep(0)
                .maxSteps(5)
                .startedAt(1000L)
                .state(new ArrayList<>())
                .build());

        assertEquals(List.of(1, 2), executedSteps);
        assertEquals(LoopTerminationReason.FINISHED, outcome.getTerminationReason());
        assertEquals("done", outcome.getResult());
    }

    @Test
    void shouldDelegateExceptionAndMaxStepsHandling() {
        AbstractPlatformLoopExecutor<String, String> exceptionExecutor = new AbstractPlatformLoopExecutor<>() {
            @Override
            protected LoopStepDecision<String, String> executeStep(
                    LoopContext<String> loopContext,
                    int step,
                    int stepNumber) {
                throw new IllegalArgumentException("boom");
            }

            @Override
            protected LoopOutcome<String, String> onStepException(
                    LoopContext<String> loopContext,
                    int step,
                    int stepNumber,
                    Exception e) {
                return LoopOutcome.<String, String>builder()
                        .runId(loopContext.getRunId())
                        .terminationReason(LoopTerminationReason.FAILED)
                        .runStatus(RunStatus.FAILED)
                        .completedSteps(stepNumber)
                        .state(loopContext.getState())
                        .errorMessage(e.getMessage())
                        .endedAt(2000L)
                        .build();
            }

            @Override
            protected LoopOutcome<String, String> onMaxSteps(LoopContext<String> loopContext) {
                throw new UnsupportedOperationException();
            }
        };

        LoopOutcome<String, String> failureOutcome = exceptionExecutor.executeLoop(LoopContext.<String>builder()
                .runId("run-2")
                .workflowId("wf-2")
                .goal("goal")
                .startStep(0)
                .maxSteps(2)
                .startedAt(1000L)
                .state("state")
                .build());

        assertEquals(LoopTerminationReason.FAILED, failureOutcome.getTerminationReason());
        assertEquals("boom", failureOutcome.getErrorMessage());

        AbstractPlatformLoopExecutor<String, String> maxStepsExecutor = new AbstractPlatformLoopExecutor<>() {
            @Override
            protected LoopStepDecision<String, String> executeStep(
                    LoopContext<String> loopContext,
                    int step,
                    int stepNumber) {
                return LoopStepDecision.continueLoop();
            }

            @Override
            protected LoopOutcome<String, String> onStepException(
                    LoopContext<String> loopContext,
                    int step,
                    int stepNumber,
                    Exception e) {
                throw new IllegalStateException(e);
            }

            @Override
            protected LoopOutcome<String, String> onMaxSteps(LoopContext<String> loopContext) {
                return LoopOutcome.<String, String>builder()
                        .runId(loopContext.getRunId())
                        .terminationReason(LoopTerminationReason.MAX_STEPS)
                        .runStatus(RunStatus.DEGRADED)
                        .completedSteps(loopContext.getMaxSteps())
                        .state(loopContext.getState())
                        .endedAt(3000L)
                        .build();
            }
        };

        LoopOutcome<String, String> maxStepsOutcome = maxStepsExecutor.executeLoop(LoopContext.<String>builder()
                .runId("run-3")
                .workflowId("wf-3")
                .goal("goal")
                .startStep(0)
                .maxSteps(2)
                .startedAt(1000L)
                .state("state")
                .build());

        assertEquals(LoopTerminationReason.MAX_STEPS, maxStepsOutcome.getTerminationReason());
        assertEquals(RunStatus.DEGRADED, maxStepsOutcome.getRunStatus());
    }
}
