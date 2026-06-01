package com.health.agent.engine.subagent;

import org.springframework.stereotype.Component;

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
}
