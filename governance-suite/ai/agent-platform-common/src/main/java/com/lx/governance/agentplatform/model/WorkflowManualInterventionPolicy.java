package com.lx.governance.agentplatform.model;

import lombok.Builder;
import lombok.Value;

/**
 * 人工介入策略。
 * <p>
 * 描述人工接管、审批或复核时要进入的队列和后续恢复路径。
 */
@Value
@Builder(toBuilder = true)
public class WorkflowManualInterventionPolicy {

    /** 人工介入类型。 */
    WorkflowManualInterventionType interventionType;

    /** 队列键。 */
    String queueKey;

    /** 指派对象类型。 */
    String assigneeType;

    /** 超时时间。 */
    Long timeoutMs;

    /** 恢复节点。 */
    String resumeNodeId;

    /** 拒绝节点。 */
    String rejectNodeId;
}
