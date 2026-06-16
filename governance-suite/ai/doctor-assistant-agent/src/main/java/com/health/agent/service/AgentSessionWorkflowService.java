package com.health.agent.service;

import com.health.agent.model.AgentContext;
import com.health.agent.model.AgentResult;
import com.health.agent.model.Message;
import com.health.agent.model.PendingTask;
import com.health.agent.model.Step;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AgentSessionWorkflowService {

    private final AgentSessionService sessionService;
    private final PendingTaskService pendingTaskService;
    private final AgentAuditService agentAuditService;

    public AgentSessionWorkflowService(
        AgentSessionService sessionService,
        PendingTaskService pendingTaskService,
        AgentAuditService agentAuditService
    ) {
        this.sessionService = sessionService;
        this.pendingTaskService = pendingTaskService;
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
        pendingTaskService.save(pendingTask);
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
}
