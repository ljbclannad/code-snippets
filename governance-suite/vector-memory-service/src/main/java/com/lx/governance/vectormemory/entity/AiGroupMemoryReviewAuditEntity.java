package com.lx.governance.vectormemory.entity;

import java.time.LocalDateTime;

import lombok.Data;

@Data
public class AiGroupMemoryReviewAuditEntity {

    private Long id;
    private Long candidateId;
    private String customerId;
    private String groupId;
    private String actionType;
    private String resultStatus;
    private String reviewer;
    private String resolutionReason;
    private String remarks;
    private Long relatedFormalId;
    private LocalDateTime createTime;
}
