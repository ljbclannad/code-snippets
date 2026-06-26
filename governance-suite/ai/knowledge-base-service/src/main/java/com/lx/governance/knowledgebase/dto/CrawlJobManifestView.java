package com.lx.governance.knowledgebase.dto;

import java.util.List;

import lombok.Data;

@Data
public class CrawlJobManifestView {

    private String jobId;

    private String status;

    private String seedUrl;

    private String sitemapUrl;

    private boolean dryRun;

    private String refreshMode;

    private Integer maxPages;

    private String titlePrefix;

    private String source;

    private String businessDomain;

    private String applicableScope;

    private String trustLevel;

    private String createdBy;

    private String jobLabel;

    private List<String> tags;

    private List<String> allowedDomains;

    private List<String> allowedPathPrefixes;

    private List<String> blockedPathPrefixes;

    private List<String> blockedUrlKeywords;

    private Boolean sameHostOnly;

    private Boolean includePathPrefixOnly;

    private Boolean archiveRawHtml;

    private Boolean archiveNormalizedMarkdown;

    private Boolean stripQueryString;

    private int importedDocuments;

    private int skippedDocuments;

    private int refreshedDocuments;

    private String updatedAt;

    private List<CrawledPageSummary> pages;
}
