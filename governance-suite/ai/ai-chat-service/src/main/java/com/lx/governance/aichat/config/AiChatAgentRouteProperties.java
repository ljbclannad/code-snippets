package com.lx.governance.aichat.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * ai-chat 到 agent 平台的路由与灰度配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai-chat.agent")
public class AiChatAgentRouteProperties {

    private List<String> enabledTaskTypes = new ArrayList<>();

    private List<String> enabledGroupIds = new ArrayList<>();

    private List<String> enabledUserIds = new ArrayList<>();

    private List<String> enabledTenantIds = new ArrayList<>();

    private List<String> enabledAgentVersions = new ArrayList<>();

    private boolean circuitBreakerOpen = false;

    private boolean autoFallbackToDirectChat = true;

    private long pendingReviewShortCircuitWindowMs = 600_000L;
}
