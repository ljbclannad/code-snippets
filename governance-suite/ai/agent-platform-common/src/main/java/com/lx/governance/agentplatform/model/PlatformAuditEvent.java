package com.lx.governance.agentplatform.model;

import java.util.Map;

import lombok.Builder;
import lombok.Value;

/**
 * 平台统一审计事件。
 */
@Value
@Builder(toBuilder = true)
public class PlatformAuditEvent {

    String auditEventId;

    PlatformAuditEventType eventType;

    String runId;

    String conversationId;

    String agentId;

    String modelVersion;

    String promptVersion;

    String toolVersion;

    PlatformAuditOutputScope outputScope;

    Map<String, Object> payload;

    Long createdAt;
}
