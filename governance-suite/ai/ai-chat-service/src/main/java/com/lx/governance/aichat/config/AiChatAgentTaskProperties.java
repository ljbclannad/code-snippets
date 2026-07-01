package com.lx.governance.aichat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * ai-chat 到 agent 平台升级请求的默认约束配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai-chat.agent.task")
public class AiChatAgentTaskProperties {

    private boolean patientVisibleResponseOnly = true;

    private boolean requireHumanReviewForHighRisk = true;

    private boolean requireEvidence = true;

    private boolean requireAuditTrail = true;

    private boolean fallbackToDirectChatOnFailure = true;

    private int timeoutMillis = 8000;

    private PlatformContextTransport platformContext = new PlatformContextTransport();

    @Data
    public static class PlatformContextTransport {
        private int maxFields = 8;
        private int maxFieldChars = 600;
        private int maxTotalChars = 2400;
        private int maxEvidences = 12;
        private int maxToolOutputs = 6;
    }
}
