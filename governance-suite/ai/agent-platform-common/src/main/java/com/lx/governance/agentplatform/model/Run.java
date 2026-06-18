package com.lx.governance.agentplatform.model;

import java.util.List;
import java.util.Map;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

/**
 * 平台统一运行对象。
 */
@Value
@Builder(toBuilder = true)
public class Run {

    String runId;

    String parentRunId;

    String conversationId;

    String sessionId;

    String workflowId;

    String agentId;

    RunType runType;

    RunStatus status;

    Map<String, Object> input;

    Map<String, Object> output;

    @Singular("step")
    List<RunStep> steps;

    @Singular("event")
    List<RunEvent> events;

    Long createdAt;

    Long startedAt;

    Long endedAt;
}
