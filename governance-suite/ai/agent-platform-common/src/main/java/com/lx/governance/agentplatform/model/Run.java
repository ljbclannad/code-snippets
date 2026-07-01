package com.lx.governance.agentplatform.model;

import java.util.List;
import java.util.Map;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

/**
 * 平台统一运行对象。
 * <p>
 * 这个对象是平台侧一次任务执行的主聚合根，串起输入、步骤、事件和最终产出。
 */
@Value
@Builder(toBuilder = true)
public class Run {

    /** 全局唯一 run 标识。 */
    String runId;

    /** 父 run 标识，通常用于恢复、回放或子任务挂接。 */
    String parentRunId;

    /** 关联的对话标识。 */
    String conversationId;

    /** 关联的会话标识。 */
    String sessionId;

    /** 所属工作流标识。 */
    String workflowId;

    /** 执行该 run 的 agent 标识。 */
    String agentId;

    /** run 所属类型，区分普通执行、审批恢复等场景。 */
    RunType runType;

    /** 当前状态。 */
    RunStatus status;

    /** 执行输入。 */
    Map<String, Object> input;

    /** 最终输出。 */
    Map<String, Object> output;

    /** 运行步骤轨迹。 */
    @Singular("step")
    List<RunStep> steps;

    /** 运行事件轨迹。 */
    @Singular("event")
    List<RunEvent> events;

    /** 记录创建时间。 */
    Long createdAt;

    /** 记录开始时间。 */
    Long startedAt;

    /** 记录结束时间。 */
    Long endedAt;
}
