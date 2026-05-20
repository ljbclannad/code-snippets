package com.lx.governance.outbox.config.listener;

import com.lx.governance.outbox.config.properties.OutboxProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.context.scope.refresh.RefreshScopeRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class OutboxConfigChangeListener {

    private final OutboxProperties outboxProperties;

    public OutboxConfigChangeListener(OutboxProperties outboxProperties) {
        this.outboxProperties = outboxProperties;
    }

    @EventListener
    public void onRefresh(RefreshScopeRefreshedEvent event) {
        long newInterval = outboxProperties.getPollIntervalMs();
        int newMaxRetry = outboxProperties.getMaxRetryCount();

        log.info("Outbox 配置变更: pollIntervalMs={}, maxRetryCount={}",
            newInterval, newMaxRetry);

        log.info("注意: 轮询间隔变更需要重启服务才能生效（定时任务已启动）");
    }
}
