package com.lx.governance.profile.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Data;

@Data
public class UserProfileCandidateFieldEntity {

    private Long id;
    private String customerId;
    private String fieldPath;
    private String normalizedValue;
    private String rawValue;
    private BigDecimal confidenceScore;
    private String status;
    private Integer hitCount;
    private String source;
    private String reviewedBy;
    private LocalDateTime reviewedAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
