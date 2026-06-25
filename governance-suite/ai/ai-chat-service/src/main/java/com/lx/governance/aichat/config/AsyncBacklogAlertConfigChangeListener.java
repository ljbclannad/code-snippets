package com.lx.governance.aichat.config;

import org.springframework.cloud.context.scope.refresh.RefreshScopeRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class AsyncBacklogAlertConfigChangeListener {

    private final AsyncBacklogAlertProperties properties;

    @EventListener
    public void onRefresh(RefreshScopeRefreshedEvent event) {
        log.info(
                "异步 backlog 告警配置已刷新: enabled={}, checkIntervalMs={}, repeatAlertIntervalMs={}, memoryExtractionThreshold={}, chatHistoryThreshold={}, userProfilePendingThreshold={}, vectorMemoryPendingThreshold={}",
                properties.isEnabled(),
                properties.getCheckIntervalMs(),
                properties.getRepeatAlertIntervalMs(),
                properties.getQueueThresholds().getMemoryExtractionReadyThreshold(),
                properties.getQueueThresholds().getChatHistorySyncReadyThreshold(),
                properties.getRpcThresholds().getUserProfilePendingThreshold(),
                properties.getRpcThresholds().getVectorMemoryPendingThreshold());
    }
}
