package com.lx.governance.knowledgebase.dto;

import java.util.List;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CrawlImportResponse {

    private String kbId;

    private String seedUrl;

    private int requestedPages;

    private int crawledPages;

    private int importedDocuments;

    private List<CrawledPageSummary> pages;
}
