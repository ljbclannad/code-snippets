package com.lx.governance.aichat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.lx.governance.agentplatform.runtime.PlatformMetricsService;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * ai-chat-service 平台指标配置。
 */
@Configuration
public class AiChatPlatformMetricsConfig {

    @Bean
    public PlatformMetricsService platformMetricsService(MeterRegistry meterRegistry) {
        return new PlatformMetricsService(meterRegistry);
    }
}
