package com.lx.governance.knowledgebase.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LocalBatchImportItemResult {

    private String sourcePath;

    private String title;

    private String fileType;

    private String status;

    private String docId;

    private String tags;

    private String businessDomain;

    private String applicableScope;

    private String trustLevel;

    private String errorMessage;
}
