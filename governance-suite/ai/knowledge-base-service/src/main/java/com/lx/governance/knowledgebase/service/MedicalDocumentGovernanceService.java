package com.lx.governance.knowledgebase.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.knowledgebase.dto.DocumentGovernanceUpdateRequest;
import com.lx.governance.knowledgebase.entity.KnowledgeDocument;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class MedicalDocumentGovernanceService {

    @Value("${knowledge-base.governance.storage-root:data/medical-governance}")
    private String storageRoot;

    private final DocumentService documentService;
    private final DocumentProcessingService documentProcessingService;
    private final ObjectMapper objectMapper;

    public KnowledgeDocument reviewDocument(String kbId, String docId, DocumentGovernanceUpdateRequest request) {
        KnowledgeDocument document = documentService.getByDocId(docId);
        if (document == null || !kbId.equals(document.getKbId())) {
            throw new IllegalArgumentException("文档不存在");
        }
        Map<String, String> reviewUpdates = new LinkedHashMap<>();
        reviewUpdates.put("review_status", defaultValue(request.getReviewStatus(), "approved"));
        reviewUpdates.put("reviewer", defaultValue(request.getReviewer(), "system"));
        reviewUpdates.put("review_comment", defaultValue(request.getReviewComment(), "reviewed"));
        reviewUpdates.put("version", request.getVersion());
        reviewUpdates.put("publish_date", request.getPublishDate());
        reviewUpdates.put("expires_at", request.getExpiresAt());
        String updatedTags = mergeTags(document.getTags(), reviewUpdates);
        documentService.updateDocumentTags(docId, updatedTags, request.getReviewer());
        if ("approved".equalsIgnoreCase(request.getReviewStatus())) {
            try {
                documentProcessingService.activateDocument(kbId, docId);
            } catch (Exception ignored) {
            }
        }
        if ("rejected".equalsIgnoreCase(request.getReviewStatus())) {
            documentProcessingService.archiveDocument(kbId, docId);
        }
        writeAudit("review", kbId, docId, request);
        return documentService.getByDocId(docId);
    }

    public KnowledgeDocument invalidateDocument(String kbId, String docId, DocumentGovernanceUpdateRequest request) {
        KnowledgeDocument document = documentService.getByDocId(docId);
        if (document == null || !kbId.equals(document.getKbId())) {
            throw new IllegalArgumentException("文档不存在");
        }
        Map<String, String> invalidateUpdates = new LinkedHashMap<>();
        invalidateUpdates.put("invalidated", "true");
        invalidateUpdates.put("invalid_reason", defaultValue(request.getInvalidReason(), "manual_invalidate"));
        invalidateUpdates.put("expires_at", defaultValue(request.getExpiresAt(), LocalDateTime.now().toString()));
        invalidateUpdates.put("replacement_version", defaultValue(request.getReplacementVersion(), ""));
        invalidateUpdates.put("version", request.getVersion());
        invalidateUpdates.put("publish_date", request.getPublishDate());
        String tags = mergeTags(document.getTags(), invalidateUpdates);
        documentService.updateDocumentTags(docId, tags, request.getReviewer());
        documentProcessingService.archiveDocument(kbId, docId);
        writeAudit("invalidate", kbId, docId, request);
        return documentService.getByDocId(docId);
    }

    private String mergeTags(String existingTags, Map<String, String> updates) {
        Map<String, String> keyed = new LinkedHashMap<>();
        Set<String> passthrough = new LinkedHashSet<>();
        if (existingTags != null && !existingTags.isBlank()) {
            for (String rawTag : existingTags.split(",")) {
                String tag = rawTag.trim();
                if (tag.isBlank()) {
                    continue;
                }
                int idx = tag.indexOf(':');
                if (idx > 0) {
                    keyed.put(tag.substring(0, idx), tag.substring(idx + 1));
                } else {
                    passthrough.add(tag);
                }
            }
        }
        updates.forEach((key, value) -> {
            if (value != null && !value.isBlank()) {
                keyed.put(key, value.trim());
            }
        });
        if (updates.containsKey("version") && updates.get("version") != null && !updates.get("version").isBlank()) {
            keyed.put("version", updates.get("version").trim());
        }
        if (updates.containsKey("publish_date") && updates.get("publish_date") != null && !updates.get("publish_date").isBlank()) {
            keyed.put("publish_date", updates.get("publish_date").trim());
        }
        Set<String> merged = new LinkedHashSet<>(passthrough);
        keyed.forEach((key, value) -> merged.add(key + ":" + value));
        return String.join(",", merged);
    }

    private void writeAudit(String action, String kbId, String docId, DocumentGovernanceUpdateRequest request) {
        try {
            Path root = Path.of(storageRoot);
            Files.createDirectories(root);
            Path logFile = root.resolve("document-governance-audit.ndjson");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("timestamp", LocalDateTime.now().toString());
            payload.put("action", action);
            payload.put("kbId", kbId);
            payload.put("docId", docId);
            payload.put("request", request);
            Files.writeString(
                    logFile,
                    objectMapper.writeValueAsString(payload) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("写入文档治理审计失败: {}", e.getMessage());
        }
    }

    private String defaultValue(String first, String fallback) {
        return first != null && !first.isBlank() ? first : fallback;
    }
}
