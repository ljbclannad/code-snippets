package com.lx.governance.knowledgebase.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CrawledPageSummary {

    private String url;

    private String canonicalUrl;

    private String title;

    private String docId;

    private String previousDocId;

    private String status;

    private int textLength;

    private String rawHtmlPath;

    private String markdownPath;

    private String publishedAt;
}
