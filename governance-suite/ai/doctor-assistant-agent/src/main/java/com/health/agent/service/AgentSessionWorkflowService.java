package com.health.agent.service;

import com.alibaba.fastjson2.JSON;
import com.health.agent.exception.AgentException;
import com.health.agent.model.AgentContext;
import com.health.agent.model.AgentResult;
import com.health.agent.model.Message;
import com.health.agent.model.PendingTask;
import com.health.agent.model.Step;
import com.lx.governance.agentplatform.model.ApprovalDecisionType;
import com.lx.governance.agentplatform.model.ApprovalReasonCode;
import com.lx.governance.agentplatform.model.ApprovalReminderPolicy;
import com.lx.governance.agentplatform.model.ApprovalRequest;
import com.lx.governance.agentplatform.model.ApprovalSlaPolicy;
import com.lx.governance.agentplatform.model.ApprovalStatus;
import com.lx.governance.agentplatform.model.ApprovalTask;
import com.lx.governance.agentplatform.model.RunStatus;
import com.lx.governance.agentplatform.model.RunTransition;
import com.lx.governance.agentplatform.model.RunTransitionTrigger;
import com.lx.governance.agentplatform.model.SuspendedExecution;
import com.lx.governance.agentplatform.runtime.ApprovalRepository;
import com.lx.governance.agentplatform.runtime.ApprovalResumeExecutor;
import com.lx.governance.agentplatform.runtime.ApprovalService;
import com.lx.governance.agentplatform.runtime.RunStateMachine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;

@Service
public class AgentSessionWorkflowService implements ApprovalService<PendingTask, AgentApprovalResumeState> {

    private final RunStateMachine runStateMachine = new RunStateMachine();

    private final AgentSessionService sessionService;
    private final ApprovalRepository<PendingTask> approvalRepository;
    private final ApprovalResumeExecutor<AgentApprovalResumeState> approvalResumeExecutor;
    private final AgentAuditService agentAuditService;

    public AgentSessionWorkflowService(
        AgentSessionService sessionService,
        ApprovalRepository<PendingTask> approvalRepository,
        ApprovalResumeExecutor<AgentApprovalResumeState> approvalResumeExecutor,
        AgentAuditService agentAuditService
    ) {
        this.sessionService = sessionService;
        this.approvalRepository = approvalRepository;
        this.approvalResumeExecutor = approvalResumeExecutor;
        this.agentAuditService = agentAuditService;
    }

    @Transactional
    public PendingTask pauseSessionForApproval(
        String sessionId,
        List<Step> history,
        List<Message> messages,
        AgentContext context,
        PendingTask pendingTask,
        String goal,
        AgentResult result,
        int totalSteps,
        long latencyMs
    ) {
        approvalRepository.save(pendingTask);
        agentAuditService.recordPendingTaskCreated(pendingTask, goal);
        sessionService.pauseSession(sessionId, history, messages, context);
        agentAuditService.recordSessionResult(
            sessionId,
            pendingTask.getCustomerId(),
            pendingTask.getDoctorId(),
            goal,
            result,
            totalSteps,
            latencyMs
        );
        return pendingTask;
    }

    public SuspendedExecution describeSuspendedExecution(String workflowId, String goal, PendingTask pendingTask) {
        if (pendingTask == null) {
            return null;
        }
        return SuspendedExecution.builder()
            .runId(pendingTask.getAgentSessionId())
            .workflowId(workflowId)
            .goal(goal)
            .suspendedNodeType("wait_approval")
            .status(RunStatus.WAITING_APPROVAL)
            .approvalTask(toApprovalTask(pendingTask))
            .suspendedAt(toEpochMillis(pendingTask.getCreatedAt()))
            .build();
    }

    public ApprovalRequest describeApprovalRequest(String workflowId, String goal, PendingTask pendingTask) {
        if (pendingTask == null) {
            return null;
        }
        return ApprovalRequest.builder()
            .approvalTaskId(pendingTask.getId() != null ? String.valueOf(pendingTask.getId()) : null)
            .runId(pendingTask.getAgentSessionId())
            .workflowId(workflowId)
            .title(defaultTitle(pendingTask))
            .description(defaultDescription(goal, pendingTask))
            .reasonCode(resolveReasonCode(pendingTask))
            .reasonDetail(pendingTask.getReason())
            .assigneeId(pendingTask.getDoctorId())
            .status(toApprovalStatus(pendingTask.getStatus()))
            .payload(Map.of(
                "toolName", pendingTask.getToolName(),
                "toolDescription", pendingTask.getToolDescription(),
                "inputJson", pendingTask.getInputJson() != null ? pendingTask.getInputJson() : ""
            ))
            .slaPolicy(defaultSlaPolicy(pendingTask))
            .createdAt(toEpochMillis(pendingTask.getCreatedAt()))
            .expireAt(toEpochMillis(pendingTask.getExpireAt()))
            .build();
    }

    public AgentApprovalResumeState loadApprovedResumeState(String sessionId, Long approvedTaskId) {
        return loadApprovedResumeState(sessionId, approvedTaskId != null ? String.valueOf(approvedTaskId) : null);
    }

