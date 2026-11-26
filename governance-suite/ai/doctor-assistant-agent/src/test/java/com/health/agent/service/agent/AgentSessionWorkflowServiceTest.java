package com.health.agent.service.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.health.agent.model.AgentContext;
import com.health.agent.model.AgentResult;
import com.health.agent.model.Message;
import com.health.agent.model.PendingTask;
import com.health.agent.model.Step;
import com.health.agent.model.TaskStatus;
import com.lx.governance.agentplatform.model.ApprovalReasonCode;
import com.lx.governance.agentplatform.model.ApprovalRequest;
import com.lx.governance.agentplatform.model.ApprovalStatus;
import com.lx.governance.agentplatform.model.RunEventType;
import com.lx.governance.agentplatform.model.RunStatus;
import com.lx.governance.agentplatform.model.SuspendedExecution;
import com.lx.governance.agentplatform.runtime.ApprovalRepository;
import com.lx.governance.agentplatform.runtime.ApprovalResumeExecutor;

@ExtendWith(MockitoExtension.class)
class AgentSessionWorkflowServiceTest {

    @Mock
    private AgentSessionService sessionService;

    @Mock
    private ApprovalRepository<PendingTask> approvalRepository;

    @Mock
    private ApprovalResumeExecutor<AgentApprovalResumeState> approvalResumeExecutor;

    @Mock
    private AgentAuditService agentAuditService;

    private AgentSessionWorkflowService workflowService;

    @BeforeEach
    void setUp() {
        workflowService = new AgentSessionWorkflowService(
                sessionService,
                approvalRepository,
                approvalResumeExecutor,
                agentAuditService);
    }

    @Test
    void shouldDescribeSuspendedExecutionAsWaitingApproval() {
        PendingTask pendingTask = PendingTask.builder()
                .id(101L)
                .agentSessionId("S001")
                .doctorId("D001")
                .toolName("create_follow_up_task")
                .toolDescription("创建随访任务")
                .reason("需要医生审批")
                .status(TaskStatus.PENDING.name())
                .createdAt(LocalDateTime.of(2026, 6, 17, 20, 0))
                .expireAt(LocalDateTime.of(2026, 6, 17, 21, 0))
                .build();

        SuspendedExecution suspendedExecution =
                workflowService.describeSuspendedExecution("doctor-follow-up-v1", "患者随访分析", pendingTask);

        assertNotNull(suspendedExecution);
        assertEquals("S001", suspendedExecution.getRunId());
        assertEquals(RunStatus.WAITING_APPROVAL, suspendedExecution.getStatus());
        assertEquals("create_follow_up_task", suspendedExecution.getApprovalTask().getTaskKey());
        assertEquals("D001", suspendedExecution.getApprovalTask().getAssigneeId());
    }

    @Test
    void shouldDescribePlatformApprovalRequest() {
        PendingTask pendingTask = PendingTask.builder()
                .id(101L)
                .agentSessionId("S001")
                .doctorId("D001")
                .toolName("patient_follow_up_review")
                .toolDescription("患者随访复核")
                .inputJson("{\"foo\":\"bar\"}")
                .reason("需要医生审批")
                .status(TaskStatus.PENDING.name())
                .createdAt(LocalDateTime.of(2026, 6, 17, 20, 0))
                .expireAt(LocalDateTime.of(2026, 6, 17, 21, 0))
                .build();

        ApprovalRequest approvalRequest =
                workflowService.describeApprovalRequest("doctor-follow-up-v1", "患者随访分析", pendingTask);

        assertNotNull(approvalRequest);
        assertEquals("S001", approvalRequest.getRunId());
        assertEquals("doctor-follow-up-v1", approvalRequest.getWorkflowId());
        assertEquals(ApprovalReasonCode.PATIENT_TASK_HUMAN_REVIEW, approvalRequest.getReasonCode());
        assertEquals(30, approvalRequest.getSlaPolicy().getDueInMinutes());
        assertEquals("patient_follow_up_review", approvalRequest.getPayload().get("toolName"));
    }

    @Test
    void shouldDelegateResumeStateLoadingToApprovalResumeExecutor() {
        AgentApprovalResumeState expected = AgentApprovalResumeState.builder().currentStep(3).build();
        when(approvalResumeExecutor.loadApprovedResumeState("S001", "101")).thenReturn(expected);

        AgentApprovalResumeState state = workflowService.loadApprovedResumeState("S001", 101L);

        assertEquals(3, state.getCurrentStep());
        verify(approvalResumeExecutor).loadApprovedResumeState("S001", "101");
    }

    @Test
    void shouldDescribeStandardRunTransitions() {
        assertEquals(
                RunStatus.RUNNING,
                workflowService.describeResumeTransition("S001", ApprovalStatus.APPROVED).getToStatus());
        assertEquals(
                RunEventType.APPROVAL_RESUMED,
                workflowService.describeResumeTransition("S001", ApprovalStatus.REJECTED).getEventType());
        assertEquals(
                RunStatus.DEGRADED,
                workflowService.describeDegradedTransition("S001", "agent_error").getToStatus());
        assertEquals(
                RunStatus.RUNNING,
                workflowService.describeRetryTransition("S001", RunStatus.FAILED, "manual_retry").getToStatus());
    }

    @Test
    void shouldPauseSessionForApprovalAndRecordAudit() {
        Step approvalStep = Step.thought("需要审批");
        Message approvalMessage = Message.user("hello");
        AgentContext approvalContext = AgentContext.initialize("C001", "D001", "目标");
        PendingTask pendingTask = PendingTask.builder()
                .id(101L)
                .agentSessionId("S001")
                .customerId("C001")
                .doctorId("D001")
                .toolName("create_follow_up_task")
                .status(TaskStatus.PENDING.name())
                .build();

        AgentResult result = AgentResult.pendingApproval(
                "S001",
                pendingTask,
                List.of(),
                1);
        workflowService.pauseSessionForApproval(
                "S001",
                List.of(approvalStep),
                List.of(approvalMessage),
                approvalContext,
                pendingTask,
                "目标",
                result,
                1,
                50L);

        verify(approvalRepository).save(pendingTask);
        verify(sessionService).pauseSession("S001", List.of(approvalStep), List.of(approvalMessage), approvalContext);
        verify(agentAuditService).recordPendingTaskCreated(pendingTask, "目标");
    }
}
