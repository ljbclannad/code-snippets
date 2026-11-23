package com.lx.governance.aichat.service;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import com.lx.governance.aichat.config.AiChatAgentRouteProperties;
import com.lx.governance.aichat.dto.ChatRequest;
import com.lx.governance.aichat.dto.DoctorAssistantTaskResponse;
import com.lx.governance.aichat.dto.DoctorAssistantTaskStatus;
import com.lx.governance.aichat.dto.SessionContext;
import com.lx.governance.aichat.dto.TaskTriggerDecision;
import com.lx.governance.agentplatform.runtime.PlatformAgentResponse;
import com.lx.governance.agentplatform.runtime.PlatformPendingReviewSnapshot;
import com.lx.governance.agentplatform.runtime.PlatformRouteDecision;
import com.lx.governance.agentplatform.runtime.PlatformRouteRuntime;
import com.lx.governance.agentplatform.runtime.PlatformRouteTarget;

/**
 * 负责 ai-chat-service 入口层与平台公共路由运行时之间的 DTO 适配。
 */
@Component
public class AgentConversationRouteRuntime {

    private final AgentDispatchPolicy agentDispatchPolicy;
    private final PlatformRouteRuntime platformRouteRuntime;

    @Autowired
    public AgentConversationRouteRuntime(
            AgentDispatchPolicy agentDispatchPolicy,
            AiChatAgentRouteProperties properties) {
        this(
                agentDispatchPolicy,
                properties != null && properties.isAutoFallbackToDirectChat(),
                properties != null ? properties.getPendingReviewShortCircuitWindowMs() : 600_000L);
    }

    public AgentConversationRouteRuntime(
            AgentDispatchPolicy agentDispatchPolicy,
            boolean autoFallbackToDirectChat,
            long pendingReviewWindowMillis) {
        this.agentDispatchPolicy = agentDispatchPolicy;
        this.platformRouteRuntime = new PlatformRouteRuntime(autoFallbackToDirectChat, pendingReviewWindowMillis);
    }

    public PlatformRouteDecision evaluateInvocation(
            ChatRequest request,
            SessionContext sessionContext,
            TaskTriggerDecision triggerDecision) {
        AgentDispatchPolicy.Decision dispatchDecision = agentDispatchPolicy.evaluate(request, sessionContext, triggerDecision);
        return platformRouteRuntime.evaluateInvocation(
                pendingReviewSnapshotOf(sessionContext),
                dispatchDecision.invokeAgent(),
                dispatchDecision.reason());
    }

    public PlatformRouteDecision evaluateAgentResponse(DoctorAssistantTaskResponse agentResponse) {
        if (agentResponse != null && "DOCTOR_NOT_ASSIGNED".equals(agentResponse.getFallbackReason())
                && agentResponse.getPatientSafeReply() != null) {
            return new PlatformRouteDecision(PlatformRouteTarget.AGENT, false, "doctor_not_assigned");
        }
        return platformRouteRuntime.evaluateAgentResponse(agentResponseOf(agentResponse));
    }

    public PlatformRouteDecision fallbackDecision(String reason) {
        return platformRouteRuntime.fallbackDecision(reason);
    }

    private PlatformPendingReviewSnapshot pendingReviewSnapshotOf(SessionContext sessionContext) {
        if (sessionContext == null || sessionContext.getLastAgentPendingTaskId() == null) {
            return null;
        }
        return PlatformPendingReviewSnapshot.builder()
                .reviewStatus(sessionContext.getLastAgentReviewStatus())
                .lastOutcomeAt(sessionContext.getLastAgentOutcomeAt())
                .build();
    }

    private PlatformAgentResponse agentResponseOf(DoctorAssistantTaskResponse agentResponse) {
        if (agentResponse == null) {
            return null;
        }
        return PlatformAgentResponse.builder()
                .skipped(agentResponse.getStatus() == DoctorAssistantTaskStatus.SKIPPED)
                .error(agentResponse.getStatus() == DoctorAssistantTaskStatus.ERROR)
                .userVisibleReply(agentResponse.getPatientSafeReply())
                .build();
    }
}
