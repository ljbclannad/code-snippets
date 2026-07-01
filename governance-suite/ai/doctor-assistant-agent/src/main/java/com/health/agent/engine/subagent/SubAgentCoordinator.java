package com.health.agent.engine.subagent;

import com.health.agent.dto.patient.TaskType;
import com.health.agent.model.AgentContext;
import com.health.agent.model.ContextEntry;
import com.lx.governance.agentplatform.model.AgentAccessContext;
import com.lx.governance.agentplatform.model.AgentDefinition;
import com.lx.governance.agentplatform.model.AgentSelectionDecision;
import com.lx.governance.agentplatform.model.AgentSelectionMode;
import com.lx.governance.agentplatform.model.AgentSelectionRequest;
import com.lx.governance.agentplatform.runtime.AgentSelector;
import com.lx.governance.agentplatform.registry.AgentRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Component
public class SubAgentCoordinator implements AgentSelector {

    public static final String CAPABILITY_DATA_COLLECTION = "patient-data-collection";
    public static final String CAPABILITY_WEIGHT_ANALYSIS = "weight-analysis";
    public static final String CAPABILITY_CARE_PLANNING = "care-planning";
    public static final String CAPABILITY_RISK_TRIAGE = "risk-triage";

    private final AgentRegistry agentRegistry;
    private final Map<String, AgentSubAgent> subAgentsById;

    public SubAgentCoordinator(AgentRegistry agentRegistry, List<AgentSubAgent> subAgents) {
        this.agentRegistry = agentRegistry;
        this.subAgentsById = new LinkedHashMap<>();
        if (subAgents != null) {
            for (AgentSubAgent subAgent : subAgents) {
                if (subAgent != null) {
                    this.subAgentsById.put(subAgent.agentId(), subAgent);
                }
            }
        }
    }

    public AgentSubAgent select(String goal, AgentContext context) {
        AgentSelectionDecision decision = select(buildSelectionRequest(goal, context));
        return requiredSubAgent(decision.primaryAgentId());
    }

    public AgentSelectionDecision selectDecision(String goal, AgentContext context) {
        return select(buildSelectionRequest(goal, context));
    }

    @Override
    public AgentSelectionDecision select(AgentSelectionRequest request) {
        TaskType taskType = resolveTaskType(request != null ? request.getTaskType() : null);
        Set<String> riskSignals = request != null ? request.getRiskSignals() : Set.of();
        Set<String> contextSlots = request != null ? request.getContextSlots() : Set.of();
        Map<String, Object> rolloutConfig = request != null ? request.getRolloutConfig() : Map.of();
        AgentAccessContext accessContext = request != null ? request.getAccessContext() : null;
        Set<String> availableTools = request != null ? request.getAvailableTools() : Set.of();
        String goal = request != null ? request.getGoal() : null;

        if (shouldRunParallel(taskType, riskSignals, contextSlots, rolloutConfig)) {
            List<String> selectedAgentIds = new ArrayList<>();
            selectedAgentIds.add(resolveRegisteredAgentId(CAPABILITY_RISK_TRIAGE, taskType, accessContext, availableTools));
            selectedAgentIds.add(resolveRegisteredAgentId(CAPABILITY_DATA_COLLECTION, taskType, accessContext, availableTools));
            return AgentSelectionDecision.builder()
                    .mode(AgentSelectionMode.PARALLEL)
                    .selectedAgentIds(selectedAgentIds)
                    .reasons(List.of(
                            "taskType=" + taskType,
                            "riskSignals=" + riskSignals,
                            "rollout=parallel"))
                    .build();
        }

        if (shouldTriage(goal, contextSlots, taskType, riskSignals)) {
            return singleDecision(CAPABILITY_RISK_TRIAGE, taskType, accessContext, availableTools,
                    "risk triage required");
        }
        if (shouldCollect(goal, contextSlots, taskType)) {
            return singleDecision(CAPABILITY_DATA_COLLECTION, taskType, accessContext, availableTools,
                    "context hydration required");
        }
        if (shouldAnalyze(goal, contextSlots, taskType)) {
            return singleDecision(CAPABILITY_WEIGHT_ANALYSIS, taskType, accessContext, availableTools,
                    "analysis slots missing");
        }
        return singleDecision(CAPABILITY_CARE_PLANNING, taskType, accessContext, availableTools,
                "planning stage ready");
    }

