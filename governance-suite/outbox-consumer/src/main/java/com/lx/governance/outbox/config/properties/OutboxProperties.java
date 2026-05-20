package com.lx.governance.outbox.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

@Component
@RefreshScope
@ConfigurationProperties(prefix = "outbox")
@Data
public class OutboxProperties {
    private long pollIntervalMs = 1000;
    private int maxRetryCount = 5;
    private long retryBackoffMs = 2000;
}
