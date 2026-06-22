package com.lx.governance.knowledgebase.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CrawledPageSummary {

    private String url;

    private String title;

    private String docId;

    private String status;

    private int textLength;
}
