package com.health.agent.service.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.health.agent.learning.model.AgentRunOutcome;
import com.health.agent.learning.service.AgentOutcomeRecorder;
import com.health.agent.mapper.PendingTaskMapper;
import com.health.agent.model.PendingTask;
import com.health.agent.model.TaskStatus;

@ExtendWith(MockitoExtension.class)
class PendingTaskServiceTest {

    @Mock
    private PendingTaskMapper taskMapper;

    @Mock
    private AgentAuditService agentAuditService;

    @Mock
    private DoctorPlatformRunPersistenceService platformRunPersistenceService;

    @Mock
    private AgentOutcomeRecorder outcomeRecorder;

    private PendingTaskService service;

    @BeforeEach
    void setUp() {
        service = new PendingTaskService();
        ReflectionTestUtils.setField(service, "taskMapper", taskMapper);
        ReflectionTestUtils.setField(service, "agentAuditService", agentAuditService);
        ReflectionTestUtils.setField(service, "platformRunPersistenceService", platformRunPersistenceService);
        ReflectionTestUtils.setField(service, "outcomeRecorder", outcomeRecorder);
    }

    @Test
    void shouldSyncPlatformApprovalStatusWhenApprove() {
        PendingTask task = PendingTask.builder()
                .id(101L)
                .agentSessionId("S001")
                .toolName("create_follow_up_task")
                .status(TaskStatus.PENDING.name())
                .createdAt(LocalDateTime.now())
                .build();
        when(taskMapper.selectById(101L)).thenReturn(task);

        service.approve(101L, "ok");

        verify(taskMapper).updateById(task);
        verify(platformRunPersistenceService).syncApprovalStatus(task);
        verify(agentAuditService).recordTaskReview(101L, TaskStatus.APPROVED.name(), "ok");
        ArgumentCaptor<AgentRunOutcome> outcomeCaptor = ArgumentCaptor.forClass(AgentRunOutcome.class);
        verify(outcomeRecorder).createOrUpdate(outcomeCaptor.capture());
        assertEquals("S001", outcomeCaptor.getValue().getRunId());
        assertEquals(Boolean.TRUE, outcomeCaptor.getValue().getDoctorAccepted());
        assertEquals(Boolean.FALSE, outcomeCaptor.getValue().getDoctorRejected());
    }

    @Test
    void shouldSyncPlatformApprovalStatusWhenReject() {
        PendingTask task = PendingTask.builder()
                .id(102L)
                .agentSessionId("S002")
                .toolName("create_follow_up_task")
                .status(TaskStatus.PENDING.name())
                .createdAt(LocalDateTime.now())
                .build();
        when(taskMapper.selectById(102L)).thenReturn(task);

        service.reject(102L, "reject");

        verify(taskMapper).updateById(task);
        verify(platformRunPersistenceService).syncApprovalStatus(task);
        verify(agentAuditService).recordTaskReview(102L, TaskStatus.REJECTED.name(), "reject");
        ArgumentCaptor<AgentRunOutcome> outcomeCaptor = ArgumentCaptor.forClass(AgentRunOutcome.class);
        verify(outcomeRecorder).createOrUpdate(outcomeCaptor.capture());
        assertEquals("S002", outcomeCaptor.getValue().getRunId());
        assertEquals(Boolean.FALSE, outcomeCaptor.getValue().getDoctorAccepted());
        assertEquals(Boolean.TRUE, outcomeCaptor.getValue().getDoctorRejected());
        assertEquals(Boolean.TRUE, outcomeCaptor.getValue().getManualOverride());
    }
}
