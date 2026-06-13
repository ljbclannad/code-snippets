package com.lx.governance.profile.entity;

import java.time.LocalDateTime;

import lombok.Data;

@Data
public class UserProfileFieldReviewAuditEntity {

    private Long id;
    private Long candidateFieldId;
    private String customerId;
    private String fieldPath;
    private String actionType;
    private String resultStatus;
    private String reviewer;
    private String resolutionReason;
    private String remarks;
    private String relatedFormalValue;
    private LocalDateTime createTime;
}
