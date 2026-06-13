package com.lx.governance.knowledgebase.service;

import com.lx.governance.knowledgebase.dto.SemanticDedupResult;
import com.lx.governance.knowledgebase.entity.KnowledgeDocument;
import com.lx.governance.knowledgebase.entity.KnowledgeDocumentParseResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentSemanticDedupService {

    @Value("${knowledge-base.document.semantic-dedup-threshold:0.9}")
    private double semanticDedupThreshold;

    private final DocumentService documentService;
    private final DocumentParseResultService parseResultService;
    private final DeepSeekKnowledgeService deepSeekKnowledgeService;

    public SemanticDedupResult detectDuplicate(KnowledgeDocument currentDocument, String currentSummary) {
        List<KnowledgeDocument> candidates = documentService.listActiveByKbId(currentDocument.getKbId(), currentDocument.getDocId(), 10);
        if (candidates.isEmpty()) {
            return null;
        }

        if (!deepSeekKnowledgeService.isConfigured()) {
            return null;
        }

        for (KnowledgeDocument candidate : candidates) {
            KnowledgeDocumentParseResult parseResult = parseResultService.getByDocId(candidate.getDocId());
            String candidateSummary = parseResult != null ? parseResult.getSummary() : candidate.getTitle();

            try {
                SemanticDedupResult result = deepSeekKnowledgeService.detectDuplicate(
                        currentDocument,
                        currentSummary,
                        candidate,
                        candidateSummary
                );
                if (result != null && result.isDuplicate() && result.getScore() >= semanticDedupThreshold) {
                    result.setMatchedDocId(candidate.getDocId());
                    return result;
                }
            } catch (Exception e) {
                log.warn("DeepSeek 语义去重失败，跳过候选文档 {}: {}", candidate.getDocId(), e.getMessage());
            }
        }

        return null;
    }
}