    private AgentSelectionDecision singleDecision(
            String capability,
            TaskType taskType,
            AgentAccessContext accessContext,
            Set<String> availableTools,
            String reason) {
        return AgentSelectionDecision.builder()
                .mode(AgentSelectionMode.SERIAL)
                .selectedAgentIds(List.of(resolveRegisteredAgentId(capability, taskType, accessContext, availableTools)))
                .reasons(List.of(
                        "capability=" + capability,
                        "taskType=" + taskType,
                        reason))
                .build();
    }

    private boolean shouldCollect(String goal, Set<String> contextSlots, TaskType taskType) {
        if (contextSlots == null || contextSlots.isEmpty()) {
            return true;
        }

        boolean hasProfile = hasAnySlot(contextSlots, "patient_profile", "profile_snapshot");
        boolean hasWeightHistory = hasAnySlot(contextSlots, "weight_history");
        boolean hasPreloadedMemory = hasAnySlot(contextSlots, "memory_snapshot", "chat_history", "recent_follow_up_signals");
        boolean hasSessionSignals = hasAnySlot(contextSlots, "session_context", "trigger_decision", "patient_message");
        boolean hasAnyAnalysis = contextSlots.contains("plateau_assessment")
            || contextSlots.contains("adherence_analysis")
            || contextSlots.contains("weight_trend_analysis");
        boolean goalNeedsChat = containsAny(goal, "依从", "饮食", "运动", "聊天", "情绪");
        boolean missingChat = !hasAnySlot(contextSlots, "chat_history", "memory_snapshot", "recent_follow_up_signals");

        if (!hasProfile && !hasWeightHistory && !hasSessionSignals && !hasPreloadedMemory && !hasAnyAnalysis) {
            if (taskType == TaskType.RISK_STRATIFICATION_AND_ESCALATION && hasSessionSignals) {
                return false;
            }
            return true;
        }
        if (!hasWeightHistory && !hasPreloadedMemory && !hasAnyAnalysis) {
            return true;
        }
        if ((taskType == TaskType.RISK_STRATIFICATION_AND_ESCALATION
                || taskType == TaskType.NEXT_FOLLOW_UP_ACTION_RECOMMENDATION)
                && (!hasProfile || (!hasPreloadedMemory && !hasSessionSignals))) {
            return true;
        }
        return goalNeedsChat && missingChat && !hasAnyAnalysis;
    }

    private boolean shouldTriage(String goal, Set<String> contextSlots, TaskType taskType, Set<String> riskSignals) {
        boolean riskTask = taskType == TaskType.RISK_STRATIFICATION_AND_ESCALATION;
        boolean riskGoal = containsAny(goal,
                "风险", "红旗", "急诊", "紧急", "恶化", "加重", "胸痛", "呼吸困难", "晕厥",
                "便血", "呕血", "轻生", "自杀", "焦虑", "抑郁", "失眠", "停药", "漏服", "药物");
        boolean hasSignals = hasAnySlot(contextSlots,
                "patient_message", "chat_history", "session_context", "trigger_decision", "memory_snapshot");
        boolean triageDone = hasAnySlot(contextSlots,
                "red_flag_triage",
                "follow_up_urgency_assessment",
                "mental_health_risk_screen",
                "medication_adherence_analysis");
        if (!riskTask && !riskGoal && (riskSignals == null || riskSignals.isEmpty())) {
            return false;
        }
        if (!hasSignals && !riskGoal && (riskSignals == null || riskSignals.isEmpty())) {
            return false;
        }
        return !triageDone;
    }

    private boolean shouldAnalyze(String goal, Set<String> contextSlots, TaskType taskType) {
        if (contextSlots == null || contextSlots.isEmpty()) {
            return false;
        }

        boolean plateauGoal = containsAny(goal, "平台", "停滞", "不下降", "plateau");
        boolean adherenceGoal = containsAny(goal, "依从", "饮食", "运动", "坚持");
        boolean hasPlateau = contextSlots.contains("plateau_assessment");
        boolean hasAdherence = contextSlots.contains("adherence_analysis");
        boolean hasTrend = contextSlots.contains("weight_trend_analysis");
        boolean hasWeightLikeSignals = hasAnySlot(contextSlots, "weight_history", "memory_snapshot", "recent_follow_up_signals");
        boolean hasConversationSignals = hasAnySlot(contextSlots, "chat_history", "session_context", "patient_message");

        if (plateauGoal && hasWeightLikeSignals && !hasPlateau) {
            return true;
        }
        if (adherenceGoal && (hasConversationSignals || hasWeightLikeSignals) && !hasAdherence) {
            return true;
        }
        if (taskType == TaskType.PATIENT_FOLLOW_UP_ANALYSIS) {
            return hasWeightLikeSignals && (!hasTrend || !hasPlateau || !hasAdherence);
        }
        if (taskType == TaskType.RISK_STRATIFICATION_AND_ESCALATION) {
            return hasConversationSignals && (hasWeightLikeSignals || hasAnySlot(contextSlots, "trigger_decision"))
                    && (!hasAdherence || !hasTrend);
        }
        return !hasTrend && hasAnySlot(contextSlots, "weight_history", "recent_follow_up_signals");
    }

