package com.lx.governance.aichat.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.lx.governance.aichat.dto.ChatRequest;
import com.lx.governance.aichat.dto.TaskTriggerDecision;

/**
 * Agent 灰度、熔断与试运行策略。
 */
@Component
public class AgentDispatchPolicy {

    private final Set<String> enabledTaskTypes;
    private final Set<String> enabledGroupIds;
    private final boolean circuitBreakerOpen;

    public AgentDispatchPolicy(
            @Value("${ai-chat.agent.enabled-task-types:}") List<String> enabledTaskTypes,
            @Value("${ai-chat.agent.enabled-group-ids:}") List<String> enabledGroupIds,
            @Value("${ai-chat.agent.circuit-breaker-open:false}") boolean circuitBreakerOpen) {
        this.enabledTaskTypes = new HashSet<>(enabledTaskTypes);
        this.enabledGroupIds = new HashSet<>(enabledGroupIds);
        this.circuitBreakerOpen = circuitBreakerOpen;
    }

    public Decision evaluate(ChatRequest request, TaskTriggerDecision triggerDecision) {
        if (circuitBreakerOpen) {
            return new Decision(false, "agent_circuit_breaker_open");
        }

        String taskType = triggerDecision != null && triggerDecision.getTaskType() != null
                ? triggerDecision.getTaskType().name()
                : "NONE";
        if (!enabledTaskTypes.isEmpty() && !enabledTaskTypes.contains(taskType)) {
            return new Decision(false, "task_type_not_gray_enabled");
        }

        String groupId = request != null ? request.getGroupId() : null;
        if (!enabledGroupIds.isEmpty() && (groupId == null || !enabledGroupIds.contains(groupId))) {
            return new Decision(false, "group_not_gray_enabled");
        }

        return new Decision(true, "gray_enabled");
    }

    public record Decision(boolean invokeAgent, String reason) {
    }
}
