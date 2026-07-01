package com.lx.governance.agentplatform.model;

import java.util.LinkedHashMap;
import java.util.Map;

import lombok.Builder;
import lombok.Value;

/**
 * 平台入口路由所需上下文。
 */
@Value
@Builder(toBuilder = true)
public class PlatformRoutingContext {

    String customerId;

    String userId;

    String groupId;

    String tenantId;

    String conversationId;

    String message;

    @Builder.Default
    Map<String, Object> sessionSignals = new LinkedHashMap<>();
}