    private boolean shouldRunParallel(
            TaskType taskType,
            Set<String> riskSignals,
            Set<String> contextSlots,
            Map<String, Object> rolloutConfig) {
        boolean rolloutParallel = rolloutConfig != null
                && Boolean.parseBoolean(String.valueOf(rolloutConfig.getOrDefault("parallelRiskReview", "false")));
        boolean missingEvidence = !hasAnySlot(contextSlots, "patient_profile", "weight_history");
        return rolloutParallel
                && taskType == TaskType.RISK_STRATIFICATION_AND_ESCALATION
                && riskSignals != null
                && !riskSignals.isEmpty()
                && missingEvidence;
    }

    private AgentSelectionRequest buildSelectionRequest(String goal, AgentContext context) {
        TaskType taskType = resolveTaskType(context);
        AgentAccessContext accessContext = buildAccessContext(context);
        Set<String> contextSlots = context != null && context.getSlots() != null
                ? new LinkedHashSet<>(context.getSlots().keySet())
                : Set.of();
        Set<String> riskSignals = new LinkedHashSet<>();
        if (containsAny(goal, "风险", "胸痛", "呼吸困难", "焦虑", "抑郁", "停药", "漏服")) {
            riskSignals.add("goal-risk-signal");
        }
        String slotText = summarizeRiskRelevantText(context);
        if (containsAny(slotText, "风险", "胸痛", "呼吸困难", "焦虑", "抑郁", "停药", "漏服", "轻生", "恶化")) {
            riskSignals.add("context-risk-signal");
        }

        Map<String, Object> rolloutConfig = readRolloutConfig(context);
        Set<String> availableTools = readAvailableTools(context, accessContext);
        return AgentSelectionRequest.builder()
                .goal(goal)
                .taskType(taskType.name())
                .accessContext(accessContext)
                .contextSlots(contextSlots)
                .riskSignals(riskSignals)
                .rolloutConfig(rolloutConfig)
                .availableTools(availableTools)
                .build();
    }

