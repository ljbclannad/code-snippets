package com.lx.governance.knowledgebase.service;

import com.lx.governance.knowledgebase.dto.SemanticDedupResult;
import com.lx.governance.knowledgebase.entity.KnowledgeDocument;
import com.lx.governance.knowledgebase.entity.KnowledgeDocumentParseResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;

class DocumentSemanticDedupServiceTest {

    @Test
    void detectDuplicateReturnsMatchedActiveDocument() {
        DocumentService documentService = mock(DocumentService.class);
        DocumentParseResultService parseResultService = mock(DocumentParseResultService.class);
        DeepSeekKnowledgeService deepSeekKnowledgeService = mock(DeepSeekKnowledgeService.class);

        KnowledgeDocument current = new KnowledgeDocument();
        current.setKbId("kb_001");
        current.setDocId("doc_current");
        current.setTitle("高血压治疗指南");
        current.setTags("高血压,治疗");
        current.setBusinessDomain("medical");

        KnowledgeDocument candidate = new KnowledgeDocument();
        candidate.setKbId("kb_001");
        candidate.setDocId("doc_old");
        candidate.setTitle("高血压规范治疗");
        candidate.setTags("高血压,治疗");
        candidate.setBusinessDomain("medical");

        KnowledgeDocumentParseResult parseResult = new KnowledgeDocumentParseResult();
        parseResult.setDocId("doc_old");
        parseResult.setSummary("高血压治疗标准方案");

        SemanticDedupResult dedupResult = new SemanticDedupResult();
        dedupResult.setDuplicate(true);
        dedupResult.setScore(0.95);
        dedupResult.setReason("内容高度重复");

        when(deepSeekKnowledgeService.isConfigured()).thenReturn(true);
        when(documentService.listActiveByKbId("kb_001", "doc_current", 10)).thenReturn(List.of(candidate));
        when(parseResultService.getByDocId("doc_old")).thenReturn(parseResult);
        when(deepSeekKnowledgeService.detectDuplicate(current, "当前摘要", candidate, "高血压治疗标准方案"))
                .thenReturn(dedupResult);

        DocumentSemanticDedupService service = new DocumentSemanticDedupService(documentService, parseResultService, deepSeekKnowledgeService);
        ReflectionTestUtils.setField(service, "semanticDedupThreshold", 0.9);

        SemanticDedupResult result = service.detectDuplicate(current, "当前摘要");
        assertNotNull(result);
        assertEquals("doc_old", result.getMatchedDocId());
    }
}
