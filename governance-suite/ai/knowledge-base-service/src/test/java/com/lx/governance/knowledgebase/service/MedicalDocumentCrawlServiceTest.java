package com.lx.governance.knowledgebase.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.knowledgebase.config.KnowledgeCrawlerProperties;
import com.lx.governance.knowledgebase.dto.CrawlImportRequest;
import com.lx.governance.knowledgebase.dto.CrawlImportResponse;
import com.lx.governance.knowledgebase.entity.KnowledgeDocument;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MedicalDocumentCrawlServiceTest {

    @Test
    void extractPagePrefersStructuredMedicalContent() throws Exception {
        KnowledgeCrawlerProperties properties = new KnowledgeCrawlerProperties();
        DocumentProcessingService processingService = mock(DocumentProcessingService.class);
        DocumentService documentService = mock(DocumentService.class);
        MedicalDocumentCrawlService service = new MedicalDocumentCrawlService(
                properties, processingService, documentService, new ObjectMapper(), new SimpleMeterRegistry());

        String html = """
                <html>
                  <head>
                    <title>高血压患者家庭血压监测</title>
                    <meta name="description" content="用于指导患者在家中进行血压监测。">
                  </head>
                  <body>
                    <header>导航栏</header>
                    <main>
                      <article>
                        <h1>高血压患者家庭血压监测</h1>
                        <p>建议每天固定时间测量并记录血压。</p>
                        <h2>注意事项</h2>
                        <ul>
                          <li>测量前静坐 5 分钟</li>
                          <li>避免咖啡和剧烈运动后立即测量</li>
                        </ul>
                      </article>
                    </main>
                  </body>
                </html>
                """;

        var extracted = service.extractPage(Jsoup.parse(html, "https://medical.example.com/guide/bp"),
                new URI("https://medical.example.com/guide/bp"));

        assertTrue(extracted.title().contains("高血压患者家庭血压监测"));
        assertTrue(extracted.summary().contains("血压监测"));
        assertTrue(extracted.content().contains("# 高血压患者家庭血压监测"));
        assertTrue(extracted.content().contains("## 注意事项"));
        assertTrue(extracted.content().contains("- 测量前静坐 5 分钟"));
    }

    @Test
    void crawlAndImportSupportsSitemapDryRunAndCanonicalDedup() throws Exception {
        KnowledgeCrawlerProperties properties = new KnowledgeCrawlerProperties();
        properties.setOutputPath(Files.createTempDirectory("kb-crawler-test").toString());
        properties.setPolitenessDelayMillis(0L);
        DocumentProcessingService processingService = mock(DocumentProcessingService.class);
        DocumentService documentService = mock(DocumentService.class);

        TestableMedicalDocumentCrawlService service = new TestableMedicalDocumentCrawlService(
                properties, processingService, documentService, new ObjectMapper(), new SimpleMeterRegistry());
        service.addText("https://medical.example.com/sitemap.xml", """
                <urlset>
                  <url><loc>https://medical.example.com/guides/a</loc></url>
                  <url><loc>https://medical.example.com/guides/b</loc></url>
                </urlset>
                """);
        service.addHtml("https://medical.example.com/guides/a", """
                <html>
                  <head>
                    <title>指南 A</title>
                    <link rel="canonical" href="https://medical.example.com/guides/final-a" />
                    <meta property="article:published_time" content="2026-06-01" />
                  </head>
                  <body><article><h1>指南 A</h1><p>内容 A</p></article></body>
                </html>
                """);
        service.addHtml("https://medical.example.com/guides/b", """
                <html>
                  <head><title>指南 B</title></head>
                  <body><article><h1>指南 B</h1><p>内容 B</p></article></body>
                </html>
                """);

        CrawlImportRequest request = new CrawlImportRequest();
        request.setSeedUrl("https://medical.example.com/guides/");
        request.setSitemapUrl("https://medical.example.com/sitemap.xml");
        request.setMaxPages(5);
        request.setDryRun(true);
        request.setJobLabel("medical-guides");

        CrawlImportResponse response = service.crawlAndImport("kb-001", request);

        assertEquals("kb-001", response.getKbId());
        assertTrue(response.isDryRun());
        assertEquals(0, response.getImportedDocuments());
        assertEquals(2, response.getSkippedDocuments());
        assertEquals(3, response.getCrawledPages());
        assertEquals(2, response.getPages().size());
        assertNotNull(response.getOutputDir());
        assertTrue(Files.exists(Path.of(response.getOutputDir())));
        assertTrue(Files.exists(Path.of(response.getOutputDir()).resolve("crawl-job.json")));
        assertTrue(Files.readString(Path.of(response.getOutputDir()).resolve("crawl-job.json")).contains("\"status\" : \"COMPLETED\""));
        assertEquals("DRY_RUN", response.getPages().get(0).getStatus());
        assertEquals("https://medical.example.com/guides/final-a", response.getPages().get(0).getCanonicalUrl());
        assertEquals("2026-06-01", response.getPages().get(0).getPublishedAt());
        assertNotNull(response.getPages().get(0).getRawHtmlPath());
        assertNotNull(response.getPages().get(0).getMarkdownPath());
    }

    @Test
    void crawlAndImportAppliesPathBlacklistAndMarksPartialSuccess() throws Exception {
        KnowledgeCrawlerProperties properties = new KnowledgeCrawlerProperties();
        properties.setOutputPath(Files.createTempDirectory("kb-crawler-test-partial").toString());
        properties.setPolitenessDelayMillis(0L);
        DocumentProcessingService processingService = mock(DocumentProcessingService.class);
        DocumentService documentService = mock(DocumentService.class);
        when(documentService.findActiveByContentHash(
                org.mockito.ArgumentMatchers.eq("kb-002"),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(null);
        when(documentService.findLatestBySourceAndFileName(
                org.mockito.ArgumentMatchers.eq("kb-002"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(null);

        TestableMedicalDocumentCrawlService service = new TestableMedicalDocumentCrawlService(
                properties, processingService, documentService, new ObjectMapper(), new SimpleMeterRegistry());
        service.addText("https://medical.example.com/sitemap.xml", """
                <urlset>
                  <url><loc>https://medical.example.com/guides/good</loc></url>
                  <url><loc>https://medical.example.com/private/blocked</loc></url>
                  <url><loc>https://medical.example.com/guides/missing</loc></url>
                </urlset>
                """);
        service.addHtml("https://medical.example.com/guides/good", """
                <html><head><title>指南 Good</title></head><body><article><h1>指南 Good</h1><p>可抓取内容</p></article></body></html>
                """);

        CrawlImportRequest request = new CrawlImportRequest();
        request.setSeedUrl("https://medical.example.com/");
        request.setSitemapUrl("https://medical.example.com/sitemap.xml");
        request.setMaxPages(5);
        request.setDryRun(true);
        request.setBlockedPathPrefixes(java.util.List.of("/private/"));
        request.setAllowedPathPrefixes(java.util.List.of("/guides/", "/private/"));

        CrawlImportResponse response = service.crawlAndImport("kb-002", request);

        String manifest = Files.readString(Path.of(response.getOutputDir()).resolve("crawl-job.json"));
        assertTrue(manifest.contains("\"status\" : \"PARTIAL_SUCCESS\""));
        assertEquals(2, response.getPages().size());
        assertTrue(response.getPages().stream().anyMatch(page -> "DRY_RUN".equals(page.getStatus())));
        assertTrue(response.getPages().stream().anyMatch(page -> "FAILED".equals(page.getStatus())));
        assertFalse(response.getPages().stream().anyMatch(page -> page.getUrl() != null && page.getUrl().contains("/private/")));
    }

    @Test
    void crawlAndImportRespectsRobotsTxtAndCanReadManifest() throws Exception {
        KnowledgeCrawlerProperties properties = new KnowledgeCrawlerProperties();
        properties.setOutputPath(Files.createTempDirectory("kb-crawler-test-robots").toString());
        properties.setPolitenessDelayMillis(0L);
        properties.setRespectRobotsTxt(true);
        DocumentProcessingService processingService = mock(DocumentProcessingService.class);
        DocumentService documentService = mock(DocumentService.class);

        TestableMedicalDocumentCrawlService service = new TestableMedicalDocumentCrawlService(
                properties, processingService, documentService, new ObjectMapper(), new SimpleMeterRegistry());
        service.addText("https://medical.example.com/robots.txt", """
                User-agent: *
                Disallow: /blocked
                """);
        service.addText("https://medical.example.com/sitemap.xml", """
                <urlset>
                  <url><loc>https://medical.example.com/blocked/a</loc></url>
                  <url><loc>https://medical.example.com/guides/ok</loc></url>
                </urlset>
                """);
        service.addHtml("https://medical.example.com/guides/ok", """
                <html><head><title>OK</title></head><body><article><h1>OK</h1><p>正文</p></article></body></html>
                """);

        CrawlImportRequest request = new CrawlImportRequest();
        request.setSeedUrl("https://medical.example.com/");
        request.setSitemapUrl("https://medical.example.com/sitemap.xml");
        request.setDryRun(true);
        request.setMaxPages(5);

        CrawlImportResponse response = service.crawlAndImport("kb-003", request);

        assertTrue(response.getPages().stream().anyMatch(page -> "SKIPPED_ROBOTS_TXT".equals(page.getStatus())));
        assertTrue(service.listCrawlJobs(10).stream().anyMatch(job -> response.getCrawlJobId().equals(job.getJobId())));
        var manifest = service.getCrawlJob(response.getCrawlJobId());
        assertEquals(response.getCrawlJobId(), manifest.getJobId());
        assertTrue(manifest.getPages().stream().anyMatch(page -> "SKIPPED_ROBOTS_TXT".equals(page.getStatus())));
    }

    @Test
    void retryCrawlJobReusesManifestRequestSnapshot() throws Exception {
        KnowledgeCrawlerProperties properties = new KnowledgeCrawlerProperties();
        properties.setOutputPath(Files.createTempDirectory("kb-crawler-test-retry").toString());
        properties.setPolitenessDelayMillis(0L);
        DocumentProcessingService processingService = mock(DocumentProcessingService.class);
        DocumentService documentService = mock(DocumentService.class);
        TestableMedicalDocumentCrawlService service = new TestableMedicalDocumentCrawlService(
                properties, processingService, documentService, new ObjectMapper(), new SimpleMeterRegistry());

        service.addText("https://medical.example.com/sitemap.xml", """
                <urlset><url><loc>https://medical.example.com/guides/one</loc></url></urlset>
                """);
        service.addHtml("https://medical.example.com/guides/one", """
                <html><head><title>One</title></head><body><article><h1>One</h1><p>正文</p></article></body></html>
                """);

        CrawlImportRequest request = new CrawlImportRequest();
        request.setSeedUrl("https://medical.example.com/");
        request.setSitemapUrl("https://medical.example.com/sitemap.xml");
        request.setDryRun(true);
        request.setBlockedUrlKeywords(java.util.List.of("forbidden"));

        CrawlImportResponse first = service.crawlAndImport("kb-004", request);
        CrawlImportResponse retried = service.retryCrawlJob("kb-004", first.getCrawlJobId());

        assertNotNull(retried.getCrawlJobId());
        assertTrue(retried.getCrawlJobId().contains("-retry"));
        assertTrue(service.getCrawlJob(retried.getCrawlJobId()).getBlockedUrlKeywords().contains("forbidden"));
    }

    private static final class TestableMedicalDocumentCrawlService extends MedicalDocumentCrawlService {

        private final Map<String, String> htmlByUrl = new HashMap<>();
        private final Map<String, String> textByUrl = new HashMap<>();

        private TestableMedicalDocumentCrawlService(
                KnowledgeCrawlerProperties crawlerProperties,
                DocumentProcessingService documentProcessingService,
                DocumentService documentService,
                ObjectMapper objectMapper,
                SimpleMeterRegistry meterRegistry) {
            super(crawlerProperties, documentProcessingService, documentService, objectMapper, meterRegistry);
        }

        void addHtml(String url, String html) {
            htmlByUrl.put(url, html);
        }

        void addText(String url, String text) {
            textByUrl.put(url, text);
        }

        @Override
        Document fetch(String url) {
            String html = htmlByUrl.get(url);
            if (html == null) {
                throw new IllegalArgumentException("missing html for " + url);
            }
            return Jsoup.parse(html, url);
        }

        @Override
        String fetchText(String url) {
            String text = textByUrl.get(url);
            if (text == null) {
                throw new IllegalArgumentException("missing text for " + url);
            }
            return text;
        }
    }
}