    public RunTransition describeResumeTransition(String sessionId, ApprovalStatus approvalStatus) {
        RunTransitionTrigger trigger = approvalStatus == ApprovalStatus.REJECTED
            ? RunTransitionTrigger.APPROVAL_REJECTED_RESUME
            : RunTransitionTrigger.APPROVAL_APPROVED_RESUME;
        return runStateMachine.transition(
            sessionId,
            RunStatus.WAITING_APPROVAL,
            trigger,
            approvalStatus != null ? approvalStatus.name().toLowerCase() : "approved");
    }

    public RunTransition describeRetryTransition(String sessionId, RunStatus currentStatus, String reason) {
        return runStateMachine.transition(sessionId, currentStatus, RunTransitionTrigger.RETRY, reason);
    }

    public RunTransition describeFailureTransition(String sessionId, String reason) {
        return runStateMachine.transition(sessionId, RunStatus.RUNNING, RunTransitionTrigger.FAIL, reason);
    }

    public RunTransition describeDegradedTransition(String sessionId, String reason) {
        return runStateMachine.transition(sessionId, RunStatus.RUNNING, RunTransitionTrigger.FALLBACK_TO_DIRECT_CHAT, reason);
    }

    @Override
    public AgentApprovalResumeState loadApprovedResumeState(String sessionId, String approvalTaskId) {
        return approvalResumeExecutor.loadApprovedResumeState(sessionId, approvalTaskId);
    }

    @Transactional
    public void completeSession(
        String sessionId,
        String customerId,
        String doctorId,
        String goal,
        List<Step> history,
        AgentContext context,
        AgentResult result,
        int totalSteps,
        long latencyMs
    ) {
        sessionService.completeSession(sessionId, history, context);
        agentAuditService.recordSessionResult(sessionId, customerId, doctorId, goal, result, totalSteps, latencyMs);
    }

    @Transactional
    public void failSession(
        String sessionId,
        String customerId,
        String doctorId,
        String goal,
        String errorMessage,
        AgentResult result,
        int totalSteps,
        long latencyMs
    ) {
        sessionService.failSession(sessionId, errorMessage);
        agentAuditService.recordSessionResult(sessionId, customerId, doctorId, goal, result, totalSteps, latencyMs);
    }

    private ApprovalTask toApprovalTask(PendingTask pendingTask) {
        return ApprovalTask.builder()
            .approvalTaskId(pendingTask.getId() != null ? String.valueOf(pendingTask.getId()) : null)
            .runId(pendingTask.getAgentSessionId())
            .taskKey(pendingTask.getToolName())
            .taskName(pendingTask.getToolDescription())
            .reason(pendingTask.getReason())
            .assigneeId(pendingTask.getDoctorId())
            .status(toApprovalStatus(pendingTask.getStatus()))
            .createdAt(toEpochMillis(pendingTask.getCreatedAt()))
            .expireAt(toEpochMillis(pendingTask.getExpireAt()))
            .build();
    }

    private ApprovalStatus toApprovalStatus(String status) {
        if (status == null || status.isBlank()) {
            return ApprovalStatus.PENDING;
        }
        try {
            return ApprovalStatus.valueOf(status);
        } catch (IllegalArgumentException ignore) {
            return ApprovalStatus.PENDING;
        }
    }

    private Long toEpochMillis(java.time.LocalDateTime time) {
        if (time == null) {
            return null;
        }
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private String defaultTitle(PendingTask pendingTask) {
        return pendingTask.getToolDescription() != null && !pendingTask.getToolDescription().isBlank()
            ? pendingTask.getToolDescription()
            : "待审批任务";
    }

    private String defaultDescription(String goal, PendingTask pendingTask) {
        String taskGoal = goal != null && !goal.isBlank() ? goal : "当前运行任务";
        String toolName = pendingTask.getToolName() != null ? pendingTask.getToolName() : "unknown_tool";
        return taskGoal + " 触发审批，请确认是否允许执行工具 " + toolName;
    }

    private ApprovalReasonCode resolveReasonCode(PendingTask pendingTask) {
        String toolName = pendingTask.getToolName();
        if ("patient_follow_up_review".equals(toolName)) {
            return ApprovalReasonCode.PATIENT_TASK_HUMAN_REVIEW;
        }
        if (toolName != null && (toolName.contains("prescription") || toolName.contains("medication"))) {
            return ApprovalReasonCode.PRESCRIPTION_CHANGE_REVIEW;
        }
        if (toolName != null && (toolName.contains("diagnosis") || toolName.contains("triage"))) {
            return ApprovalReasonCode.DIAGNOSIS_SENSITIVE_REVIEW;
        }
        return ApprovalReasonCode.HIGH_RISK_TOOL_CALL;
    }

    private ApprovalSlaPolicy defaultSlaPolicy(PendingTask pendingTask) {
        return ApprovalSlaPolicy.builder()
            .dueInMinutes(30)
            .expireInMinutes(60)
            .timeoutDecisionType(ApprovalDecisionType.EXPIRED)
            .reminderPolicy(ApprovalReminderPolicy.builder()
                .firstReminderAfterMinutes(10)
                .repeatReminderEveryMinutes(10)
                .maxReminderCount(3)
                .build())
            .build();
    }
}
