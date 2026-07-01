package com.lx.governance.agentplatform.model;

import lombok.Builder;
import lombok.Value;

/**
 * 回放视角下的工具调用摘要。
 * <p>
 * 把审批任务也投影成“工具调用”形态，方便统一展示。
 */
@Value
@Builder(toBuilder = true)
public class ReplayToolCall {

    /** 工具调用标识。 */
    String toolCallId;

    /** run 标识。 */
    String runId;

    /** 关联步骤。 */
    String stepId;

    /** 工具名。 */
    String toolName;

    /** 工具描述。 */
    String toolDescription;

    /** 状态。 */
    String status;

    /** 原因。 */
    String reason;

    /** 结果摘要。 */
    String resultSummary;

    /** 创建时间。 */
    Long createdAt;

    /** 过期时间。 */
    Long expireAt;
}
