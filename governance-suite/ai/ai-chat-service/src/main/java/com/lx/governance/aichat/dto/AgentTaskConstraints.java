package com.lx.governance.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ai-chat-service 调用 doctor-assistant-agent 时附带的约束信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentTaskConstraints {

    /**
     * 患者侧只能消费安全改写后的回复。
     */
    private Boolean patientVisibleResponseOnly;

    /**
     * 高风险建议必须要求人工复核。
     */
    private Boolean requireHumanReviewForHighRisk;

    /**
     * 结构化结果必须带证据链。
     */
    private Boolean requireEvidence;

    /**
     * 是否要求审计轨迹。
     */
    private Boolean requireAuditTrail;

    /**
     * 失败时是否允许回退到普通对话。
     */
    private Boolean fallbackToDirectChatOnFailure;

    /**
     * 单次调用超时时间，单位毫秒。
     */
    private Integer timeoutMillis;

    /**
     * 请求幂等键，便于重试去重。
     */
    private String idempotencyKey;
}
