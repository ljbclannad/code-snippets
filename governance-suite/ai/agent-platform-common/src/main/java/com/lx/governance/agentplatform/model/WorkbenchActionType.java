package com.lx.governance.agentplatform.model;

/**
 * 工作台可执行动作类型。
 */
public enum WorkbenchActionType {
    /** 批准。 */
    APPROVE,
    /** 拒绝。 */
    REJECT,
    /** 请求补充信息。 */
    REQUEST_INFO,
    /** 重试。 */
    RETRY
}
