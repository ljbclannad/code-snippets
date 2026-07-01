package com.health.agent.engine.subagent;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class CarePlanningSubAgent implements AgentSubAgent {
    @Override
    public String name() {
        return "CarePlanningAgent";
    }

    @Override
    public String responsibility() {
        return "基于已收集事实和分析结果，生成建议动作、随访任务和高风险审批请求。";
    }

    @Override
    public List<String> preferredToolNames() {
        return List.of(
                "recommend_follow_up_data_requests",
                "generate_patient_education_points",
                "create_follow_up_task",
                "send_reminder",
                "recommend_medical_review");
    }

    @Override
    public Set<String> visibleSlotKeys() {
        return Set.of(
                "patient_task_request",
                "session_context",
                "profile_snapshot",
                "patient_profile",
                "memory_snapshot",
                "chat_history",
                "recent_follow_up_signals",
                "patient_message",
                "weight_history",
                "prescription_history",
                "follow_up_tasks",
                "weight_trend_analysis",
                "plateau_assessment",
                "adherence_analysis",
                "red_flag_triage",
                "follow_up_urgency_assessment",
                "mental_health_risk_screen",
                "medication_adherence_analysis",
                "structured_symptoms",
                "health_knowledge",
                "last_tool_result");
    }
}
