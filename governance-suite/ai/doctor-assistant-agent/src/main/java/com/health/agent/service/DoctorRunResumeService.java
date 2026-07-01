package com.health.agent.service;

import org.springframework.stereotype.Service;

import com.health.agent.exception.AgentException;
import com.health.agent.model.AgentSession;
import com.health.agent.model.PendingTask;
import com.health.agent.model.TaskStatus;
import com.lx.governance.agentplatform.model.ApprovalDecision;
import com.lx.governance.agentplatform.model.ApprovalDecisionType;
import com.lx.governance.agentplatform.model.ResumeRunRequest;

/**
 * doctor 场景下的统一审批决策与恢复请求服务。
 */
@Service
public class DoctorRunResumeService {

    private final AgentSessionService sessionService;
    private final PendingTaskService pendingTaskService;

    public DoctorRunResumeService(
            AgentSessionService sessionService,
            PendingTaskService pendingTaskService) {
        this.sessionService = sessionService;
        this.pendingTaskService = pendingTaskService;
    }

    public ApprovalDecision approveTask(Long taskId, String reviewComment) {
        PendingTask task = pendingTaskService.approve(taskId, reviewComment);
        if (task == null) {
            throw new AgentException("任务不存在: " + taskId);
        }
        return toDecision(task, ApprovalDecisionType.APPROVED, reviewComment);
    }

    public ApprovalDecision rejectTask(Long taskId, String reviewComment) {
        PendingTask task = pendingTaskService.reject(taskId, reviewComment);
        if (task == null) {
            throw new AgentException("任务不存在: " + taskId);
        }
        return toDecision(task, ApprovalDecisionType.REJECTED, reviewComment);
    }

    public ResumeRunRequest prepareResume(String sessionId, Long approvalTaskId) {
        AgentSession session = sessionService.getById(sessionId);
        if (session == null) {
            throw new AgentException("会话不存在: " + sessionId);
        }
        PendingTask task = pendingTaskService.getById(approvalTaskId);
        if (task == null) {
            throw new AgentException("任务不存在: " + approvalTaskId);
        }
        if (!sessionId.equals(task.getAgentSessionId())) {
            throw new AgentException("任务不属于该会话");
        }
        ApprovalDecisionType decisionType = toDecisionType(task.getStatus());
        if (decisionType == null) {
            throw new AgentException("任务尚未形成可恢复决策，当前状态: " + task.getStatus());
        }
        return ResumeRunRequest.builder()
                .runId(sessionId)
                .approvalTaskId(String.valueOf(approvalTaskId))
                .decisionType(decisionType)
                .operatorId(session.getDoctorId())
                .requestedAt(System.currentTimeMillis())
                .build();
    }

    private ApprovalDecision toDecision(PendingTask task, ApprovalDecisionType decisionType, String comment) {
        return ApprovalDecision.builder()
                .approvalTaskId(task.getId() != null ? String.valueOf(task.getId()) : null)
                .runId(task.getAgentSessionId())
                .decisionType(decisionType)
                .operatorId(task.getDoctorId())
                .comment(comment)
                .decidedAt(task.getReviewedAt() != null
                        ? task.getReviewedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                        : System.currentTimeMillis())
                .build();
    }

    private ApprovalDecisionType toDecisionType(String taskStatus) {
        if (TaskStatus.APPROVED.name().equals(taskStatus)) {
            return ApprovalDecisionType.APPROVED;
        }
        if (TaskStatus.REJECTED.name().equals(taskStatus)) {
            return ApprovalDecisionType.REJECTED;
        }
        if (TaskStatus.EXPIRED.name().equals(taskStatus)) {
            return ApprovalDecisionType.EXPIRED;
        }
        return null;
    }
}
