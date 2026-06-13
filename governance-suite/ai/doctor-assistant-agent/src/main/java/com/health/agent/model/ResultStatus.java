package com.health.agent.model;

/**
 * 执行结果状态
 */
public enum ResultStatus {
    /**
     * 成功完成
     */
    SUCCESS,

    /**
     * 等待审批
     */
    PENDING_APPROVAL,

    /**
     * 执行错误
     */
    ERROR,

    /**
     * 达到最大步数
     */
    MAX_STEPS
}
