package com.lx.governance.agentplatform.model;

import java.util.Map;

import lombok.Builder;
import lombok.Value;

/**
 * 平台运行中的单个步骤快照。
 * <p>
 * 代表一次节点、agent 或工具执行的局部状态。
 */
@Value
@Builder(toBuilder = true)
public class RunStep {

    /** 步骤序号。 */
    Integer stepIndex;

    /** 步骤标识。 */
    String stepId;

    /** 节点类型。 */
    String nodeType;

    /** 节点名称。 */
    String nodeName;

    /** 触发本步骤的 agent 标识。 */
    String agentId;

    /** 当前步骤状态。 */
    RunStatus status;

    /** 步骤输入。 */
    Map<String, Object> input;

    /** 步骤输出。 */
    Map<String, Object> output;

    /** 步骤异常信息。 */
    String errorMessage;

    /** 步骤开始时间。 */
    Long startedAt;

    /** 步骤结束时间。 */
    Long endedAt;
}
