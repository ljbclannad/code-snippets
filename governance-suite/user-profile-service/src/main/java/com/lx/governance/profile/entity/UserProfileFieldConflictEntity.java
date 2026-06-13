package com.lx.governance.profile.entity;

import java.time.LocalDateTime;

import lombok.Data;

@Data
public class UserProfileFieldConflictEntity {

    private Long id;
    private String customerId;
    private Long candidateFieldId;
    private String conflictTargetType;
    private String conflictTargetValue;
    private String conflictReason;
    private String resolutionStatus;
    private String resolutionReason;
    private String resolutionNote;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
