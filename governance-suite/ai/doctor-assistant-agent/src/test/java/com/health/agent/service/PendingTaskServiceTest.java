package com.health.agent.service;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

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

    private PendingTaskService service;

    @BeforeEach
    void setUp() {
        service = new PendingTaskService();
        ReflectionTestUtils.setField(service, "taskMapper", taskMapper);
        ReflectionTestUtils.setField(service, "agentAuditService", agentAuditService);
        ReflectionTestUtils.setField(service, "platformRunPersistenceService", platformRunPersistenceService);
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
    }
}
