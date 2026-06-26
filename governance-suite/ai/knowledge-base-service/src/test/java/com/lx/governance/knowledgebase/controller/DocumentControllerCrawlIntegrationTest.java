package com.lx.governance.knowledgebase.controller;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.knowledgebase.config.KnowledgeCrawlerProperties;
import com.lx.governance.knowledgebase.dto.CrawlImportRequest;
import com.lx.governance.knowledgebase.dto.UploadDocumentRequest;
import com.lx.governance.knowledgebase.entity.KnowledgeDocument;
import com.lx.governance.knowledgebase.service.DocumentChunkService;
import com.lx.governance.knowledgebase.service.DocumentParseResultService;
import com.lx.governance.knowledgebase.service.DocumentProcessingService;
import com.lx.governance.knowledgebase.service.DocumentService;
import com.lx.governance.knowledgebase.service.LocalDocumentBatchImportService;
import com.lx.governance.knowledgebase.service.MedicalDocumentCrawlService;
import com.lx.governance.knowledgebase.service.MedicalDocumentGovernanceService;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class DocumentControllerCrawlIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private DocumentProcessingService documentProcessingService;
    private DocumentService documentService;
    private DocumentParseResultService documentParseResultService;
    private DocumentChunkService documentChunkService;
    private LocalDocumentBatchImportService localDocumentBatchImportService;
    private MedicalDocumentCrawlService medicalDocumentCrawlService;
    private MockMvc mockMvc;

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        documentProcessingService = Mockito.mock(DocumentProcessingService.class);
        documentService = Mockito.mock(DocumentService.class);
        documentParseResultService = Mockito.mock(DocumentParseResultService.class);
        documentChunkService = Mockito.mock(DocumentChunkService.class);
        localDocumentBatchImportService = Mockito.mock(LocalDocumentBatchImportService.class);

        when(documentService.findActiveByContentHash(anyString(), anyString())).thenReturn(null);
        when(documentService.findLatestBySourceAndFileName(anyString(), anyString(), anyString())).thenReturn(null);

        KnowledgeCrawlerProperties properties = new KnowledgeCrawlerProperties();
        properties.setOutputPath(Files.createTempDirectory("kb-crawler-controller-test").toString());
        properties.setPolitenessDelayMillis(0L);
        properties.setRespectRobotsTxt(true);
        medicalDocumentCrawlService = new MedicalDocumentCrawlService(
                properties,
                documentProcessingService,
                documentService,
                objectMapper,
                new SimpleMeterRegistry());

        DocumentController controller = new DocumentController(
                documentProcessingService,
                documentService,
                documentParseResultService,
                documentChunkService,
                medicalDocumentCrawlService,
                localDocumentBatchImportService,
                Mockito.mock(MedicalDocumentGovernanceService.class));
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void crawlEndpointShouldArchiveSnapshotsAndExposeJobManifest() throws Exception {
        registerText("/robots.txt", "User-agent: *\nAllow: /\n", "text/plain; charset=UTF-8");
        registerText("/sitemap.xml", """
                <urlset>
                  <url><loc>%s/guides/a</loc></url>
                  <url><loc>%s/guides/b</loc></url>
                </urlset>
                """.formatted(baseUrl, baseUrl), "application/xml; charset=UTF-8");
        registerText("/guides/a", """
                <html>
                  <head>
                    <title>指南 A</title>
                    <link rel="canonical" href="%s/guides/final-a" />
                    <meta property="article:published_time" content="2026-06-01" />
                  </head>
                  <body><article><h1>指南 A</h1><p>内容 A</p></article></body>
                </html>
                """.formatted(baseUrl), "text/html; charset=UTF-8");
        registerText("/guides/b", """
                <html>
                  <head><title>指南 B</title></head>
                  <body><article><h1>指南 B</h1><p>内容 B</p></article></body>
                </html>
                """, "text/html; charset=UTF-8");

        CrawlImportRequest request = new CrawlImportRequest();
        request.setSeedUrl(baseUrl + "/guides/");
        request.setSitemapUrl(baseUrl + "/sitemap.xml");
        request.setMaxPages(5);
        request.setDryRun(true);
        request.setJobLabel("controller-integration");

        MvcResult result = mockMvc.perform(post("/api/kb/{kbId}/documents/crawl", "kb-ctrl-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.importedDocuments").value(0))
                .andExpect(jsonPath("$.data.skippedDocuments").value(2))
                .andExpect(jsonPath("$.data.pages[0].status").value("DRY_RUN"))
                .andExpect(jsonPath("$.data.pages[0].canonicalUrl").value(baseUrl + "/guides/final-a"))
                .andReturn();

        JsonNode payload = objectMapper.readTree(result.getResponse().getContentAsString());
        String outputDir = payload.path("data").path("outputDir").asText();
        String jobId = payload.path("data").path("crawlJobId").asText();

        assertTrue(Files.exists(Path.of(outputDir).resolve("crawl-job.json")));
        assertTrue(Files.exists(Path.of(outputDir).resolve("raw-html")));
        assertTrue(Files.exists(Path.of(outputDir).resolve("markdown")));
        assertTrue(Files.readString(Path.of(outputDir).resolve("crawl-job.json")).contains("\"status\" : \"COMPLETED\""));

        mockMvc.perform(get("/api/kb/{kbId}/documents/crawl/jobs", "kb-ctrl-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].jobId").value(jobId));

        mockMvc.perform(get("/api/kb/{kbId}/documents/crawl/jobs/{jobId}", "kb-ctrl-1", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.jobId").value(jobId))
                .andExpect(jsonPath("$.data.pages[0].publishedAt").value("2026-06-01"));

        verify(documentProcessingService, never()).uploadDocument(eq("kb-ctrl-1"), any());
    }

    @Test
    void crawlEndpointShouldUploadNormalizedMarkdownWithMetadata() throws Exception {
        registerText("/robots.txt", "User-agent: *\nAllow: /\n", "text/plain; charset=UTF-8");
        registerText("/sitemap.xml", """
                <urlset>
                  <url><loc>%s/guides/hypertension-exercise</loc></url>
                </urlset>
                """.formatted(baseUrl), "application/xml; charset=UTF-8");
        registerText("/guides/hypertension-exercise", """
                <html>
                  <head><title>高血压运动管理</title></head>
                  <body>
                    <article>
                      <h1>高血压运动管理</h1>
                      <p>建议每周进行中等强度有氧运动。</p>
                      <h2>注意事项</h2>
                      <ul><li>运动后观察血压变化</li></ul>
                    </article>
                  </body>
                </html>
                """, "text/html; charset=UTF-8");

        KnowledgeDocument uploaded = new KnowledgeDocument();
        uploaded.setDocId("doc-hyper-1");
        uploaded.setStatus("ACTIVE");
        when(documentProcessingService.uploadDocument(eq("kb-ctrl-2"), any())).thenReturn(uploaded);

        CrawlImportRequest request = new CrawlImportRequest();
        request.setSeedUrl(baseUrl + "/guides/");
        request.setSitemapUrl(baseUrl + "/sitemap.xml");
        request.setMaxPages(3);
        request.setDryRun(false);
        request.setTitlePrefix("医疗搜推");
        request.setTags(List.of("hypertension", "exercise"));
        request.setBusinessDomain("chronic_disease");
        request.setApplicableScope("doctor_assistant");
        request.setTrustLevel("HIGH");
        request.setCreatedBy("integration-test");
        request.setSource("web_crawler");

        mockMvc.perform(post("/api/kb/{kbId}/documents/crawl", "kb-ctrl-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.importedDocuments").value(1))
                .andExpect(jsonPath("$.data.pages[0].docId").value("doc-hyper-1"))
                .andExpect(jsonPath("$.data.pages[0].status").value("ACTIVE"));

        ArgumentCaptor<UploadDocumentRequest> uploadCaptor = ArgumentCaptor.forClass(UploadDocumentRequest.class);
        verify(documentProcessingService).uploadDocument(eq("kb-ctrl-2"), uploadCaptor.capture());

        UploadDocumentRequest upload = uploadCaptor.getValue();
        String markdown = new String(upload.getFile().getBytes(), StandardCharsets.UTF_8);
        assertTrue(upload.getTitle().contains("医疗搜推 - 高血压运动管理"));
        assertTrue(upload.getTags().contains("medical"));
        assertTrue(upload.getTags().contains("crawler"));
        assertTrue(upload.getTags().contains("hypertension"));
        assertTrue("chronic_disease".equals(upload.getBusinessDomain()));
        assertTrue(upload.getSource().startsWith("web_crawler:127.0.0.1"));
        assertTrue("doctor_assistant".equals(upload.getApplicableScope()));
        assertTrue("HIGH".equals(upload.getTrustLevel()));
        assertTrue("integration-test".equals(upload.getCreatedBy()));
        assertTrue(markdown.contains("# 高血压运动管理"));
        assertTrue(markdown.contains("## 注意事项"));
        assertTrue(markdown.contains("- 运动后观察血压变化"));
    }

    private void registerText(String path, String content, String contentType) {
        server.createContext(path, exchange -> writeResponse(exchange, content, contentType));
    }

    private void writeResponse(HttpExchange exchange, String content, String contentType) throws IOException {
        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(body);
        }
    }
}
