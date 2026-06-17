package com.health.agent.engine.subagent;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DataCollectionSubAgent implements AgentSubAgent {
    @Override
    public String name() {
        return "DataCollectionAgent";
    }

    @Override
    public String responsibility() {
        return "优先补齐患者档案、体重历史、聊天记录、处方历史等基础事实，不急于给结论。";
    }

    @Override
    public List<String> preferredToolNames() {
        return List.of(
                "get_patient_profile",
                "get_weight_history",
                "get_chat_history",
                "get_prescription_history",
                "check_self_monitoring_completeness");
    }
}
