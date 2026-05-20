package com.lx.governance.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.context.scope.refresh.RefreshScopeRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 通用配置变更监听器
 * 监听 Nacos 配置刷新事件，记录审计日志
 */
@Component
@Slf4j
public class ConfigChangeListener {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @EventListener
    public void onRefresh(RefreshScopeRefreshedEvent event) {
        String timestamp = LocalDateTime.now().format(FORMATTER);
        String scope = event.getName();

        log.info("配置刷新事件触发: scope={}", scope);
        log.info("配置变更审计: time={}, scope={}", timestamp, scope);
    }
}