    private String summarizeRiskRelevantText(AgentContext context) {
        if (context == null || context.getSlots() == null || context.getSlots().isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        appendSlotValue(builder, context.getSlots().get("patient_message"));
        appendSlotValue(builder, context.getSlots().get("chat_history"));
        appendSlotValue(builder, context.getSlots().get("trigger_decision"));
        return builder.toString();
    }

    private void appendSlotValue(StringBuilder builder, ContextEntry entry) {
        if (builder == null || entry == null || entry.getValue() == null) {
            return;
        }
        builder.append(String.valueOf(entry.getValue())).append(' ');
    }

    private TaskType resolveTaskType(AgentContext context) {
        return resolveTaskType(taskTypeValue(context));
    }

    private TaskType resolveTaskType(String taskType) {
        if (taskType == null || taskType.isBlank()) {
            return TaskType.NONE;
        }
        try {
            return TaskType.valueOf(taskType);
        } catch (IllegalArgumentException ignore) {
            return TaskType.NONE;
        }
    }

    private String taskTypeValue(AgentContext context) {
        Map<String, Object> requestMap = slotValueAsMap(context, "patient_task_request");
        return requestMap != null && requestMap.get("taskType") != null
                ? String.valueOf(requestMap.get("taskType"))
                : TaskType.NONE.name();
    }

    private boolean containsAny(String goal, String... keywords) {
        if (goal == null || goal.isBlank()) {
            return false;
        }
        for (String keyword : keywords) {
            if (goal.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAnySlot(Set<String> slotNames, String... candidates) {
        if (slotNames == null || slotNames.isEmpty() || candidates == null) {
            return false;
        }
        for (String candidate : candidates) {
            if (slotNames.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    List<String> registeredSubAgentNames() {
        return subAgentsById.values().stream()
                .filter(Objects::nonNull)
                .map(AgentSubAgent::name)
                .toList();
    }

    private String resolveRegisteredAgentId(
            String capability,
            TaskType taskType,
            AgentAccessContext accessContext,
            Set<String> availableTools) {
        return agentRegistry.listVisible(accessContext).stream()
                .filter(definition -> supportsCapability(definition, capability))
                .filter(definition -> supportsTaskType(definition, taskType))
                .filter(definition -> supportsAvailableTools(definition, availableTools))
                .filter(definition -> definition.getStatus() == com.lx.governance.agentplatform.model.AgentStatus.ENABLED)
                .findFirst()
                .map(AgentDefinition::getAgentId)
                .orElseThrow(() -> new IllegalStateException(
                        "No registered agent found for capability=" + capability + ", taskType=" + taskType));
    }

    private boolean supportsCapability(AgentDefinition definition, String capability) {
        return definition != null
                && definition.getCapabilities() != null
                && definition.getCapabilities().contains(capability);
    }

    private boolean supportsTaskType(AgentDefinition definition, TaskType taskType) {
        if (definition == null || definition.getSupportedTaskTypes() == null || definition.getSupportedTaskTypes().isEmpty()) {
            return true;
        }
        return taskType == null || taskType == TaskType.NONE || definition.getSupportedTaskTypes().contains(taskType.name());
    }

    private boolean supportsAvailableTools(AgentDefinition definition, Set<String> availableTools) {
        if (definition == null || definition.getAllowedTools() == null || definition.getAllowedTools().isEmpty()) {
            return true;
        }
        if (availableTools == null || availableTools.isEmpty()) {
            return true;
        }
        return definition.getAllowedTools().stream().allMatch(availableTools::contains);
    }

    private Map<String, Object> readRolloutConfig(AgentContext context) {
        Map<String, Object> sessionContext = slotValueAsMap(context, "session_context");
        Map<String, Object> patientTaskRequest = slotValueAsMap(context, "patient_task_request");
        Map<String, Object> rollout = new LinkedHashMap<>();
        Object sessionRollout = sessionContext != null ? sessionContext.get("selectorRollout") : null;
        if (sessionRollout instanceof Map<?, ?> map) {
            map.forEach((key, value) -> rollout.put(String.valueOf(key), value));
        }
        Object requestRollout = patientTaskRequest != null ? patientTaskRequest.get("selectorRollout") : null;
        if (requestRollout instanceof Map<?, ?> map) {
            map.forEach((key, value) -> rollout.put(String.valueOf(key), value));
        }
        return rollout;
    }

    private Set<String> readAvailableTools(AgentContext context, AgentAccessContext accessContext) {
        ContextEntry entry = context != null && context.getSlots() != null ? context.getSlots().get("available_tools") : null;
        if (entry != null && entry.getValue() instanceof Collection<?> collection) {
            Set<String> tools = new LinkedHashSet<>();
            for (Object item : collection) {
                if (item != null) {
                    tools.add(String.valueOf(item));
                }
            }
            if (!tools.isEmpty()) {
                return tools;
            }
        }
        return agentRegistry.listVisible(accessContext).stream()
                .map(AgentDefinition::getAllowedTools)
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private AgentAccessContext buildAccessContext(AgentContext context) {
        Map<String, Object> sessionContext = slotValueAsMap(context, "session_context");
        Map<String, Object> patientTaskRequest = slotValueAsMap(context, "patient_task_request");
        return AgentAccessContext.builder()
                .groupId(stringValue(
                        sessionContext != null ? sessionContext.get("groupId") : null,
                        patientTaskRequest != null ? patientTaskRequest.get("groupId") : null))
                .userId(stringValue(
                        context != null ? context.getCustomerId() : null,
                        sessionContext != null ? sessionContext.get("customerId") : null,
                        patientTaskRequest != null ? patientTaskRequest.get("customerId") : null))
                .tenantId(stringValue(
                        sessionContext != null ? sessionContext.get("tenantId") : null,
                        patientTaskRequest != null ? patientTaskRequest.get("tenantId") : null))
                .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> slotValueAsMap(AgentContext context, String slotName) {
        if (context == null || context.getSlots() == null) {
            return null;
        }
        ContextEntry entry = context.getSlots().get(slotName);
        if (entry == null || !(entry.getValue() instanceof Map<?, ?> value)) {
            return null;
        }
        return (Map<String, Object>) value;
    }

    private String stringValue(Object... candidates) {
        if (candidates == null) {
            return null;
        }
        for (Object candidate : candidates) {
            if (candidate instanceof String text && !text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    public AgentSubAgent requiredSubAgent(String agentId) {
        AgentSubAgent subAgent = subAgentsById.get(agentId);
        if (subAgent == null) {
            throw new IllegalStateException("SubAgent bean not registered for agentId: " + agentId);
        }
        return subAgent;
    }
}
