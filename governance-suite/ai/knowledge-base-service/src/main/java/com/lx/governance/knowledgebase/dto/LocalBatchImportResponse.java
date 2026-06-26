package com.lx.governance.knowledgebase.dto;

import java.util.List;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LocalBatchImportResponse {

    private String kbId;

    private String manifestPath;

    private boolean dryRun;

    private int totalFiles;

    private int importedDocuments;

    private int skippedDocuments;

    private List<LocalBatchImportItemResult> items;
}
