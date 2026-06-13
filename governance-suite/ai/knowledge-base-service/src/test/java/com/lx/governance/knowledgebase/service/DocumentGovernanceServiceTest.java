package com.lx.governance.knowledgebase.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.knowledgebase.domain.DocumentChunk;
import com.lx.governance.knowledgebase.entity.KnowledgeBase;
import com.lx.governance.knowledgebase.entity.KnowledgeDocument;
import com.lx.governance.knowledgebase.entity.KnowledgeDocumentChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.*;

class DocumentGovernanceServiceTest {

    @Test
    void archiveActiveDocumentDeletesVectorsAndMarksArchived() throws Exception {
        DocumentService documentService = mock(DocumentService.class);
        KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
        SemanticChunkingService chunkingService = mock(SemanticChunkingService.class);
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        DocumentParseResultService parseResultService = mock(DocumentParseResultService.class);
        DocumentChunkService documentChunkService = mock(DocumentChunkService.class);
        DocumentMetadataExtractionService metadataExtractionService = mock(DocumentMetadataExtractionService.class);
        DocumentSemanticDedupService semanticDedupService = mock(DocumentSemanticDedupService.class);

        DocumentProcessingService service = new DocumentProcessingService(
                List.of(),
                documentService,
                knowledgeBaseService,
                chunkingService,
                vectorStoreService,
                parseResultService,
                documentChunkService,
                metadataExtractionService,
                semanticDedupService,
                new ObjectMapper()
        );

        KnowledgeBase kb = new KnowledgeBase();
        kb.setKbId("kb_001");
        kb.setMilvusCollection("kb_kb_001");

        KnowledgeDocument document = new KnowledgeDocument();
        document.setKbId("kb_001");
        document.setDocId("doc_001");
        document.setStatus("ACTIVE");
        document.setChunkCount(2);

        when(documentService.getByDocId("doc_001")).thenReturn(document);
        when(knowledgeBaseService.getByKbId("kb_001")).thenReturn(kb);

        service.archiveDocument("kb_001", "doc_001");

        verify(vectorStoreService).deleteChunks("kb_kb_001", List.of("doc_001_0", "doc_001_1"));
        verify(documentChunkService).markArchived("doc_001");
        verify(documentService).archiveDocument("doc_001");
        verify(knowledgeBaseService).decrementDocumentCount("kb_001");
        verify(knowledgeBaseService).decrementChunkCount("kb_001", 2);
    }

    @Test
    void activateArchivedDocumentRebuildsVectorsAndMarksActive() {
        DocumentService documentService = mock(DocumentService.class);
        KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
        SemanticChunkingService chunkingService = mock(SemanticChunkingService.class);
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        DocumentParseResultService parseResultService = mock(DocumentParseResultService.class);
        DocumentChunkService documentChunkService = mock(DocumentChunkService.class);
        DocumentMetadataExtractionService metadataExtractionService = mock(DocumentMetadataExtractionService.class);
        DocumentSemanticDedupService semanticDedupService = mock(DocumentSemanticDedupService.class);

        DocumentProcessingService service = new DocumentProcessingService(
                List.of(),
                documentService,
                knowledgeBaseService,
                chunkingService,
                vectorStoreService,
                parseResultService,
                documentChunkService,
                metadataExtractionService,
                semanticDedupService,
                new ObjectMapper()
        );

        KnowledgeBase kb = new KnowledgeBase();
        kb.setKbId("kb_001");
        kb.setMilvusCollection("kb_kb_001");

        KnowledgeDocument document = new KnowledgeDocument();
        document.setKbId("kb_001");
        document.setDocId("doc_001");
        document.setTitle("标题");
        document.setTags("标签");
        document.setBusinessDomain("medical");
        document.setSource("manual");
        document.setApplicableScope("scope");
        document.setTrustLevel("HIGH");
        document.setStatus("ARCHIVED");
        document.setChunkCount(1);

        KnowledgeDocumentChunk chunkRow = new KnowledgeDocumentChunk();
        chunkRow.setChunkId("doc_001_0");
        chunkRow.setDocId("doc_001");
        chunkRow.setChunkIndex(0);
        chunkRow.setContent("内容");
        chunkRow.setTitle("标题");
        chunkRow.setTags("标签");
        chunkRow.setBusinessDomain("medical");
        chunkRow.setSource("manual");
        chunkRow.setApplicableScope("scope");
        chunkRow.setTrustLevel("HIGH");

        DocumentChunk chunk = DocumentChunk.builder()
                .chunkId("doc_001_0")
                .docId("doc_001")
                .chunkIndex(0)
                .content("内容")
                .metadata(java.util.Map.of("title", "标题"))
                .build();

        when(documentService.getByDocId("doc_001")).thenReturn(document);
        when(knowledgeBaseService.getByKbId("kb_001")).thenReturn(kb);
        when(documentChunkService.listByDocId("doc_001")).thenReturn(List.of(chunkRow));
        when(documentChunkService.toDomainChunks("doc_001")).thenReturn(List.of(chunk));

        service.activateDocument("kb_001", "doc_001");

        verify(documentChunkService).markActive("doc_001");
        verify(documentService).activateDocument("doc_001", 1);
        verify(vectorStoreService).insertChunks(eq("kb_kb_001"), anyList());
        verify(knowledgeBaseService).incrementDocumentCount("kb_001");
        verify(knowledgeBaseService).updateChunkCount("kb_001", 1);
    }
}
