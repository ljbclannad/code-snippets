package com.lx.governance.aichat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.lx.governance.agentplatform.runtime.DefaultPlatformContextProjector;
import com.lx.governance.agentplatform.runtime.PlatformContextProjector;

/**
 * 注册 ai-chat-service 需要消费的 agent-platform-common 运行时组件。
 */
@Configuration
public class AgentPlatformContextConfig {

    @Bean
    public PlatformContextProjector platformContextProjector() {
        return new DefaultPlatformContextProjector();
    }
}
