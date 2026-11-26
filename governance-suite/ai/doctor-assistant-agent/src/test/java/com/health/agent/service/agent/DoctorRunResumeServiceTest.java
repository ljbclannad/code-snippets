package com.health.agent.service.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.health.agent.exception.AgentException;
import com.health.agent.model.AgentSession;
import com.health.agent.model.PendingTask;
import com.health.agent.model.TaskStatus;
import com.lx.governance.agentplatform.model.ApprovalDecisionType;

@ExtendWith(MockitoExtension.class)
class DoctorRunResumeServiceTest {

    @Mock
    private AgentSessionService sessionService;

    @Mock
    private PendingTaskService pendingTaskService;

    private DoctorRunResumeService service;

    @BeforeEach
    void setUp() {
        service = new DoctorRunResumeService(sessionService, pendingTaskService);
    }

    @Test
    void shouldPrepareUnifiedResumeRunRequest() {
        AgentSession session = AgentSession.builder()
                .id("S001")
                .doctorId("D001")
                .build();
        PendingTask task = PendingTask.builder()
                .id(101L)
                .agentSessionId("S001")
                .status(TaskStatus.APPROVED.name())
                .build();
        when(sessionService.getById("S001")).thenReturn(session);
        when(pendingTaskService.getById(101L)).thenReturn(task);

        var request = service.prepareResume("S001", 101L);

        assertEquals("S001", request.getRunId());
        assertEquals("101", request.getApprovalTaskId());
        assertEquals(ApprovalDecisionType.APPROVED, request.getDecisionType());
        assertEquals("D001", request.getOperatorId());
    }

    @Test
    void shouldRejectResumeWhenTaskStillPending() {
        AgentSession session = AgentSession.builder()
                .id("S001")
                .doctorId("D001")
                .build();
        PendingTask task = PendingTask.builder()
                .id(101L)
                .agentSessionId("S001")
                .status(TaskStatus.PENDING.name())
                .build();
        when(sessionService.getById("S001")).thenReturn(session);
        when(pendingTaskService.getById(101L)).thenReturn(task);

        assertThrows(AgentException.class, () -> service.prepareResume("S001", 101L));
    }

    @Test
    void shouldReturnApprovalDecisionAfterApprove() {
        PendingTask task = PendingTask.builder()
                .id(101L)
                .agentSessionId("S001")
                .doctorId("D001")
                .status(TaskStatus.APPROVED.name())
                .reviewedAt(LocalDateTime.of(2026, 6, 18, 10, 30))
                .build();
        when(pendingTaskService.approve(101L, "ok")).thenReturn(task);

        var decision = service.approveTask(101L, "ok");

        assertEquals(ApprovalDecisionType.APPROVED, decision.getDecisionType());
        assertEquals("S001", decision.getRunId());
        assertEquals("D001", decision.getOperatorId());
    }
}
