package com.lx.governance.knowledgebase.dto;

import lombok.Data;

@Data
public class DocumentGovernanceUpdateRequest {
    private String reviewStatus;
    private String reviewer;
    private String reviewComment;
    private String version;
    private String publishDate;
    private String expiresAt;
    private String replacementVersion;
    private String invalidReason;
}
