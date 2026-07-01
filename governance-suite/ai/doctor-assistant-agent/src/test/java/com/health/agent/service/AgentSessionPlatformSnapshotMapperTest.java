package com.health.agent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.health.agent.model.AgentSession;
import com.health.agent.model.PendingTask;
import com.health.agent.model.SessionStatus;
import com.health.agent.model.TaskStatus;
import com.lx.governance.agentplatform.model.RunEventType;
import com.lx.governance.agentplatform.model.RunPersistenceSnapshot;
import com.lx.governance.agentplatform.model.RunStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

class AgentSessionPlatformSnapshotMapperTest {

    private final AgentSessionPlatformSnapshotMapper mapper = new AgentSessionPlatformSnapshotMapper();

    @Test
    void shouldProjectPausedLegacySessionToPlatformRunSnapshot() {
        AgentSession session = AgentSession.builder()
            .id("S001")
            .customerId("C001")
            .doctorId("D001")
            .goal("患者随访分析")
            .status(SessionStatus.PAUSED.name())
            .historyJson("[{\"type\":\"thought\"}]")
            .messagesJson("[{\"role\":\"user\",\"content\":\"最近体重反弹了\"}]")
            .contextJson("{\"risk\":\"medium\"}")
            .currentStep(3)
            .createdAt(LocalDateTime.of(2026, 6, 18, 10, 0))
            .updatedAt(LocalDateTime.of(2026, 6, 18, 10, 5))
            .build();
        PendingTask pendingTask = PendingTask.builder()
            .id(101L)
            .agentSessionId("S001")
            .doctorId("D001")
            .toolName("create_follow_up_task")
            .toolDescription("创建随访任务")
            .reason("需要医生审批")
            .status(TaskStatus.PENDING.name())
            .createdAt(LocalDateTime.of(2026, 6, 18, 10, 4))
            .expireAt(LocalDateTime.of(2026, 6, 18, 11, 4))
            .build();

        RunPersistenceSnapshot snapshot = mapper.toSnapshot(
            session,
            List.of(pendingTask),
            "doctor-follow-up-v1",
            "doctor-assistant-agent");

        assertNotNull(snapshot);
        assertEquals("agent_session", snapshot.getLegacyRunTable());
        assertEquals("S001", snapshot.getLegacyRunId());
        assertEquals("agent_pending_task", snapshot.getLegacyApprovalTable());
        assertEquals(List.of("101"), snapshot.getLegacyApprovalIds());
        assertEquals(RunStatus.WAITING_APPROVAL, snapshot.getRun().getStatus());
        assertEquals("S001", snapshot.getRun().getRunId());
        assertEquals("S001", snapshot.getRun().getSessionId());
        assertEquals("doctor-follow-up-v1", snapshot.getRun().getWorkflowId());
        assertEquals(1, snapshot.getApprovalTasks().size());
        assertEquals("create_follow_up_task", snapshot.getApprovalTasks().get(0).getTaskKey());
        assertFalse(snapshot.getRun().getEvents().isEmpty());
        assertEquals(RunEventType.WAITING_APPROVAL,
            snapshot.getRun().getEvents().get(snapshot.getRun().getEvents().size() - 1).getEventType());
    }

    @Test
    void shouldProjectCompletedLegacySessionToCompletedRun() {
        AgentSession session = AgentSession.builder()
            .id("S002")
            .customerId("C002")
            .doctorId("D002")
            .goal("减重复盘")
            .status(SessionStatus.COMPLETED.name())
            .currentStep(4)
            .finalAnswer("建议继续保持饮食记录")
            .createdAt(LocalDateTime.of(2026, 6, 18, 9, 0))
            .updatedAt(LocalDateTime.of(2026, 6, 18, 9, 8))
            .completedAt(LocalDateTime.of(2026, 6, 18, 9, 8))
            .build();

        RunPersistenceSnapshot snapshot = mapper.toSnapshot(
            session,
            List.of(),
            "doctor-retrospective-v1",
            "doctor-assistant-agent");

        assertEquals(RunStatus.COMPLETED, snapshot.getRun().getStatus());
        assertEquals("建议继续保持饮食记录", snapshot.getRun().getOutput().get("finalAnswer"));
        assertEquals(RunEventType.RUN_COMPLETED,
            snapshot.getRun().getEvents().get(snapshot.getRun().getEvents().size() - 1).getEventType());
    }
}
