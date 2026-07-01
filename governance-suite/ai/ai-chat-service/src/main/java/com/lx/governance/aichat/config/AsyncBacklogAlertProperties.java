package com.lx.governance.aichat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import lombok.Data;

@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "ai-chat.async-backlog-alert")
public class AsyncBacklogAlertProperties {

    private boolean enabled = true;
    private long checkIntervalMs = 30000L;
    private long repeatAlertIntervalMs = 300000L;
    private QueueThresholds queueThresholds = new QueueThresholds();
    private RpcThresholds rpcThresholds = new RpcThresholds();

    @Data
    public static class QueueThresholds {
        private int memoryExtractionReadyThreshold = 100;
        private int chatHistorySyncReadyThreshold = 100;
    }

    @Data
    public static class RpcThresholds {
        private int userProfilePendingThreshold = 50;
        private int vectorMemoryPendingThreshold = 50;
    }
}
