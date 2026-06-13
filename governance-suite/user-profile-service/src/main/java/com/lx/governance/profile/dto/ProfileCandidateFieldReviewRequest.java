package com.lx.governance.profile.dto;

import lombok.Data;

@Data
public class ProfileCandidateFieldReviewRequest {

    private String reviewer = "SYSTEM";
    private String resolutionReason;
    private String remarks;
}
