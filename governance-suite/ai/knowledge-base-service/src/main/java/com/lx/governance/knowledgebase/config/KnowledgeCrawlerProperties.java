package com.lx.governance.knowledgebase.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/**
 * 知识库抓取配置。
 */
@Data
@ConfigurationProperties(prefix = "knowledge-base.crawler")
public class KnowledgeCrawlerProperties {

    private boolean enabled = true;

    private String userAgent = "Mozilla/5.0 (compatible; GovernanceSuiteKnowledgeBot/1.0; +https://localhost)";

    private int connectTimeoutMillis = 10000;

    private int readTimeoutMillis = 15000;

    private long politenessDelayMillis = 400L;

    private int maxPagesPerJob = 20;

    private int maxContentLength = 200000;

    private boolean respectRobotsTxt = true;

    private int fetchRetryCount = 2;

    private long fetchRetryBackoffMillis = 300L;

    private String defaultCreatedBy = "kb-crawler";

    private String outputPath = "/tmp/kb/crawler";
}
