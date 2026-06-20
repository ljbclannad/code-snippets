package com.lx.governance.aichat.service;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import com.lx.governance.aichat.config.AiChatAgentRouteProperties;
import com.lx.governance.aichat.dto.ChatRequest;
import com.lx.governance.aichat.dto.SessionContext;
import com.lx.governance.aichat.dto.TaskTriggerDecision;
import com.lx.governance.agentplatform.model.PlatformDispatchContext;
import com.lx.governance.agentplatform.model.PlatformDispatchDecision;
import com.lx.governance.agentplatform.runtime.PlatformDispatchPolicyEngine;

/**
 * ai-chat 侧对平台灰度/熔断策略的适配器。
 */
@Component
public class AgentDispatchPolicy {

    private final PlatformDispatchPolicyEngine platformDispatchPolicyEngine;

    @Autowired
    public AgentDispatchPolicy(AiChatAgentRouteProperties properties) {
        this(new PlatformDispatchPolicyEngine(
                properties != null ? properties.getEnabledTaskTypes() : java.util.List.of(),
                properties != null ? properties.getEnabledGroupIds() : java.util.List.of(),
                properties != null ? properties.getEnabledUserIds() : java.util.List.of(),
                properties != null ? properties.getEnabledTenantIds() : java.util.List.of(),
                properties != null ? properties.getEnabledAgentVersions() : java.util.List.of(),
                properties != null && properties.isCircuitBreakerOpen()));
    }

    AgentDispatchPolicy(PlatformDispatchPolicyEngine platformDispatchPolicyEngine) {
        this.platformDispatchPolicyEngine = platformDispatchPolicyEngine;
    }

    public Decision evaluate(ChatRequest request, SessionContext sessionContext, TaskTriggerDecision triggerDecision) {
        String taskType = triggerDecision != null && triggerDecision.getTaskType() != null
                ? triggerDecision.getTaskType().name()
                : "NONE";
        PlatformDispatchDecision decision = platformDispatchPolicyEngine.evaluate(PlatformDispatchContext.builder()
                .taskType(taskType)
                .groupId(request != null ? request.getGroupId() : sessionContext != null ? sessionContext.getGroupId() : null)
                .userId(request != null && request.getUserId() != null && !request.getUserId().isBlank()
                        ? request.getUserId()
                        : sessionContext != null && sessionContext.getUserId() != null && !sessionContext.getUserId().isBlank()
                                ? sessionContext.getUserId()
                                : request != null ? request.getCustomerId() : null)
                .tenantId(request != null ? request.getTenantId() : sessionContext != null ? sessionContext.getTenantId() : null)
                .agentVersion(resolveAgentVersion(sessionContext))
                .build());
        return new Decision(decision.isAllowed(), decision.getReason());
    }

    private String resolveAgentVersion(SessionContext sessionContext) {
        if (sessionContext == null || sessionContext.getExtractedData() == null) {
            return null;
        }
        Object value = sessionContext.getExtractedData().get("agentVersion");
        return value != null ? String.valueOf(value) : null;
    }

    public record Decision(boolean invokeAgent, String reason) {
    }
}
