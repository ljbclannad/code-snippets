package com.health.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.lx.governance.agentplatform.registry.AgentRegistry;
import com.lx.governance.agentplatform.registry.InMemoryAgentRegistry;

/**
 * 注册 doctor-assistant-agent 需要消费的平台级注册中心实现。
 */
@Configuration
public class DoctorAgentPlatformRegistryConfig {

    @Bean
    public AgentRegistry agentRegistry() {
        return new InMemoryAgentRegistry();
    }
}
