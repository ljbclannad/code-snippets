package com.lx.governance.knowledgebase.service;

import com.lx.governance.knowledgebase.domain.ParsedDocument;
import com.lx.governance.knowledgebase.dto.MetadataExtractionResult;
import com.lx.governance.knowledgebase.entity.KnowledgeDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentMetadataExtractionService {

    private final DeepSeekKnowledgeService deepSeekKnowledgeService;

    public MetadataExtractionResult extract(KnowledgeDocument document, ParsedDocument parsedDocument) {
        String content = parsedDocument.getParagraphs().stream()
                .map(paragraph -> paragraph.getContent().trim())
                .filter(text -> !text.isEmpty())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");

        if (!deepSeekKnowledgeService.isConfigured()) {
            return fallback(document, content);
        }

        try {
            MetadataExtractionResult result = deepSeekKnowledgeService.extractMetadata(document, content);
            if (result == null || result.getSummary() == null || result.getSummary().isBlank()) {
                return fallback(document, content);
            }
            return result;
        } catch (Exception e) {
            log.warn("DeepSeek 元数据抽取失败，回退到本地规则: {}", e.getMessage());
            return fallback(document, content);
        }
    }

    private MetadataExtractionResult fallback(KnowledgeDocument document, String content) {
        MetadataExtractionResult result = new MetadataExtractionResult();
        result.setSummary(content.length() > 300 ? content.substring(0, 300) : content);
        result.setKeywords(List.of(document.getTitle(), document.getTags(), document.getFileName()));
        result.setSuggestedTags(Arrays.stream(document.getTags().split(","))
                .map(String::trim)
                .filter(tag -> !tag.isBlank())
                .toList());
        result.setExtendedMetadata(Map.of(
                "topic", document.getBusinessDomain(),
                "audience", document.getApplicableScope(),
                "qualityHint", document.getTrustLevel()
        ));
        return result;
    }
}
