package com.health.agent.model;

/**
 * 任务状态
 */
public enum TaskStatus {
    /**
     * 待审批
     */
    PENDING,

    /**
     * 已批准
     */
    APPROVED,

    /**
     * 已拒绝
     */
    REJECTED,

    /**
     * 已过期
     */
    EXPIRED
}
