package com.lx.governance.aichat.dto;

/**
 * doctor-assistant-agent 返回给 ai-chat-service 的任务状态。
 */
public enum DoctorAssistantTaskStatus {
    SUCCESS,
    SKIPPED,
    PENDING_APPROVAL,
    ERROR,
    MAX_STEPS
}
