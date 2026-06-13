package com.health.agent.dto.patient;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentTaskConstraints {
    private Boolean patientVisibleResponseOnly;
    private Boolean requireHumanReviewForHighRisk;
    private Boolean requireEvidence;
    private Boolean requireAuditTrail;
    private Boolean fallbackToDirectChatOnFailure;
    private Integer timeoutMillis;
    private String idempotencyKey;
}
