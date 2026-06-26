package com.lx.governance.knowledgebase.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CrawlJobSummary {

    private String jobId;

    private String status;

    private String seedUrl;

    private boolean dryRun;

    private String updatedAt;

    private int importedDocuments;

    private int skippedDocuments;

    private int refreshedDocuments;
}
