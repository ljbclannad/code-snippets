package com.lx.governance.agentplatform.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.lx.governance.agentplatform.model.ApprovalStatus;
import com.lx.governance.agentplatform.model.ApprovalTask;
import com.lx.governance.agentplatform.model.ConversationRunLink;
import com.lx.governance.agentplatform.model.PlatformEntryMode;
import com.lx.governance.agentplatform.model.PlatformOutcomeMode;
import com.lx.governance.agentplatform.model.Run;
import com.lx.governance.agentplatform.model.RunEvent;
import com.lx.governance.agentplatform.model.RunEventType;
import com.lx.governance.agentplatform.model.RunPersistenceSnapshot;
import com.lx.governance.agentplatform.model.RunStatus;
import com.lx.governance.agentplatform.model.RunStep;
import com.lx.governance.agentplatform.model.RunType;

class RunReplayServiceTest {

    @Test
    void shouldReplayRunWithEventsRouteAndToolCalls() {
        InMemoryRunPersistenceRepository repository = new InMemoryRunPersistenceRepository();
        Run run = Run.builder()
                .runId("run-1")
                .conversationId("conv-1")
                .sessionId("session-1")
                .agentId("doctor-assistant-agent")
                .runType(RunType.TASK)
                .status(RunStatus.WAITING_APPROVAL)
                .output(Map.of("finalAnswer", "需要医生确认后继续执行"))
                .step(RunStep.builder()
                        .stepIndex(2)
                        .stepId("run-1:step:2")
                        .nodeType("react_loop")
                        .nodeName("doctor-loop")
                        .agentId("doctor-assistant-agent")
                        .status(RunStatus.WAITING_APPROVAL)
                        .build())
                .event(RunEvent.builder()
                        .eventId("e2")
                        .runId("run-1")
                        .eventType(RunEventType.WAITING_APPROVAL)
                        .message("waiting approval")
                        .createdAt(200L)
                        .build())
                .event(RunEvent.builder()
                        .eventId("e1")
                        .runId("run-1")
                        .eventType(RunEventType.RUN_CREATED)
                        .message("created")
                        .createdAt(100L)
                        .build())
                .build();
        repository.save(RunPersistenceSnapshot.builder()
                .run(run)
                .approvalTask(ApprovalTask.builder()
                        .approvalTaskId("task-1")
                        .runId("run-1")
                        .taskKey("create_follow_up_task")
                        .taskName("创建随访任务")
                        .reason("需要医生审批")
                        .status(ApprovalStatus.PENDING)
                        .createdAt(180L)
                        .expireAt(300L)
                        .build())
                .legacyRunTable("agent_session")
                .legacyRunId("S001")
                .legacyApprovalTable("agent_pending_task")
                .legacyApprovalId("101")
                .build());
        repository.save(ConversationRunLink.builder()
                .conversationId("conv-1")
                .sessionId("session-1")
                .runId("run-1")
                .runType(RunType.TASK)
                .entryMode(PlatformEntryMode.AGENT_RUN)
                .outcomeMode(PlatformOutcomeMode.PENDING_APPROVAL)
                .triggerSource("PATIENT_TASK_AGENT")
                .fallbackReason(null)
                .linkedAt(220L)
                .build());

        RunReplayService replayService = new RunReplayService(
                repository,
                repository,
                repository,
                repository,
                repository);

        var replay = replayService.replayByRunId("run-1").orElseThrow();

        assertEquals("run-1", replay.getRunId());
        assertNotNull(replay.getConversationLink());
        assertEquals("PATIENT_TASK_AGENT", replay.getRouteDecision().getTriggerSource());
        assertEquals(2, replay.getEvents().size());
        assertEquals(RunEventType.RUN_CREATED, replay.getEvents().get(0).getEventType());
        assertEquals(1, replay.getToolCalls().size());
        assertEquals("create_follow_up_task", replay.getToolCalls().get(0).getToolName());
        assertTrue(replay.getToolCalls().get(0).getResultSummary().contains("需要医生确认"));
        assertEquals("agent_session", replay.getLegacyRunTable());
    }
}
