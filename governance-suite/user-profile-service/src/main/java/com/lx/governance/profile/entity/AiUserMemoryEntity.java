package com.lx.governance.profile.entity;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class AiUserMemoryEntity {
    private Long id;
    private String customerId;
    private String memoryType;
    private String memoryKey;
    private String memoryContent;
    private String embeddingId;
    private BigDecimal confidenceScore;
    private String source;
    private Integer verified;
    private String verifiedBy;
    private LocalDateTime verifiedAt;
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
