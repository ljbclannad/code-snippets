package com.health.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * doctor agent 运行时主循环相关配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "doctor.agent.runtime")
public class DoctorAgentRuntimeProperties {

    private int maxSteps = 15;

    private boolean useMock = false;

    private int protocolRepairAttempts = 1;
}
