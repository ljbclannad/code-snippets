package com.lx.governance.agentplatform.model;

/**
 * 通用循环终止原因。
 */
public enum LoopTerminationReason {

    FINISHED,
    WAITING_APPROVAL,
    FAILED,
    MAX_STEPS
}
