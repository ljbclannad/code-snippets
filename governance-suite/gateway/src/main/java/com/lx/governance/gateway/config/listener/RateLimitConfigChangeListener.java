package com.lx.governance.gateway.config.listener;

import com.lx.governance.gateway.config.properties.RateLimitProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.context.scope.refresh.RefreshScopeRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class RateLimitConfigChangeListener {

    private final RateLimitProperties rateLimitProperties;

    public RateLimitConfigChangeListener(RateLimitProperties rateLimitProperties) {
        this.rateLimitProperties = rateLimitProperties;
    }

    @EventListener
    public void onRefresh(RefreshScopeRefreshedEvent event) {
        log.info("限流配置已更新: replenishRate={}, burstCapacity={}",
            rateLimitProperties.getReplenishRate(),
            rateLimitProperties.getBurstCapacity());

        notifyMonitoring("rate-limit-config-changed", rateLimitProperties);
    }

    private void notifyMonitoring(String eventName, Object data) {
        log.info("监控通知: event={}, data={}", eventName, data);
    }
}
