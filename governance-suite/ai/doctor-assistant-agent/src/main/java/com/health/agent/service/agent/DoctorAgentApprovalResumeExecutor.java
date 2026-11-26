package com.health.agent.service.agent;

import java.util.List;

import org.springframework.stereotype.Component;

import com.alibaba.fastjson2.JSON;
import com.health.agent.exception.AgentException;
import com.health.agent.model.ActionDecision;
import com.health.agent.model.AgentContext;
import com.health.agent.model.AgentSession;
import com.health.agent.model.Message;
import com.health.agent.model.PendingTask;
import com.health.agent.model.SessionStatus;
import com.health.agent.model.Step;
import com.health.agent.model.TaskStatus;
import com.lx.governance.agentplatform.runtime.ApprovalResumeExecutor;

/**
 * doctor 场景的审批恢复执行器。
 */
@Component
public class DoctorAgentApprovalResumeExecutor implements ApprovalResumeExecutor<AgentApprovalResumeState> {

    private final DoctorPlatformResumeStateService platformResumeStateService;
    private final AgentSessionService sessionService;
    private final PendingTaskService pendingTaskService;

    public DoctorAgentApprovalResumeExecutor(
            DoctorPlatformResumeStateService platformResumeStateService,
            AgentSessionService sessionService,
            PendingTaskService pendingTaskService) {
        this.platformResumeStateService = platformResumeStateService;
        this.sessionService = sessionService;
        this.pendingTaskService = pendingTaskService;
    }

    @Override
    public AgentApprovalResumeState loadApprovedResumeState(String sessionId, String approvalTaskId) {
        DoctorPlatformResumeStateService.PlatformResumeState platformState =
                platformResumeStateService.loadResumeState(sessionId, approvalTaskId).orElse(null);

        AgentSession session = platformState != null ? platformState.getSession() : sessionService.getById(sessionId);
        if (session == null) {
            throw new AgentException("会话不存在: " + sessionId);
        }

        if (!SessionStatus.PAUSED.name().equals(session.getStatus())) {
            throw new AgentException("会话状态不正确，当前状态: " + session.getStatus());
        }

        PendingTask task = pendingTaskService.findById(approvalTaskId);
        if (task == null && platformState != null) {
            task = platformState.getPendingTask();
        }
        if (task == null) {
            throw new AgentException("任务不存在: " + approvalTaskId);
        }

        if (!sessionId.equals(task.getAgentSessionId())) {
            throw new AgentException("任务不属于该会话");
        }

        if (!TaskStatus.APPROVED.name().equals(task.getStatus())) {
            throw new AgentException("任务未批准，当前状态: " + task.getStatus());
        }

        List<Step> history = platformState != null ? platformState.getHistory()
                : JSON.parseArray(session.getHistoryJson(), Step.class);
        List<Message> messages = platformState != null ? platformState.getMessages()
                : JSON.parseArray(session.getMessagesJson(), Message.class);
        AgentContext context = platformState != null ? platformState.getContext()
                : parseContext(session.getContextJson(), session.getCustomerId(), session.getDoctorId(), session.getGoal());
        int currentStep = platformState != null ? platformState.getCurrentStep()
                : session.getCurrentStep() != null ? session.getCurrentStep() : 0;
        ActionDecision approvedAction = JSON.parseObject(task.getInputJson(), ActionDecision.class);

        return AgentApprovalResumeState.builder()
                .session(session)
                .pendingTask(task)
                .history(history)
                .messages(messages)
                .context(context)
                .currentStep(currentStep)
                .approvedAction(approvedAction)
                .build();
    }

    private AgentContext parseContext(String contextJson, String customerId, String doctorId, String goal) {
        if (contextJson == null || contextJson.isBlank()) {
            return AgentContext.initialize(customerId, doctorId, goal);
        }
        AgentContext parsed = JSON.parseObject(contextJson, AgentContext.class);
        return parsed != null ? parsed : AgentContext.initialize(customerId, doctorId, goal);
    }
}
