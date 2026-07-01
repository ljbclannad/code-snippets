package com.lx.governance.knowledgebase.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.knowledgebase.dto.SearchRequest;
import com.lx.governance.knowledgebase.dto.SearchResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class MedicalRetrievalAuditService {

    @Value("${knowledge-base.audit.storage-root:data/medical-audit}")
    private String storageRoot;

    private final ObjectMapper objectMapper;

    public void recordSearch(
            String kbId,
            SearchRequest request,
            List<SearchResult> results,
            String answer,
            boolean escalatedToAgent,
            String escalationReason) {
        try {
            Path root = Path.of(storageRoot);
            Files.createDirectories(root);
            Path logFile = root.resolve("retrieval-log.ndjson");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("timestamp", LocalDateTime.now().toString());
            payload.put("kbId", kbId);
            payload.put("query", request != null ? request.getQuery() : null);
            payload.put("retrievalProfile", request != null ? request.getRetrievalProfile() : null);
            payload.put("trustLevel", request != null ? request.getTrustLevel() : null);
            payload.put("resultCount", results != null ? results.size() : 0);
            payload.put("results", summarizeResults(results));
            payload.put("finalAnswer", answer);
            payload.put("escalatedToAgent", escalatedToAgent);
            payload.put("escalationReason", escalationReason);
            Files.writeString(
                    logFile,
                    objectMapper.writeValueAsString(payload) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("记录检索审计日志失败: {}", e.getMessage());
        }
    }

    private List<Map<String, Object>> summarizeResults(List<SearchResult> results) {
        if (results == null) {
            return List.of();
        }
        return results.stream().map(result -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("docId", result.getDocId());
            item.put("chunkId", result.getChunkId());
            item.put("title", result.getTitle());
            item.put("source", result.getSource());
            item.put("trustLevel", result.getTrustLevel());
            item.put("score", result.getScore());
            item.put("hitReason", result.getHitReason());
            return item;
        }).toList();
    }
}
