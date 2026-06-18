package com.health.agent.config;

import java.util.List;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.health.agent.engine.subagent.AgentSubAgent;
import com.health.agent.engine.subagent.CarePlanningSubAgent;
import com.health.agent.engine.subagent.DataCollectionSubAgent;
import com.health.agent.engine.subagent.RiskTriageSubAgent;
import com.health.agent.engine.subagent.WeightAnalysisSubAgent;
import com.lx.governance.agentplatform.model.AgentDefinition;
import com.lx.governance.agentplatform.model.AgentStatus;
import com.lx.governance.agentplatform.model.AgentType;
import com.lx.governance.agentplatform.registry.AgentRegistry;

/**
 * 将当前 doctor 场景下的 subAgent 先接入平台统一注册中心，
 * 为后续从硬编码协调器迁移到 registry 驱动选择器做准备。
 */
@Configuration
public class DoctorAgentPlatformRegistrationConfig {

    private final DoctorAgentPlatformProperties platformProperties;

    public DoctorAgentPlatformRegistrationConfig(DoctorAgentPlatformProperties platformProperties) {
        this.platformProperties = platformProperties;
    }

    @Bean
    CommandLineRunner doctorSubAgentRegistrationRunner(
            AgentRegistry agentRegistry,
            DataCollectionSubAgent dataCollectionSubAgent,
            WeightAnalysisSubAgent weightAnalysisSubAgent,
            CarePlanningSubAgent carePlanningSubAgent,
            RiskTriageSubAgent riskTriageSubAgent) {
        return args -> {
            register(agentRegistry, dataCollectionSubAgent,
                    List.of("patient-data-collection", "follow-up-facts", "history-gathering"));
            register(agentRegistry, weightAnalysisSubAgent,
                    List.of("weight-analysis", "plateau-detection", "adherence-analysis"));
            register(agentRegistry, carePlanningSubAgent,
                    List.of("care-planning", "follow-up-recommendation", "patient-education"));
            register(agentRegistry, riskTriageSubAgent,
                    List.of("risk-triage", "red-flag-review", "human-escalation"));
        };
    }

    private void register(
            AgentRegistry agentRegistry,
            AgentSubAgent subAgent,
            List<String> capabilities) {
        agentRegistry.register(AgentDefinition.builder()
                .agentId(subAgent.agentId())
                .displayName(subAgent.name())
                .agentType(AgentType.SUB_AGENT)
                .capabilities(capabilities)
                .supportedTaskType("PATIENT_FOLLOW_UP_ANALYSIS")
                .supportedTaskType("RISK_STRATIFICATION_AND_ESCALATION")
                .supportedTaskType("NEXT_FOLLOW_UP_ACTION_RECOMMENDATION")
                .allowedTools(platformProperties.resolveAllowedTools(subAgent.agentId(), subAgent.preferredToolNames()))
                .riskLevel("MEDIUM")
                .owner(platformProperties.getPlatformAgentId())
                .version("v1")
                .status(AgentStatus.ENABLED)
                .inputSchema(java.util.Map.of(
                        "goal", "string",
                        "contextSlots", "object",
                        "patientMessage", "string"))
                .outputSchema(java.util.Map.of(
                        "thinking", "string",
                        "action", "object",
                        "finalAnswer", "object"))
                .build());
    }
}
