package com.health.agent.dto.patient;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ai-chat-service 下传给 doctor-assistant-agent 的执行约束。
 *
 * <p>它定义本次 Agent 调用在患者可见性、证据要求、审计要求和失败回退上的边界。</p>
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
     * 高风险结论是否必须要求人工复核。
     */
    private Boolean requireHumanReviewForHighRisk;
    /**
     * 结构化结果是否必须带证据链。
     */
    private Boolean requireEvidence;
    /**
     * 是否要求保留完整审计轨迹。
     */
    private Boolean requireAuditTrail;
    /**
     * 若 Agent 失败，是否允许上游回退普通对话。
     */
    private Boolean fallbackToDirectChatOnFailure;
    /**
     * 单次调用超时时间，单位毫秒。
     */
    private Integer timeoutMillis;
    /**
     * 幂等键，便于重试去重。
     */
    private String idempotencyKey;
}
