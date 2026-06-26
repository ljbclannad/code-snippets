package com.lx.governance.knowledgebase.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LocalBatchImportRequest {

    @NotBlank(message = "manifestPath 不能为空")
    private String manifestPath;

    private Boolean dryRun = false;

    private String createdBy;

    private String defaultBusinessDomain;

    private String defaultApplicableScope;

    private String defaultTrustLevel;
}
