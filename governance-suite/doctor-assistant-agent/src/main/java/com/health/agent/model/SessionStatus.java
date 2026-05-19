package com.health.agent.model;

/**
 * 会话状态
 */
public enum SessionStatus {
    /**
     * 运行中
     */
    RUNNING,

    /**
     * 暂停（等待审批）
     */
    PAUSED,

    /**
     * 已完成
     */
    COMPLETED,

    /**
     * 失败
     */
    FAILED
}
