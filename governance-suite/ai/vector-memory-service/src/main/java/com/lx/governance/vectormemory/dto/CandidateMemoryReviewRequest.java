package com.lx.governance.vectormemory.dto;

import lombok.Data;

@Data
public class CandidateMemoryReviewRequest {

    private String reviewer = "SYSTEM";
    private String resolutionReason;
    private String remarks;
}
