package com.lx.governance.agentplatform.model;

import java.util.Map;

import lombok.Builder;
import lombok.Value;

/**
 * 平台运行中的单个步骤快照。
 */
@Value
@Builder(toBuilder = true)
public class RunStep {

    Integer stepIndex;

    String stepId;

    String nodeType;

    String nodeName;

    String agentId;

    RunStatus status;

    Map<String, Object> input;

    Map<String, Object> output;

    String errorMessage;

    Long startedAt;

    Long endedAt;
}
