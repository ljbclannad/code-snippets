package com.lx.governance.vectormemory.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Data;

@Data
public class AiGroupMemoryConflictEntity {

    private Long id;
    private String customerId;
    private String groupId;
    private Long candidateId;
    private String conflictTargetType;
    private Long conflictTargetId;
    private String conflictReason;
    private BigDecimal conflictScore;
    private String resolutionStatus;
    private String resolutionReason;
    private String resolutionNote;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
