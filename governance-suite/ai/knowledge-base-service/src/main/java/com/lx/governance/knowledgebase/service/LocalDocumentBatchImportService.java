package com.lx.governance.knowledgebase.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.knowledgebase.dto.LocalBatchImportItemResult;
import com.lx.governance.knowledgebase.dto.LocalBatchImportRequest;
import com.lx.governance.knowledgebase.dto.LocalBatchImportResponse;
import com.lx.governance.knowledgebase.dto.UploadDocumentRequest;
import com.lx.governance.knowledgebase.entity.KnowledgeDocument;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class LocalDocumentBatchImportService {

    private final DocumentProcessingService documentProcessingService;
    private final MedicalMetadataNormalizer metadataNormalizer;
    private final ObjectMapper objectMapper;

    public LocalBatchImportResponse importFromManifest(String kbId, LocalBatchImportRequest request) throws Exception {
        Path manifestPath = Path.of(request.getManifestPath()).toAbsolutePath().normalize();
        if (!Files.exists(manifestPath) || !Files.isRegularFile(manifestPath)) {
            throw new IllegalArgumentException("manifest 不存在: " + manifestPath);
        }

        LocalDocumentSeedManifest manifest = objectMapper.readValue(manifestPath.toFile(), LocalDocumentSeedManifest.class);
        List<LocalDocumentSeedItem> documents = manifest.documents() != null ? manifest.documents() : List.of();
        Path baseDir = manifestPath.getParent();

        int imported = 0;
        int skipped = 0;
        java.util.ArrayList<LocalBatchImportItemResult> items = new java.util.ArrayList<>();

        for (LocalDocumentSeedItem item : documents) {
            Path sourcePath = resolveSourcePath(baseDir, item.path());
            if (!Files.exists(sourcePath) || !Files.isRegularFile(sourcePath)) {
                skipped++;
                items.add(LocalBatchImportItemResult.builder()
                        .sourcePath(sourcePath.toString())
                        .title(item.title())
                        .status("FILE_NOT_FOUND")
                        .errorMessage("文件不存在")
                        .build());
                continue;
            }

            MedicalMetadataNormalizer.NormalizedMedicalMetadata normalized = metadataNormalizer.normalize(
                    firstNonBlank(item.title(), sourcePath.getFileName().toString()),
                    item.tags(),
                    firstNonBlank(item.businessDomain(), request.getDefaultBusinessDomain()),
                    item.source(),
                    firstNonBlank(item.applicableScope(), request.getDefaultApplicableScope()),
                    firstNonBlank(item.trustLevel(), request.getDefaultTrustLevel()),
                    firstNonBlank(item.createdBy(), request.getCreatedBy()),
                    item.documentType(),
                    item.disease(),
                    item.population(),
                    item.region(),
                    item.version(),
                    item.publishDate());

            if (Boolean.TRUE.equals(request.getDryRun())) {
                skipped++;
                items.add(LocalBatchImportItemResult.builder()
                        .sourcePath(sourcePath.toString())
                        .title(normalized.getTitle())
                        .fileType(fileTypeOf(sourcePath))
                        .status("DRY_RUN")
                        .tags(String.join(",", normalized.getTags()))
                        .businessDomain(normalized.getBusinessDomain())
                        .applicableScope(normalized.getApplicableScope())
                        .trustLevel(normalized.getTrustLevel())
                        .build());
                continue;
            }

            try {
                UploadDocumentRequest upload = new UploadDocumentRequest();
                upload.setTitle(normalized.getTitle());
                java.util.List<String> uploadTags = new java.util.ArrayList<>(normalized.getTags());
                if (uploadTags.stream().noneMatch(tag -> tag != null && tag.startsWith("review_status:"))) {
                    uploadTags.add("review_status:approved");
                }
                if (uploadTags.stream().noneMatch(tag -> tag != null && tag.startsWith("source_type:"))) {
                    uploadTags.add("source_type:faq");
                }
                upload.setTags(String.join(",", uploadTags));
                upload.setBusinessDomain(normalized.getBusinessDomain());
                upload.setSource(normalized.getSource());
                upload.setApplicableScope(normalized.getApplicableScope());
                upload.setTrustLevel(normalized.getTrustLevel());
                upload.setCreatedBy(normalized.getCreatedBy());
                upload.setFile(new ByteArrayMultipartFile(
                        "file",
                        sourcePath.getFileName().toString(),
                        contentTypeOf(sourcePath),
                        Files.readAllBytes(sourcePath)));

                KnowledgeDocument document = documentProcessingService.uploadDocument(kbId, upload);
                imported++;
                items.add(LocalBatchImportItemResult.builder()
                        .sourcePath(sourcePath.toString())
                        .title(normalized.getTitle())
                        .fileType(fileTypeOf(sourcePath))
                        .status(document.getStatus())
                        .docId(document.getDocId())
                        .tags(upload.getTags())
                        .businessDomain(upload.getBusinessDomain())
                        .applicableScope(upload.getApplicableScope())
                        .trustLevel(upload.getTrustLevel())
                        .build());
            } catch (Exception e) {
                skipped++;
                log.warn("本地批量导入失败: path={}, message={}", sourcePath, e.getMessage());
                items.add(LocalBatchImportItemResult.builder()
                        .sourcePath(sourcePath.toString())
                        .title(normalized.getTitle())
                        .fileType(fileTypeOf(sourcePath))
                        .status("FAILED")
                        .tags(String.join(",", normalized.getTags()))
                        .businessDomain(normalized.getBusinessDomain())
                        .applicableScope(normalized.getApplicableScope())
                        .trustLevel(normalized.getTrustLevel())
                        .errorMessage(e.getMessage())
                        .build());
            }
        }

        return LocalBatchImportResponse.builder()
                .kbId(kbId)
                .manifestPath(manifestPath.toString())
                .dryRun(Boolean.TRUE.equals(request.getDryRun()))
                .totalFiles(documents.size())
                .importedDocuments(imported)
                .skippedDocuments(skipped)
                .items(items)
                .build();
    }

    private Path resolveSourcePath(Path baseDir, String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return baseDir.resolve("__missing__");
        }
        Path path = Path.of(rawPath);
        return path.isAbsolute() ? path.normalize() : baseDir.resolve(path).normalize();
    }

    private String fileTypeOf(Path sourcePath) {
        String fileName = sourcePath.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot + 1).toLowerCase(java.util.Locale.ROOT) : "txt";
    }

    private String contentTypeOf(Path sourcePath) {
        return switch (fileTypeOf(sourcePath)) {
            case "md", "markdown" -> "text/markdown";
            case "txt" -> "text/plain";
            case "pdf" -> "application/pdf";
            default -> "application/octet-stream";
        };
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second != null && !second.isBlank() ? second.trim() : null;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LocalDocumentSeedManifest(String manifestVersion, String libraryName, List<LocalDocumentSeedItem> documents) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LocalDocumentSeedItem(
            String path,
            String title,
            List<String> tags,
            String businessDomain,
            String source,
            String applicableScope,
            String trustLevel,
            String createdBy,
            String documentType,
            String disease,
            String population,
            String region,
            String version,
            String publishDate,
            String summary) {
    }
}
