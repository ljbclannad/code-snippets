package com.health.agent.engine.subagent;

import com.health.agent.model.AgentContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SubAgentCoordinator {

    @Autowired
    private DataCollectionSubAgent dataCollectionSubAgent;

    @Autowired
    private WeightAnalysisSubAgent weightAnalysisSubAgent;

    @Autowired
    private CarePlanningSubAgent carePlanningSubAgent;

    public AgentSubAgent select(String goal, AgentContext context) {
        if (shouldCollect(goal, context)) {
            return dataCollectionSubAgent;
        }
        if (shouldAnalyze(goal, context)) {
            return weightAnalysisSubAgent;
        }
        return carePlanningSubAgent;
    }

    private boolean shouldCollect(String goal, AgentContext context) {
        if (context == null || context.getSlots() == null || context.getSlots().isEmpty()) {
            return true;
        }

        boolean hasProfile = context.getSlots().containsKey("patient_profile");
        boolean hasWeightHistory = context.getSlots().containsKey("weight_history");
        boolean hasAnyAnalysis = context.getSlots().containsKey("plateau_assessment")
            || context.getSlots().containsKey("adherence_analysis")
            || context.getSlots().containsKey("weight_trend_analysis");
        boolean goalNeedsChat = containsAny(goal, "依从", "饮食", "运动", "聊天", "情绪");
        boolean missingChat = !context.getSlots().containsKey("chat_history");

        if (!hasWeightHistory && !hasAnyAnalysis) {
            return true;
        }
        return goalNeedsChat && missingChat && !hasAnyAnalysis;
    }

    private boolean shouldAnalyze(String goal, AgentContext context) {
        if (context == null || context.getSlots() == null) {
            return false;
        }

        boolean plateauGoal = containsAny(goal, "平台", "停滞", "不下降", "plateau");
        boolean adherenceGoal = containsAny(goal, "依从", "饮食", "运动", "坚持");
        boolean hasPlateau = context.getSlots().containsKey("plateau_assessment");
        boolean hasAdherence = context.getSlots().containsKey("adherence_analysis");
        boolean hasTrend = context.getSlots().containsKey("weight_trend_analysis");

        if (plateauGoal && !hasPlateau) {
            return true;
        }
        if (adherenceGoal && !hasAdherence) {
            return true;
        }
        return !hasTrend && context.getSlots().containsKey("weight_history");
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
}
