package com.health.agent.engine.subagent;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class WeightAnalysisSubAgent implements AgentSubAgent {
    @Override
    public String name() {
        return "WeightAnalysisAgent";
    }

    @Override
    public String responsibility() {
        return "基于已有体重、聊天和任务状态完成趋势、平台期、依从性和风险信号分析。";
    }

    @Override
    public List<String> preferredToolNames() {
        return List.of(
                "analyze_weight_trend",
                "detect_weight_plateau",
                "analyze_adherence_signals",
                "summarize_symptom_timeline",
                "extract_structured_symptoms");
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
                "weight_trend_analysis",
                "plateau_assessment",
                "adherence_analysis",
                "red_flag_triage",
                "follow_up_urgency_assessment",
                "mental_health_risk_screen",
                "medication_adherence_analysis",
                "structured_symptoms",
                "last_tool_result");
    }
}
