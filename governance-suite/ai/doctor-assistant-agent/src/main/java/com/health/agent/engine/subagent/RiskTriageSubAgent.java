package com.health.agent.engine.subagent;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RiskTriageSubAgent implements AgentSubAgent {
    @Override
    public String name() {
        return "RiskTriageAgent";
    }

    @Override
    public String responsibility() {
        return "优先识别红旗症状、心理危机、药物执行风险和随访紧急度，先判断是否需要立即转人工或线下就医。";
    }

    @Override
    public List<String> preferredToolNames() {
        return List.of(
                "triage_red_flags",
                "assess_follow_up_urgency",
                "screen_mental_health_risk",
                "analyze_medication_adherence",
                "extract_structured_symptoms",
                "recommend_medical_review");
    }
}
