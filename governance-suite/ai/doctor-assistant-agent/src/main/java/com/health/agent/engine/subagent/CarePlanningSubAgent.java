package com.health.agent.engine.subagent;

import org.springframework.stereotype.Component;

import java.util.List;

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
}
