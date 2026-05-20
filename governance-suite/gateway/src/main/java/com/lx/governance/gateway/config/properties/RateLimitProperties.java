package com.lx.governance.gateway.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

@Component
@RefreshScope
@ConfigurationProperties(prefix = "gateway.rate-limit")
@Data
public class RateLimitProperties {
    private int replenishRate = 5;
    private int burstCapacity = 10;
}
