package com.lx.governance.agentplatform.model;

import java.util.List;
import java.util.Map;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

/**
 * 平台 run 回放视图。
 * <p>
 * 将主 run、步骤、事件、审批和产物拼成一个可审计的读模型。
 */
@Value
@Builder(toBuilder = true)
public class RunReplayView {

    /** run 标识。 */
    String runId;

    /** 主 run。 */
    Run run;

    /** 对话映射。 */
    ConversationRunLink conversationLink;

    /** 路由结果。 */
    ReplayRouteDecision routeDecision;

    /** 步骤轨迹。 */
    @Singular("step")
    List<RunStep> steps;

    /** 事件轨迹。 */
    @Singular("event")
    List<RunEvent> events;

    /** 审批任务。 */
    @Singular("approvalTask")
    List<ApprovalTask> approvalTasks;

    /** 运行产物。 */
    @Singular("artifact")
    List<ArtifactRecord> artifacts;

    /** 回放视角下的工具调用。 */
    @Singular("toolCall")
    List<ReplayToolCall> toolCalls;

    /** Agent 运行策略快照。 */
    Map<String, Object> strategySnapshot;

    /** Agent 运行结果。 */
    Map<String, Object> outcome;

    /** Agent 结果归因。 */
    Map<String, Object> attribution;

    /** 遗留 run 表名。 */
    String legacyRunTable;

    /** 遗留 run id。 */
    String legacyRunId;

    /** 遗留审批表名。 */
    String legacyApprovalTable;

    /** 遗留审批 id。 */
    @Singular("legacyApprovalId")
    List<String> legacyApprovalIds;
}
