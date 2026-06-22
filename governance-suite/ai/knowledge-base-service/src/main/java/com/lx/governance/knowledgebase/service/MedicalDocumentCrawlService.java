package com.lx.governance.knowledgebase.service;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import com.lx.governance.knowledgebase.config.KnowledgeCrawlerProperties;
import com.lx.governance.knowledgebase.dto.CrawlImportRequest;
import com.lx.governance.knowledgebase.dto.CrawlImportResponse;
import com.lx.governance.knowledgebase.dto.CrawledPageSummary;
import com.lx.governance.knowledgebase.dto.UploadDocumentRequest;
import com.lx.governance.knowledgebase.entity.KnowledgeDocument;

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

    public CrawlImportResponse crawlAndImport(String kbId, CrawlImportRequest request) throws Exception {
        if (!crawlerProperties.isEnabled()) {
            throw new IllegalStateException("知识抓取能力当前未启用");
        }

        URI seedUri = normalizeUri(request.getSeedUrl());
        int pageLimit = Math.min(
                safeInt(request.getMaxPages(), crawlerProperties.getMaxPagesPerJob()),
                crawlerProperties.getMaxPagesPerJob());

        Set<String> visited = new LinkedHashSet<>();
        Queue<String> pending = new ArrayDeque<>();
        pending.add(seedUri.toString());

        List<CrawledPageSummary> pageSummaries = new ArrayList<>();
        int imported = 0;
        long lastFetchAt = 0L;

        while (!pending.isEmpty() && visited.size() < pageLimit) {
            String currentUrl = pending.poll();
            if (currentUrl == null || visited.contains(currentUrl)) {
                continue;
            }

            URI currentUri = normalizeUri(currentUrl);
            if (!isAllowed(seedUri, currentUri, request)) {
                continue;
            }

            sleepIfNeeded(lastFetchAt);
            lastFetchAt = System.currentTimeMillis();
            visited.add(currentUri.toString());

            try {
                Document page = fetch(currentUri.toString());
                ExtractedPage extracted = extractPage(page, currentUri);
                if (extracted.content().isBlank()) {
                    pageSummaries.add(CrawledPageSummary.builder()
                            .url(currentUri.toString())
                            .title(extracted.title())
                            .status("SKIPPED_EMPTY")
                            .textLength(0)
                            .build());
                    continue;
                }

                KnowledgeDocument document = importAsDocument(kbId, request, currentUri, extracted);
                imported++;
                pageSummaries.add(CrawledPageSummary.builder()
                        .url(currentUri.toString())
                        .title(extracted.title())
                        .docId(document.getDocId())
                        .status(document.getStatus())
                        .textLength(extracted.content().length())
                        .build());

                for (String link : collectCandidateLinks(page, currentUri, request)) {
                    if (!visited.contains(link) && pending.size() + visited.size() < pageLimit * 3) {
                        pending.add(link);
                    }
                }
            } catch (Exception ex) {
                log.warn("抓取页面失败: url={}, message={}", currentUri, ex.getMessage());
                pageSummaries.add(CrawledPageSummary.builder()
                        .url(currentUri.toString())
                        .status("FAILED")
                        .textLength(0)
                        .build());
            }
        }

        return CrawlImportResponse.builder()
                .kbId(kbId)
                .seedUrl(seedUri.toString())
                .requestedPages(pageLimit)
                .crawledPages(visited.size())
                .importedDocuments(imported)
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
        return new ExtractedPage(title, summary, markdown);
    }

    private KnowledgeDocument importAsDocument(
            String kbId,
            CrawlImportRequest request,
            URI pageUri,
            ExtractedPage page) throws Exception {

        String title = trimToLength(buildDocumentTitle(request, page.title()), 180);
        String slug = slugify(pageUri.getHost() + "-" + fallbackTitleFromUri(pageUri));
        String markdown = buildMarkdownDocument(pageUri, page, request);
        Path archiveFile = archiveRawMarkdown(slug, markdown);

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
                archiveFile.getFileName().toString(),
                "text/markdown",
                markdown.getBytes(StandardCharsets.UTF_8)));
        return documentProcessingService.uploadDocument(kbId, upload);
    }

    private Path archiveRawMarkdown(String slug, String markdown) throws IOException {
        String timestamp = JOB_TIME_FORMAT.format(LocalDateTime.now());
        Path outputDir = Path.of(crawlerProperties.getOutputPath(), timestamp);
        Files.createDirectories(outputDir);
        Path outputFile = outputDir.resolve(slug + ".md");
        Files.writeString(outputFile, markdown, StandardCharsets.UTF_8);
        return outputFile;
    }

    private List<String> collectCandidateLinks(Document document, URI currentUri, CrawlImportRequest request) {
        List<String> links = new ArrayList<>();
        Elements anchors = document.select("a[href]");
        for (Element anchor : anchors) {
            String href = anchor.absUrl("href");
            if (href == null || href.isBlank()) {
                continue;
            }
            try {
                URI next = normalizeUri(href);
                if (isAllowed(currentUri, next, request) && isDocumentLike(next)) {
                    links.add(next.toString());
                }
            } catch (Exception ignore) {
                // ignore malformed links
            }
        }
        return links;
    }

    private boolean isAllowed(URI seedUri, URI targetUri, CrawlImportRequest request) {
        if (targetUri == null || targetUri.getHost() == null) {
            return false;
        }

        List<String> allowedDomains = request.getAllowedDomains();
        if (allowedDomains != null && !allowedDomains.isEmpty()) {
            boolean match = allowedDomains.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .anyMatch(domain -> targetUri.getHost().equalsIgnoreCase(domain)
                            || targetUri.getHost().endsWith("." + domain.toLowerCase(Locale.ROOT)));
            if (!match) {
                return false;
            }
        }

        if (Boolean.TRUE.equals(request.getSameHostOnly())
                && !seedUri.getHost().equalsIgnoreCase(targetUri.getHost())) {
            return false;
        }

        if (Boolean.TRUE.equals(request.getIncludePathPrefixOnly())) {
            String seedPath = normalizePath(seedUri.getPath());
            String targetPath = normalizePath(targetUri.getPath());
            if (!targetPath.startsWith(seedPath) && !seedPath.startsWith(targetPath)) {
                return false;
            }
        }

        String scheme = targetUri.getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    private boolean isDocumentLike(URI uri) {
        String path = normalizePath(uri.getPath());
        return !path.endsWith(".jpg")
                && !path.endsWith(".jpeg")
                && !path.endsWith(".png")
                && !path.endsWith(".gif")
                && !path.endsWith(".svg")
                && !path.endsWith(".zip")
                && !path.endsWith(".js")
                && !path.endsWith(".css");
    }

    private String buildMarkdownDocument(URI pageUri, ExtractedPage page, CrawlImportRequest request) {
        StringBuilder builder = new StringBuilder();
        builder.append("# ").append(page.title()).append("\n\n");
        builder.append("- source_url: ").append(pageUri).append("\n");
        builder.append("- crawled_at: ").append(LocalDateTime.now()).append("\n");
        builder.append("- business_domain: ").append(firstNonBlank(request.getBusinessDomain(), "medical")).append("\n");
        builder.append("- applicable_scope: ").append(firstNonBlank(request.getApplicableScope(), "doctor_assistant")).append("\n");
        builder.append("- trust_level: ").append(firstNonBlank(request.getTrustLevel(), "HIGH")).append("\n\n");
        if (!page.summary().isBlank()) {
            builder.append("## Summary\n\n");
            builder.append(page.summary()).append("\n\n");
        }
        builder.append("## Content\n\n");
        builder.append(page.content()).append("\n");
        return builder.toString();
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

    private static int safeInt(Integer preferred, int defaultValue) {
        return preferred == null ? defaultValue : preferred;
    }

    record ExtractedPage(String title, String summary, String content) {
    }
}
