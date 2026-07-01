package com.lx.governance.agentplatform.model;

/**
 * 通用循环终止原因。
 */
public enum LoopTerminationReason {

    /** 正常结束。 */
    FINISHED,
    /** 等待审批。 */
    WAITING_APPROVAL,
    /** 执行失败。 */
    FAILED,
    /** 达到最大步数。 */
    MAX_STEPS
}
