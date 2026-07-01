package com.lx.governance.knowledgebase.service;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.HexFormat;
import java.util.Comparator;
import java.util.HashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.knowledgebase.dto.CrawlJobManifestView;
import com.lx.governance.knowledgebase.dto.CrawlJobSummary;
import com.lx.governance.knowledgebase.config.KnowledgeCrawlerProperties;
import com.lx.governance.knowledgebase.dto.CrawlImportRequest;
import com.lx.governance.knowledgebase.dto.CrawlImportResponse;
import com.lx.governance.knowledgebase.dto.CrawledPageSummary;
import com.lx.governance.knowledgebase.dto.UploadDocumentRequest;
import com.lx.governance.knowledgebase.entity.KnowledgeDocument;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 面向医疗知识站点的轻量抓取导入服务。
 * 当前目标是尽量复用既有文档处理链路，而不是实现完整爬虫平台。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MedicalDocumentCrawlService {

    private static final DateTimeFormatter JOB_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Pattern MULTI_BLANKS = Pattern.compile("[ \\t\\x0B\\f\\r]+");
    private static final Pattern MULTI_LINES = Pattern.compile("\\n{3,}");

    private final KnowledgeCrawlerProperties crawlerProperties;
    private final DocumentProcessingService documentProcessingService;
    private final DocumentService documentService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Map<String, RobotsRules> robotsCache = new HashMap<>();

    public CrawlImportResponse crawlAndImport(String kbId, CrawlImportRequest request) throws Exception {
        if (!crawlerProperties.isEnabled()) {
            throw new IllegalStateException("知识抓取能力当前未启用");
        }

        URI seedUri = normalizeUri(request.getSeedUrl());
        URI scopeUri = effectiveScopeUri(seedUri, request);
        int pageLimit = Math.min(
                safeInt(request.getMaxPages(), crawlerProperties.getMaxPagesPerJob()),
                crawlerProperties.getMaxPagesPerJob());
        boolean dryRun = Boolean.TRUE.equals(request.getDryRun());
        meterRegistry.counter("kb.crawler.jobs.started").increment();

        CrawlJobContext job = prepareJobContext(request);
        Set<String> visited = new LinkedHashSet<>();
        Set<String> enqueued = new LinkedHashSet<>();
        Set<String> importedContentSignatures = new LinkedHashSet<>();
        Queue<String> pending = new ArrayDeque<>();

        seedInitialUrls(scopeUri, request, pending, enqueued, pageLimit);

        List<CrawledPageSummary> pageSummaries = new ArrayList<>();
        int imported = 0;
        int skipped = 0;
        int refreshed = 0;
        long lastFetchAt = 0L;
        writeManifest(job, request, "RUNNING", scopeUri.toString(), 0, 0, 0, List.of());

        while (!pending.isEmpty() && visited.size() < pageLimit) {
            String currentUrl = pending.poll();
            if (currentUrl == null) {
                continue;
            }

            URI currentUri = normalizeUri(currentUrl);
            URI normalizedCurrentUri = normalizeForDedup(currentUri, request);
            String visitedKey = normalizedCurrentUri.toString();
            if (visited.contains(visitedKey) || !isAllowed(scopeUri, normalizedCurrentUri, request)) {
                continue;
            }

            sleepIfNeeded(lastFetchAt);
            lastFetchAt = System.currentTimeMillis();
            visited.add(visitedKey);

            try {
                if (!isRobotsAllowed(normalizedCurrentUri)) {
                    skipped++;
                    recordPageStatus("SKIPPED_ROBOTS_TXT");
                    pageSummaries.add(CrawledPageSummary.builder()
                            .url(normalizedCurrentUri.toString())
                            .status("SKIPPED_ROBOTS_TXT")
                            .textLength(0)
                            .build());
                    writeManifest(job, request, "RUNNING", scopeUri.toString(), imported, skipped, refreshed, pageSummaries);
                    continue;
                }

                Document page = fetchWithRetry(normalizedCurrentUri.toString());
                URI canonicalUri = resolveCanonicalUri(page, normalizedCurrentUri, request);
                URI crawlUri = normalizeForDedup(canonicalUri, request);
                String canonicalKey = crawlUri.toString();
                if (visited.contains(canonicalKey) && !canonicalKey.equals(visitedKey)) {
                    skipped++;
                    recordPageStatus("SKIPPED_CANONICAL_DUPLICATE");
                    pageSummaries.add(CrawledPageSummary.builder()
                            .url(normalizedCurrentUri.toString())
                            .canonicalUrl(canonicalKey)
                            .status("SKIPPED_CANONICAL_DUPLICATE")
                            .textLength(0)
                            .build());
                    continue;
                }
                visited.add(canonicalKey);

                ExtractedPage extracted = extractPage(page, crawlUri);
                ArchivedPage archivedPage = archivePage(job, crawlUri, page.outerHtml(), extracted.toMarkdownDocument(crawlUri, request), request);
                if (extracted.content().isBlank()) {
                    skipped++;
                    recordPageStatus("SKIPPED_EMPTY");
                    pageSummaries.add(CrawledPageSummary.builder()
                            .url(normalizedCurrentUri.toString())
                            .canonicalUrl(canonicalKey)
                            .title(extracted.title())
                            .status("SKIPPED_EMPTY")
                            .textLength(0)
                            .rawHtmlPath(stringify(archivedPage.rawHtmlPath()))
                            .markdownPath(stringify(archivedPage.markdownPath()))
                            .publishedAt(extracted.publishedAt())
                            .build());
                    continue;
                }

                String status;
                String docId = null;
                String previousDocId = null;
                String contentSignature = contentSignature(archivedPage.normalizedMarkdownContent());
                if (dryRun) {
                    status = "DRY_RUN";
                    skipped++;
                    recordPageStatus(status);
                } else {
                    ImportDecision decision = resolveImportDecision(
                            kbId,
                            request,
                            crawlUri,
                            archivedPage.suggestedFilename(),
                            contentSignature,
                            importedContentSignatures);
                    previousDocId = decision.previousDocId();
                    if (decision.skipImport()) {
                        status = decision.status();
                        skipped++;
                        recordPageStatus(status);
                    } else {
                        if (decision.archiveExisting()) {
                            documentProcessingService.archiveDocument(kbId, decision.previousDocId());
                            refreshed++;
                        }
                        KnowledgeDocument document = importAsDocument(kbId, request, crawlUri, extracted, archivedPage);
                        imported++;
                        docId = document.getDocId();
                        status = document.getStatus();
                        importedContentSignatures.add(contentSignature);
                        recordPageStatus(status);
                    }
                }

                pageSummaries.add(CrawledPageSummary.builder()
                        .url(normalizedCurrentUri.toString())
                        .canonicalUrl(canonicalKey)
                        .title(extracted.title())
                        .docId(docId)
                        .previousDocId(previousDocId)
                        .status(status)
                        .textLength(extracted.content().length())
                        .rawHtmlPath(stringify(archivedPage.rawHtmlPath()))
                        .markdownPath(stringify(archivedPage.markdownPath()))
                        .publishedAt(extracted.publishedAt())
                        .build());
                writeManifest(job, request, "RUNNING", scopeUri.toString(), imported, skipped, refreshed, pageSummaries);

                for (String link : collectCandidateLinks(page, scopeUri, request)) {
                    if (enqueued.add(link) && enqueued.size() <= pageLimit * 5) {
                        pending.add(link);
                    }
                }
            } catch (Exception ex) {
                log.warn("抓取页面失败: url={}, message={}", normalizedCurrentUri, ex.getMessage());
                skipped++;
                recordPageStatus("FAILED");
                pageSummaries.add(CrawledPageSummary.builder()
                        .url(normalizedCurrentUri.toString())
                        .status("FAILED")
                        .textLength(0)
                        .build());
                writeManifest(job, request, "RUNNING", scopeUri.toString(), imported, skipped, refreshed, pageSummaries);
            }
        }

        String finalStatus = finalJobStatus(pageSummaries, imported, refreshed);
        recordJobStatus(finalStatus);
        writeManifest(job, request, finalStatus, scopeUri.toString(), imported, skipped, refreshed, pageSummaries);
        meterRegistry.counter("kb.crawler.pages.total").increment(pageSummaries.size());

        return CrawlImportResponse.builder()
                .kbId(kbId)
                .seedUrl(scopeUri.toString())
                .crawlJobId(job.jobId())
                .outputDir(job.outputDir().toString())
                .dryRun(dryRun)
                .requestedPages(pageLimit)
                .crawledPages(visited.size())
                .importedDocuments(imported)
                .skippedDocuments(skipped)
                .refreshedDocuments(refreshed)
                .pages(pageSummaries)
                .build();
    }

    Document fetch(String url) throws IOException {
        return Jsoup.connect(url)
                .userAgent(crawlerProperties.getUserAgent())
                .timeout(crawlerProperties.getConnectTimeoutMillis() + crawlerProperties.getReadTimeoutMillis())
                .maxBodySize(crawlerProperties.getMaxContentLength())
                .followRedirects(true)
                .get();
    }

    Document fetchWithRetry(String url) throws IOException {
        IOException last = null;
        int attempts = Math.max(1, crawlerProperties.getFetchRetryCount() + 1);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return fetch(url);
            } catch (IOException ex) {
                last = ex;
                if (attempt < attempts) {
                    meterRegistry.counter("kb.crawler.fetch.retries").increment();
                    sleepRetryBackoff(attempt);
                }
            }
        }
        throw last;
    }

    String fetchText(String url) throws IOException {
        return Jsoup.connect(url)
                .userAgent(crawlerProperties.getUserAgent())
                .timeout(crawlerProperties.getConnectTimeoutMillis() + crawlerProperties.getReadTimeoutMillis())
                .maxBodySize(crawlerProperties.getMaxContentLength())
                .ignoreContentType(true)
                .execute()
                .body();
    }

    String fetchTextWithRetry(String url) throws IOException {
        IOException last = null;
        int attempts = Math.max(1, crawlerProperties.getFetchRetryCount() + 1);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return fetchText(url);
            } catch (IOException ex) {
                last = ex;
                if (attempt < attempts) {
                    meterRegistry.counter("kb.crawler.fetch.retries").increment();
                    sleepRetryBackoff(attempt);
                }
            }
        }
        throw last;
    }

    ExtractedPage extractPage(Document document, URI pageUri) {
        String title = firstNonBlank(document.title(),
                textOfMeta(document, "meta[property=og:title]"),
                textOfMeta(document, "meta[name=title]"),
                fallbackTitleFromUri(pageUri));

        Element contentRoot = firstNonNull(
                document.selectFirst("article"),
                document.selectFirst("main"),
                document.selectFirst(".markdown-body"),
                document.selectFirst(".article"),
                document.selectFirst(".article-content"),
                document.selectFirst(".content"),
                document.body());

        String markdown = toMarkdownLikeText(contentRoot);
        String summary = firstNonBlank(
                textOfMeta(document, "meta[name=description]"),
                textOfMeta(document, "meta[property=og:description]"),
                "");
        String publishedAt = firstNonBlank(
                textOfMeta(document, "meta[property=article:published_time]"),
                textOfMeta(document, "meta[name=pubdate]"),
                textOfMeta(document, "meta[name=date]"),
                textOfMeta(document, "meta[itemprop=datePublished]"),
                "");
        return new ExtractedPage(title, summary, markdown, publishedAt);
    }

    private void seedInitialUrls(
            URI scopeUri,
            CrawlImportRequest request,
            Queue<String> pending,
            Set<String> enqueued,
            int pageLimit) throws IOException, URISyntaxException {
        URI sitemapUri = request.getSitemapUrl() != null && !request.getSitemapUrl().isBlank()
                ? normalizeUri(request.getSitemapUrl())
                : inferSitemapUri(scopeUri);
        if (sitemapUri != null) {
            try {
                List<String> sitemapUrls = readSitemapUrls(sitemapUri, scopeUri, request, pageLimit);
                for (String url : sitemapUrls) {
                    if (enqueued.add(url)) {
                        pending.add(url);
                    }
                }
            } catch (IOException e) {
                log.warn("读取 sitemap 失败，回退 seedUrl: sitemap={}, message={}", sitemapUri, e.getMessage());
            }
        }
        if (enqueued.isEmpty()) {
            enqueued.add(scopeUri.toString());
            pending.add(scopeUri.toString());
        }
    }

    private List<String> readSitemapUrls(
            URI sitemapUri,
            URI scopeUri,
            CrawlImportRequest request,
            int pageLimit) throws IOException, URISyntaxException {
        String xml = fetchTextWithRetry(sitemapUri.toString());
        Document sitemap = Jsoup.parse(xml, "", Parser.xmlParser());
        List<String> urls = new ArrayList<>();
        for (Element loc : sitemap.select("url > loc, sitemap > loc")) {
            String text = loc.text();
            if (text == null || text.isBlank()) {
                continue;
            }
            URI target = normalizeForDedup(normalizeUri(text), request);
            if (isAllowed(scopeUri, target, request) && isDocumentLike(target)) {
                urls.add(target.toString());
                if (urls.size() >= pageLimit) {
                    break;
                }
            }
        }
        return urls;
    }

    private KnowledgeDocument importAsDocument(
            String kbId,
            CrawlImportRequest request,
            URI pageUri,
            ExtractedPage page,
            ArchivedPage archivedPage) throws Exception {

        String title = trimToLength(buildDocumentTitle(request, page.title()), 180);
        String markdown = archivedPage.markdownPath() != null
                ? Files.readString(archivedPage.markdownPath(), StandardCharsets.UTF_8)
                : page.toMarkdownDocument(pageUri, request);
        markdown = sanitizePromptInjectionContent(markdown);

        UploadDocumentRequest upload = new UploadDocumentRequest();
        upload.setTitle(title);
        upload.setTags(buildTags(request, pageUri));
        upload.setBusinessDomain(firstNonBlank(request.getBusinessDomain(), "medical"));
        upload.setSource(firstNonBlank(request.getSource(), "web_crawler") + ":" + pageUri.getHost());
        upload.setApplicableScope(firstNonBlank(request.getApplicableScope(), "doctor_assistant"));
        upload.setTrustLevel(firstNonBlank(request.getTrustLevel(), "HIGH"));
        upload.setCreatedBy(firstNonBlank(request.getCreatedBy(), crawlerProperties.getDefaultCreatedBy()));
        upload.setFile(new ByteArrayMultipartFile(
                "file",
                archivedPage.suggestedFilename(),
                "text/markdown",
                markdown.getBytes(StandardCharsets.UTF_8)));
        return documentProcessingService.uploadDocument(kbId, upload);
    }

    private ArchivedPage archivePage(
            CrawlJobContext job,
            URI pageUri,
            String rawHtml,
            String markdown,
            CrawlImportRequest request) throws IOException {
        String slug = slugify(pageUri.getHost() + "-" + fallbackTitleFromUri(pageUri));
        Path htmlPath = null;
        if (!Boolean.FALSE.equals(request.getArchiveRawHtml())) {
            htmlPath = job.rawDir().resolve(slug + ".html");
            Files.writeString(htmlPath, rawHtml, StandardCharsets.UTF_8);
        }
        Path markdownPath = null;
        if (!Boolean.FALSE.equals(request.getArchiveNormalizedMarkdown())) {
            markdownPath = job.markdownDir().resolve(slug + ".md");
            Files.writeString(markdownPath, markdown, StandardCharsets.UTF_8);
        }
        return new ArchivedPage(htmlPath, markdownPath, slug + ".md", markdown);
    }

    public CrawlJobManifestView getCrawlJob(String jobId) throws IOException {
        Path manifestPath = Path.of(crawlerProperties.getOutputPath(), jobId, "crawl-job.json");
        if (!Files.exists(manifestPath)) {
            throw new IllegalArgumentException("抓取作业不存在: " + jobId);
        }
        return objectMapper.readValue(manifestPath.toFile(), CrawlJobManifestView.class);
    }

    public CrawlImportResponse retryCrawlJob(String kbId, String jobId) throws Exception {
        CrawlJobManifestView manifest = getCrawlJob(jobId);
        CrawlImportRequest request = toRequest(manifest);
        request.setJobLabel(manifest.getJobId() + "-retry");
        meterRegistry.counter("kb.crawler.jobs.retried").increment();
        return crawlAndImport(kbId, request);
    }

    public List<CrawlJobSummary> listCrawlJobs(int limit) throws IOException {
        int safeLimit = Math.max(1, limit);
        Path outputRoot = Path.of(crawlerProperties.getOutputPath());
        if (!Files.exists(outputRoot)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(outputRoot)) {
            return stream
                    .filter(Files::isDirectory)
                    .sorted(Comparator.reverseOrder())
                    .limit(safeLimit)
                    .map(dir -> dir.resolve("crawl-job.json"))
                    .filter(Files::exists)
                    .map(this::readCrawlJobSummary)
                    .filter(Objects::nonNull)
                    .toList();
        }
    }

    private List<String> collectCandidateLinks(Document document, URI scopeUri, CrawlImportRequest request) {
        List<String> links = new ArrayList<>();
        Elements anchors = document.select("a[href]");
        for (Element anchor : anchors) {
            String href = anchor.absUrl("href");
            if (href == null || href.isBlank()) {
                continue;
            }
            try {
                URI next = normalizeForDedup(normalizeUri(href), request);
                if (isAllowed(scopeUri, next, request) && isDocumentLike(next)) {
                    links.add(next.toString());
                }
            } catch (Exception ignore) {
                // ignore malformed links
            }
        }
        return links;
    }

    private boolean isAllowed(URI scopeUri, URI targetUri, CrawlImportRequest request) {
        if (targetUri == null || targetUri.getHost() == null) {
            return false;
        }

        String targetUrl = targetUri.toString().toLowerCase(Locale.ROOT);
        if (request.getBlockedUrlKeywords() != null && request.getBlockedUrlKeywords().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .anyMatch(targetUrl::contains)) {
            return false;
        }

        List<String> allowedDomains = request.getAllowedDomains();
        if (allowedDomains != null && !allowedDomains.isEmpty()) {
            boolean match = allowedDomains.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .anyMatch(domain -> {
                        String normalizedDomain = domain.toLowerCase(Locale.ROOT);
                        String targetHost = targetUri.getHost().toLowerCase(Locale.ROOT);
                        return targetHost.equals(normalizedDomain) || targetHost.endsWith("." + normalizedDomain);
                    });
            if (!match) {
                return false;
            }
        }

        if (Boolean.TRUE.equals(request.getSameHostOnly())
                && !scopeUri.getHost().equalsIgnoreCase(targetUri.getHost())) {
            return false;
        }

        if (Boolean.TRUE.equals(request.getIncludePathPrefixOnly())) {
            String scopePath = normalizePath(scopeUri.getPath());
            String targetPath = normalizePath(targetUri.getPath());
            if (!targetPath.startsWith(scopePath) && !scopePath.startsWith(targetPath)) {
                return false;
            }
        }

        String targetPath = normalizePath(targetUri.getPath());
        if (request.getAllowedPathPrefixes() != null && !request.getAllowedPathPrefixes().isEmpty()) {
            boolean allowed = request.getAllowedPathPrefixes().stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .map(MedicalDocumentCrawlService::normalizePath)
                    .anyMatch(targetPath::startsWith);
            if (!allowed) {
                return false;
            }
        }
        if (request.getBlockedPathPrefixes() != null && request.getBlockedPathPrefixes().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(MedicalDocumentCrawlService::normalizePath)
                .anyMatch(targetPath::startsWith)) {
            return false;
        }

        String scheme = targetUri.getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    private boolean isRobotsAllowed(URI targetUri) {
        if (!crawlerProperties.isRespectRobotsTxt()) {
            return true;
        }
        try {
            URI robotsUri = new URI(targetUri.getScheme(), targetUri.getAuthority(), "/robots.txt", null, null);
            String cacheKey = robotsUri.toString();
            RobotsRules rules = robotsCache.get(cacheKey);
            if (rules == null) {
                rules = loadRobotsRules(robotsUri);
                robotsCache.put(cacheKey, rules);
            }
            return rules.isAllowed(targetUri.getPath());
        } catch (Exception e) {
            log.warn("读取 robots.txt 失败，默认放行: host={}, message={}", targetUri.getHost(), e.getMessage());
            return true;
        }
    }

    private boolean isDocumentLike(URI uri) {
        String path = normalizePath(uri.getPath()).toLowerCase(Locale.ROOT);
        return !path.endsWith(".jpg/")
                && !path.endsWith(".jpeg/")
                && !path.endsWith(".png/")
                && !path.endsWith(".gif/")
                && !path.endsWith(".svg/")
                && !path.endsWith(".zip/")
                && !path.endsWith(".js/")
                && !path.endsWith(".css/")
                && !path.endsWith(".xml/");
    }

    private URI resolveCanonicalUri(Document document, URI fallbackUri, CrawlImportRequest request)
            throws URISyntaxException {
        String canonical = firstNonBlank(
                attributeOf(document, "link[rel=canonical]", "href"),
                textOfMeta(document, "meta[property=og:url]"));
        if (canonical.isBlank()) {
            return fallbackUri;
        }
        URI resolved = normalizeUri(canonical);
        return normalizeForDedup(resolved, request);
    }

    private URI effectiveScopeUri(URI seedUri, CrawlImportRequest request) throws URISyntaxException {
        if (request.getSitemapUrl() == null || request.getSitemapUrl().isBlank()) {
            return normalizeForDedup(seedUri, request);
        }
        URI explicitSitemap = normalizeUri(request.getSitemapUrl());
        URI scope = seedUri.getPath() != null && !seedUri.getPath().isBlank() && !seedUri.getPath().endsWith(".xml")
                ? seedUri
                : new URI(explicitSitemap.getScheme(), explicitSitemap.getAuthority(), "/", null, null);
        return normalizeForDedup(scope, request);
    }

    private URI inferSitemapUri(URI seedUri) throws URISyntaxException {
        String path = seedUri.getPath();
        if (path != null && path.toLowerCase(Locale.ROOT).endsWith(".xml")) {
            return seedUri;
        }
        return null;
    }

    private CrawlJobContext prepareJobContext(CrawlImportRequest request) throws IOException {
        String timestamp = JOB_TIME_FORMAT.format(LocalDateTime.now());
        String label = slugify(firstNonBlank(request.getJobLabel(), fallbackTitleFromSeed(request.getSeedUrl())));
        String jobId = timestamp + "-" + label;
        Path jobDir = Path.of(crawlerProperties.getOutputPath(), jobId);
        Path rawDir = jobDir.resolve("raw-html");
        Path markdownDir = jobDir.resolve("markdown");
        Path manifestPath = jobDir.resolve("crawl-job.json");
        Files.createDirectories(rawDir);
        Files.createDirectories(markdownDir);
        return new CrawlJobContext(jobId, jobDir, rawDir, markdownDir, manifestPath);
    }

    private ImportDecision resolveImportDecision(
            String kbId,
            CrawlImportRequest request,
            URI pageUri,
            String suggestedFilename,
            String contentSignature,
            Set<String> importedContentSignatures) {
        if (importedContentSignatures.contains(contentSignature)) {
            return new ImportDecision(true, false, null, "SKIPPED_CONTENT_DUPLICATE_IN_JOB");
        }

        KnowledgeDocument sameContent = documentService.findActiveByContentHash(kbId, contentSignature);
        if (sameContent != null) {
            return new ImportDecision(true, false, sameContent.getDocId(), "SKIPPED_CONTENT_DUPLICATE");
        }

        String source = firstNonBlank(request.getSource(), "web_crawler") + ":" + pageUri.getHost();
        KnowledgeDocument existing = documentService.findLatestBySourceAndFileName(kbId, source, suggestedFilename);
        if (existing == null) {
            return new ImportDecision(false, false, null, "IMPORTED");
        }

        String refreshMode = firstNonBlank(request.getRefreshMode(), "SKIP_EXISTING").toUpperCase(Locale.ROOT);
        return switch (refreshMode) {
            case "ARCHIVE_AND_REIMPORT" -> new ImportDecision(false, true, existing.getDocId(), "REFRESHED");
            case "SKIP_EXISTING" -> new ImportDecision(true, false, existing.getDocId(), "SKIPPED_EXISTING");
            default -> new ImportDecision(true, false, existing.getDocId(), "SKIPPED_UNKNOWN_REFRESH_MODE");
        };
    }

    private String finalJobStatus(List<CrawledPageSummary> pages, int imported, int refreshed) {
        boolean hasFailed = pages.stream().anyMatch(page -> "FAILED".equals(page.getStatus()));
        boolean hasSuccessLike = imported > 0 || refreshed > 0 || pages.stream().anyMatch(page ->
                "DRY_RUN".equals(page.getStatus())
                        || "ACTIVE".equals(page.getStatus())
                        || "REFRESHED".equals(page.getStatus()));
        if (hasFailed && hasSuccessLike) {
            return "PARTIAL_SUCCESS";
        }
        if (hasFailed) {
            return "FAILED";
        }
        return "COMPLETED";
    }

    private void writeManifest(
            CrawlJobContext job,
            CrawlImportRequest request,
            String status,
            String seedUrl,
            int imported,
            int skipped,
            int refreshed,
            List<CrawledPageSummary> pages) throws IOException {
        CrawlJobManifest manifest = new CrawlJobManifest(
                job.jobId(),
                status,
                seedUrl,
                request.getSitemapUrl(),
                Boolean.TRUE.equals(request.getDryRun()),
                firstNonBlank(request.getRefreshMode(), "SKIP_EXISTING"),
                request.getMaxPages(),
                request.getTitlePrefix(),
                request.getSource(),
                request.getBusinessDomain(),
                request.getApplicableScope(),
                request.getTrustLevel(),
                request.getCreatedBy(),
                request.getJobLabel(),
                request.getTags(),
                request.getAllowedDomains(),
                request.getAllowedPathPrefixes(),
                request.getBlockedPathPrefixes(),
                request.getBlockedUrlKeywords(),
                request.getSameHostOnly(),
                request.getIncludePathPrefixOnly(),
                request.getArchiveRawHtml(),
                request.getArchiveNormalizedMarkdown(),
                request.getStripQueryString(),
                imported,
                skipped,
                refreshed,
                LocalDateTime.now().toString(),
                pages);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(job.manifestPath().toFile(), manifest);
    }

    private CrawlJobSummary readCrawlJobSummary(Path manifestPath) {
        try {
            CrawlJobManifestView manifest = objectMapper.readValue(manifestPath.toFile(), CrawlJobManifestView.class);
            return CrawlJobSummary.builder()
                    .jobId(manifest.getJobId())
                    .status(manifest.getStatus())
                    .seedUrl(manifest.getSeedUrl())
                    .dryRun(manifest.isDryRun())
                    .updatedAt(manifest.getUpdatedAt())
                    .importedDocuments(manifest.getImportedDocuments())
                    .skippedDocuments(manifest.getSkippedDocuments())
                    .refreshedDocuments(manifest.getRefreshedDocuments())
                    .build();
        } catch (Exception e) {
            log.warn("读取 crawl job manifest 失败: path={}, message={}", manifestPath, e.getMessage());
            return null;
        }
    }

    private RobotsRules loadRobotsRules(URI robotsUri) throws IOException {
        try {
            String body = fetchTextWithRetry(robotsUri.toString());
            return RobotsRules.parse(body, crawlerProperties.getUserAgent());
        } catch (IOException e) {
            return RobotsRules.allowAll();
        }
    }

    private void sleepRetryBackoff(int attempt) {
        long backoff = Math.max(0L, crawlerProperties.getFetchRetryBackoffMillis()) * attempt;
        if (backoff <= 0) {
            return;
        }
        try {
            Thread.sleep(backoff);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private CrawlImportRequest toRequest(CrawlJobManifestView manifest) {
        CrawlImportRequest request = new CrawlImportRequest();
        request.setSeedUrl(manifest.getSeedUrl());
        request.setSitemapUrl(manifest.getSitemapUrl());
        request.setDryRun(manifest.isDryRun());
        request.setRefreshMode(manifest.getRefreshMode());
        request.setMaxPages(manifest.getMaxPages());
        request.setTitlePrefix(manifest.getTitlePrefix());
        request.setSource(manifest.getSource());
        request.setBusinessDomain(manifest.getBusinessDomain());
        request.setApplicableScope(manifest.getApplicableScope());
        request.setTrustLevel(manifest.getTrustLevel());
        request.setCreatedBy(manifest.getCreatedBy());
        request.setJobLabel(manifest.getJobLabel());
        request.setTags(manifest.getTags());
        request.setAllowedDomains(manifest.getAllowedDomains());
        request.setAllowedPathPrefixes(manifest.getAllowedPathPrefixes());
        request.setBlockedPathPrefixes(manifest.getBlockedPathPrefixes());
        request.setBlockedUrlKeywords(manifest.getBlockedUrlKeywords());
        request.setSameHostOnly(manifest.getSameHostOnly());
        request.setIncludePathPrefixOnly(manifest.getIncludePathPrefixOnly());
        request.setArchiveRawHtml(manifest.getArchiveRawHtml());
        request.setArchiveNormalizedMarkdown(manifest.getArchiveNormalizedMarkdown());
        request.setStripQueryString(manifest.getStripQueryString());
        return request;
    }

    private void recordPageStatus(String status) {
        meterRegistry.counter("kb.crawler.pages.by_status", "status", status).increment();
    }

    private void recordJobStatus(String status) {
        meterRegistry.counter("kb.crawler.jobs.completed", "status", status).increment();
    }

    private String buildDocumentTitle(CrawlImportRequest request, String pageTitle) {
        String prefix = firstNonBlank(request.getTitlePrefix(), "医疗知识抓取");
        return prefix + " - " + pageTitle;
    }

    private String buildTags(CrawlImportRequest request, URI pageUri) {
        List<String> tags = new ArrayList<>();
        tags.add("medical");
        tags.add("crawler");
        tags.add(slugify(pageUri.getHost()));
        tags.add("review_status:pending");
        tags.add("source_type:" + inferSourceType(pageUri));
        if (request.getTags() != null) {
            request.getTags().stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .forEach(tags::add);
        }
        return String.join(",", new LinkedHashSet<>(tags));
    }

    String toMarkdownLikeText(Element root) {
        if (root == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for (Element element : root.select("h1,h2,h3,h4,h5,h6,p,li,blockquote,pre,table,tr")) {
            String tag = element.tagName();
            String text = normalizeText(element.text());
            if (text.isBlank()) {
                continue;
            }
            switch (tag) {
                case "h1" -> builder.append("# ").append(text).append("\n\n");
                case "h2" -> builder.append("## ").append(text).append("\n\n");
                case "h3" -> builder.append("### ").append(text).append("\n\n");
                case "h4", "h5", "h6" -> builder.append("#### ").append(text).append("\n\n");
                case "li" -> builder.append("- ").append(text).append("\n");
                case "blockquote" -> builder.append("> ").append(text).append("\n\n");
                case "pre" -> builder.append("```text\n").append(text).append("\n```\n\n");
                case "tr" -> builder.append(text).append("\n");
                default -> builder.append(text).append("\n\n");
            }
        }

        String text = normalizeText(builder.toString());
        return MULTI_LINES.matcher(text).replaceAll("\n\n").trim();
    }

    private String textOfMeta(Document document, String selector) {
        Element meta = document.selectFirst(selector);
        if (meta == null) {
            return "";
        }
        return firstNonBlank(meta.attr("content"), meta.text(), "");
    }

    private String inferSourceType(URI pageUri) {
        String host = pageUri.getHost() == null ? "" : pageUri.getHost().toLowerCase(Locale.ROOT);
        if (host.contains("hospital")) {
            return "hospital";
        }
        if (host.contains("drug") || host.contains("pharma")) {
            return "drug_label";
        }
        if (host.contains("guide") || host.contains("society") || host.contains("association")) {
            return "guideline";
        }
        return "web";
    }

    private String sanitizePromptInjectionContent(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        return content
                .replaceAll("(?i)ignore\\s+all\\s+previous\\s+instructions", "")
                .replaceAll("(?i)system\\s+prompt", "")
                .replaceAll("(?i)you\\s+are\\s+chatgpt", "")
                .replaceAll("(?i)作为大模型.*?执行", "");
    }

    private String attributeOf(Document document, String selector, String attribute) {
        Element element = document.selectFirst(selector);
        return element == null ? "" : firstNonBlank(element.attr(attribute));
    }

    private void sleepIfNeeded(long lastFetchAt) {
        if (lastFetchAt <= 0) {
            return;
        }
        long elapsed = System.currentTimeMillis() - lastFetchAt;
        long delay = crawlerProperties.getPolitenessDelayMillis() - elapsed;
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static String normalizeText(String input) {
        if (input == null) {
            return "";
        }
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFKC)
                .replace('\u00A0', ' ');
        normalized = MULTI_BLANKS.matcher(normalized).replaceAll(" ");
        normalized = normalized.replaceAll(" *\\n *", "\n");
        return normalized.trim();
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        return path.endsWith("/") ? path : path + "/";
    }

    private static String trimToLength(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String fallbackTitleFromUri(URI uri) {
        String path = uri.getPath();
        if (path == null || path.isBlank() || "/".equals(path)) {
            return uri.getHost();
        }
        String[] parts = path.split("/");
        String last = parts[parts.length - 1];
        return last == null || last.isBlank() ? uri.getHost() : last;
    }

    private static String fallbackTitleFromSeed(String rawSeedUrl) {
        try {
            return fallbackTitleFromUri(normalizeUri(rawSeedUrl));
        } catch (Exception e) {
            return "crawl-job";
        }
    }

    private static String slugify(String input) {
        String normalized = Normalizer.normalize(firstNonBlank(input), Normalizer.Form.NFKD)
                .replaceAll("[^\\p{Alnum}]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "");
        return normalized.isBlank() ? "document" : normalized.toLowerCase(Locale.ROOT);
    }

    private static URI normalizeUri(String rawUrl) throws URISyntaxException {
        URI uri = new URI(rawUrl.trim());
        if (uri.getScheme() == null) {
            return new URI("https://" + rawUrl.trim());
        }
        return uri;
    }

    private static URI normalizeForDedup(URI uri, CrawlImportRequest request) throws URISyntaxException {
        String query = Boolean.TRUE.equals(request.getStripQueryString()) ? null : uri.getQuery();
        String path = uri.getPath();
        if (path == null || path.isBlank()) {
            path = "/";
        }
        return new URI(uri.getScheme(), uri.getAuthority(), path, query, null);
    }

    private static int safeInt(Integer preferred, int defaultValue) {
        return preferred == null ? defaultValue : preferred;
    }

    private static String contentSignature(String normalizedMarkdown) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(normalizedMarkdown.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("计算内容签名失败", e);
        }
    }

    private static String stringify(Path path) {
        return path == null ? null : path.toString();
    }

    record ExtractedPage(String title, String summary, String content, String publishedAt) {
        String toMarkdownDocument(URI pageUri, CrawlImportRequest request) {
            StringBuilder builder = new StringBuilder();
            builder.append("# ").append(title).append("\n\n");
            builder.append("- source_url: ").append(pageUri).append("\n");
            builder.append("- crawled_at: ").append(LocalDateTime.now()).append("\n");
            builder.append("- business_domain: ")
                    .append(firstNonBlank(request.getBusinessDomain(), "medical"))
                    .append("\n");
            builder.append("- applicable_scope: ")
                    .append(firstNonBlank(request.getApplicableScope(), "doctor_assistant"))
                    .append("\n");
            builder.append("- trust_level: ")
                    .append(firstNonBlank(request.getTrustLevel(), "HIGH"))
                    .append("\n");
            if (!publishedAt.isBlank()) {
                builder.append("- published_at: ").append(publishedAt).append("\n");
            }
            builder.append("\n");
            if (!summary.isBlank()) {
                builder.append("## Summary\n\n");
                builder.append(summary).append("\n\n");
            }
            builder.append("## Content\n\n");
            builder.append(content).append("\n");
            return builder.toString();
        }
    }

    record CrawlJobContext(String jobId, Path outputDir, Path rawDir, Path markdownDir, Path manifestPath) {
    }

    record ArchivedPage(Path rawHtmlPath, Path markdownPath, String suggestedFilename, String normalizedMarkdownContent) {
    }

    record ImportDecision(boolean skipImport, boolean archiveExisting, String previousDocId, String status) {
    }

    record CrawlJobManifest(
            String jobId,
            String status,
            String seedUrl,
            String sitemapUrl,
            boolean dryRun,
            String refreshMode,
            Integer maxPages,
            String titlePrefix,
            String source,
            String businessDomain,
            String applicableScope,
            String trustLevel,
            String createdBy,
            String jobLabel,
            List<String> tags,
            List<String> allowedDomains,
            List<String> allowedPathPrefixes,
            List<String> blockedPathPrefixes,
            List<String> blockedUrlKeywords,
            Boolean sameHostOnly,
            Boolean includePathPrefixOnly,
            Boolean archiveRawHtml,
            Boolean archiveNormalizedMarkdown,
            Boolean stripQueryString,
            int importedDocuments,
            int skippedDocuments,
            int refreshedDocuments,
            String updatedAt,
            List<CrawledPageSummary> pages) {
    }

    record RobotsRules(List<String> disallowPrefixes) {
        static RobotsRules allowAll() {
            return new RobotsRules(List.of());
        }

        static RobotsRules parse(String body, String userAgent) {
            if (body == null || body.isBlank()) {
                return allowAll();
            }
            String normalizedAgent = userAgent == null ? "*" : userAgent.toLowerCase(Locale.ROOT);
            boolean matchedAgent = false;
            boolean wildcardAgent = false;
            List<String> rules = new ArrayList<>();
            for (String rawLine : body.split("\\R")) {
                String line = rawLine.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int comment = line.indexOf('#');
                if (comment >= 0) {
                    line = line.substring(0, comment).strip();
                }
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.startsWith("user-agent:")) {
                    String agentValue = line.substring("user-agent:".length()).trim().toLowerCase(Locale.ROOT);
                    matchedAgent = normalizedAgent.contains(agentValue) || "*".equals(agentValue);
                    wildcardAgent = "*".equals(agentValue);
                    continue;
                }
                if (lower.startsWith("disallow:") && (matchedAgent || wildcardAgent)) {
                    String path = line.substring("disallow:".length()).trim();
                    if (!path.isBlank()) {
                        rules.add(path);
                    }
                }
            }
            return new RobotsRules(rules);
        }

        boolean isAllowed(String path) {
            String normalizedPath = path == null || path.isBlank() ? "/" : path;
            return disallowPrefixes.stream().noneMatch(normalizedPath::startsWith);
        }
    }
}
