package com.health.agent.engine.subagent;

import com.health.agent.dto.patient.TaskType;
import com.health.agent.model.ContextEntry;
import com.health.agent.model.AgentContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;

@Component
public class SubAgentCoordinator {

    @Autowired
    private DataCollectionSubAgent dataCollectionSubAgent;

    @Autowired
    private WeightAnalysisSubAgent weightAnalysisSubAgent;

    @Autowired
    private CarePlanningSubAgent carePlanningSubAgent;

    @Autowired
    private RiskTriageSubAgent riskTriageSubAgent;

    public AgentSubAgent select(String goal, AgentContext context) {
        TaskType taskType = resolveTaskType(context);
        if (shouldTriage(goal, context, taskType)) {
            return riskTriageSubAgent;
        }
        if (shouldCollect(goal, context, taskType)) {
            return dataCollectionSubAgent;
        }
        if (shouldAnalyze(goal, context, taskType)) {
            return weightAnalysisSubAgent;
        }
        return carePlanningSubAgent;
    }

    private boolean shouldCollect(String goal, AgentContext context, TaskType taskType) {
        if (context == null || context.getSlots() == null || context.getSlots().isEmpty()) {
            return true;
        }

        boolean hasProfile = hasAnySlot(context, "patient_profile", "profile_snapshot");
        boolean hasWeightHistory = hasAnySlot(context, "weight_history");
        boolean hasPreloadedMemory = hasAnySlot(context, "memory_snapshot", "chat_history", "recent_follow_up_signals");
        boolean hasSessionSignals = hasAnySlot(context, "session_context", "trigger_decision", "patient_message");
        boolean hasAnyAnalysis = context.getSlots().containsKey("plateau_assessment")
            || context.getSlots().containsKey("adherence_analysis")
            || context.getSlots().containsKey("weight_trend_analysis");
        boolean goalNeedsChat = containsAny(goal, "依从", "饮食", "运动", "聊天", "情绪");
        boolean missingChat = !hasAnySlot(context, "chat_history", "memory_snapshot", "recent_follow_up_signals");

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

    private boolean shouldTriage(String goal, AgentContext context, TaskType taskType) {
        boolean riskTask = taskType == TaskType.RISK_STRATIFICATION_AND_ESCALATION;
        boolean riskGoal = containsAny(goal,
                "风险", "红旗", "急诊", "紧急", "恶化", "加重", "胸痛", "呼吸困难", "晕厥",
                "便血", "呕血", "轻生", "自杀", "焦虑", "抑郁", "失眠", "停药", "漏服", "药物");
        boolean hasSignals = hasAnySlot(context,
                "patient_message", "chat_history", "session_context", "trigger_decision", "memory_snapshot");
        boolean triageDone = hasAnySlot(context,
                "red_flag_triage",
                "follow_up_urgency_assessment",
                "mental_health_risk_screen",
                "medication_adherence_analysis");
        if (!riskTask && !riskGoal) {
            return false;
        }
        if (!hasSignals && !riskGoal) {
            return false;
        }
        return !triageDone;
    }

    private boolean shouldAnalyze(String goal, AgentContext context, TaskType taskType) {
        if (context == null || context.getSlots() == null) {
            return false;
        }

        boolean plateauGoal = containsAny(goal, "平台", "停滞", "不下降", "plateau");
        boolean adherenceGoal = containsAny(goal, "依从", "饮食", "运动", "坚持");
        boolean hasPlateau = context.getSlots().containsKey("plateau_assessment");
        boolean hasAdherence = context.getSlots().containsKey("adherence_analysis");
        boolean hasTrend = context.getSlots().containsKey("weight_trend_analysis");
        boolean hasWeightLikeSignals = hasAnySlot(context, "weight_history", "memory_snapshot", "recent_follow_up_signals");
        boolean hasConversationSignals = hasAnySlot(context, "chat_history", "session_context", "patient_message");

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
            return hasConversationSignals && (hasWeightLikeSignals || hasAnySlot(context, "trigger_decision"))
                    && (!hasAdherence || !hasTrend);
        }
        return !hasTrend && hasAnySlot(context, "weight_history", "recent_follow_up_signals");
    }

    private TaskType resolveTaskType(AgentContext context) {
        if (context == null || context.getSlots() == null) {
            return TaskType.NONE;
        }
        ContextEntry entry = context.getSlots().get("patient_task_request");
        if (entry == null || !(entry.getValue() instanceof Map<?, ?> requestMap)) {
            return TaskType.NONE;
        }
        Object taskType = requestMap.get("taskType");
        if (taskType == null) {
            return TaskType.NONE;
        }
        try {
            return TaskType.valueOf(String.valueOf(taskType));
        } catch (IllegalArgumentException ignore) {
            return TaskType.NONE;
        }
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

    private boolean hasAnySlot(AgentContext context, String... slotNames) {
        if (context == null || context.getSlots() == null || slotNames == null) {
            return false;
        }
        for (String slotName : slotNames) {
            ContextEntry entry = context.getSlots().get(slotName);
            if (entry != null && isMeaningful(entry.getValue())) {
                return true;
            }
        }
        return false;
    }

    private boolean isMeaningful(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof String text) {
            return !text.isBlank();
        }
        if (value instanceof Collection<?> collection) {
            return !collection.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        return true;
    }
}
