package com.lx.governance.order.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

@Component
@RefreshScope
@ConfigurationProperties(prefix = "app.business")
@Data
public class BusinessProperties {
    private boolean seckillEnabled = true;
    private int orderTimeoutSeconds = 1800;
}
