package com.lx.governance.agentplatform.model;

import java.util.Map;

import lombok.Builder;
import lombok.Value;

/**
 * 平台运行时事件。
 */
@Value
@Builder(toBuilder = true)
public class RunEvent {

    String eventId;

    String runId;

    String stepId;

    RunEventType eventType;

    String message;

    Map<String, Object> payload;

    Long createdAt;
}
