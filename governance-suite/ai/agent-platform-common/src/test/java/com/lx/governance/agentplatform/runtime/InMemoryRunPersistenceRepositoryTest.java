package com.lx.governance.agentplatform.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
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
import com.lx.governance.agentplatform.model.RunType;

class InMemoryRunPersistenceRepositoryTest {

    @Test
    void shouldPersistSnapshotRunAndConversationLink() {
        InMemoryRunPersistenceRepository repository = new InMemoryRunPersistenceRepository();
        Run run = Run.builder()
                .runId("run-1")
                .conversationId("conv-1")
                .sessionId("session-1")
                .workflowId("wf-1")
                .agentId("doctor-agent")
                .runType(RunType.TASK)
                .status(RunStatus.RUNNING)
                .input(Map.of("goal", "复盘"))
                .createdAt(1000L)
                .startedAt(1001L)
                .build();
        RunEvent event = RunEvent.builder()
                .eventId("evt-1")
                .runId("run-1")
                .eventType(RunEventType.RUN_STARTED)
                .createdAt(1001L)
                .build();
        ApprovalTask approvalTask = ApprovalTask.builder()
                .approvalTaskId("ap-1")
                .runId("run-1")
                .taskKey("create_follow_up_task")
                .status(ApprovalStatus.PENDING)
                .createdAt(1002L)
                .build();

        repository.save(RunPersistenceSnapshot.builder()
                .run(run)
                .approvalTask(approvalTask)
                .build());
        repository.save(event);
        repository.save(ConversationRunLink.builder()
                .conversationId("conv-1")
                .sessionId("session-1")
                .runId("run-1")
                .entryMode(PlatformEntryMode.AGENT_RUN)
                .outcomeMode(PlatformOutcomeMode.PENDING_APPROVAL)
                .linkedAt(1002L)
                .build());

        assertTrue(repository.findSnapshotByRunId("run-1").isPresent());
        assertEquals(1, repository.findSnapshotByRunId("run-1").get().getApprovalTasks().size());
        assertEquals(1, repository.findRunById("run-1").orElseThrow().getInput().size());
        assertEquals(List.of(event), repository.findEventsByRunId("run-1"));
        assertEquals("run-1", repository.findLatestByConversationId("conv-1").orElseThrow().getRunId());
        assertEquals(ApprovalStatus.PENDING, repository.findById("ap-1").getStatus());
    }
}
