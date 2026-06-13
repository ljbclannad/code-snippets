package com.lx.governance.order.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

@Component
@RefreshScope
@ConfigurationProperties(prefix = "app.thread-pool.io")
@Data
public class ThreadPoolProperties {
    private int coreSize = 32;
    private int maxSize = 64;
    private int queueCapacity = 2000;
    private int keepAliveSeconds = 60;
}
