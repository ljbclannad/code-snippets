package com.lx.governance.vectormemory.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Data;

@Data
public class AiGroupMemoryCandidateEntity {

    private Long id;
    private String customerId;
    private String groupId;
    private String memoryType;
    private String memoryKey;
    private String normalizedKey;
    private String normalizedValue;
    private String polarity;
    private String memoryContent;
    private BigDecimal confidenceScore;
    private BigDecimal worthinessScore;
    private String status;
    private String lifecycleStatus;
    private String decisionReasonCode;
    private Integer hitCount;
    private Integer sourceCount;
    private Integer evidenceCount;
    private String sourceMessage;
    private LocalDateTime lastSeenAt;
    private LocalDateTime lastReinforcedAt;
    private String reviewedBy;
    private LocalDateTime reviewedAt;
    private Long confirmedMemoryId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
