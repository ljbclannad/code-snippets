package com.lx.governance.knowledgebase.service;

import com.lx.governance.knowledgebase.entity.KnowledgeBase;
import com.lx.governance.knowledgebase.entity.KnowledgeDocument;
import com.lx.governance.knowledgebase.domain.ParsedDocument;
import com.lx.governance.knowledgebase.dto.MetadataExtractionResult;
import com.lx.governance.knowledgebase.parser.DocumentParser;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DocumentProcessingServiceTest {

    @Test
    void testDeleteCompletedDocumentCleansVectorsAndCounters() throws Exception {
        DocumentService documentService = mock(DocumentService.class);
        KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
        SemanticChunkingService chunkingService = mock(SemanticChunkingService.class);
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        DocumentParseResultService parseResultService = mock(DocumentParseResultService.class);
        DocumentChunkService documentChunkService = mock(DocumentChunkService.class);
        DocumentMetadataExtractionService metadataExtractionService = mock(DocumentMetadataExtractionService.class);
        DocumentSemanticDedupService semanticDedupService = mock(DocumentSemanticDedupService.class);
        ObjectMapper objectMapper = new ObjectMapper();

        DocumentProcessingService service = new DocumentProcessingService(
                List.<DocumentParser>of(),
                documentService,
                knowledgeBaseService,
                chunkingService,
                vectorStoreService,
                parseResultService,
                documentChunkService,
                metadataExtractionService,
                semanticDedupService,
                objectMapper
        );

        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setKbId("kb_001");
        knowledgeBase.setMilvusCollection("kb_kb_001");

        Path tempFile = Files.createTempFile("kb-doc", ".txt");
        KnowledgeDocument document = new KnowledgeDocument();
        document.setKbId("kb_001");
        document.setDocId("doc_001");
        document.setFilePath(tempFile.toString());
        document.setStatus("ACTIVE");
        document.setChunkCount(2);

        when(documentService.getByDocId("doc_001")).thenReturn(document);
        when(knowledgeBaseService.getByKbId("kb_001")).thenReturn(knowledgeBase);

        service.deleteDocument("kb_001", "doc_001");

        verify(vectorStoreService).deleteChunks("kb_kb_001", List.of("doc_001_0", "doc_001_1"));
        verify(documentChunkService, atLeastOnce()).deleteByDocId("doc_001");
        verify(parseResultService).deleteByDocId("doc_001");
        verify(documentService).deleteDocument("doc_001");
        verify(knowledgeBaseService).decrementDocumentCount("kb_001");
        verify(knowledgeBaseService).decrementChunkCount("kb_001", 2);
        assertFalse(Files.exists(tempFile));
    }

    @Test
    void testDeleteFailedDocumentSkipsVectorCleanup() throws Exception {
        DocumentService documentService = mock(DocumentService.class);
        KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
        SemanticChunkingService chunkingService = mock(SemanticChunkingService.class);
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        DocumentParseResultService parseResultService = mock(DocumentParseResultService.class);
        DocumentChunkService documentChunkService = mock(DocumentChunkService.class);
        DocumentMetadataExtractionService metadataExtractionService = mock(DocumentMetadataExtractionService.class);
        DocumentSemanticDedupService semanticDedupService = mock(DocumentSemanticDedupService.class);
        ObjectMapper objectMapper = new ObjectMapper();

        DocumentProcessingService service = new DocumentProcessingService(
                List.<DocumentParser>of(),
                documentService,
                knowledgeBaseService,
                chunkingService,
                vectorStoreService,
                parseResultService,
                documentChunkService,
                metadataExtractionService,
                semanticDedupService,
                objectMapper
        );

        Path tempFile = Files.createTempFile("kb-doc-failed", ".txt");
        KnowledgeDocument document = new KnowledgeDocument();
        document.setKbId("kb_001");
        document.setDocId("doc_failed");
        document.setFilePath(tempFile.toString());
        document.setStatus("FAILED");
        document.setChunkCount(0);

        when(documentService.getByDocId("doc_failed")).thenReturn(document);

        service.deleteDocument("kb_001", "doc_failed");

        verify(vectorStoreService, never()).deleteChunks(anyString(), anyList());
        verify(documentChunkService).deleteByDocId("doc_failed");
        verify(parseResultService).deleteByDocId("doc_failed");
        verify(documentService).deleteDocument("doc_failed");
        verify(knowledgeBaseService, never()).decrementDocumentCount(anyString());
        verify(knowledgeBaseService, never()).decrementChunkCount(anyString(), anyInt());
        assertFalse(Files.exists(tempFile));
    }

    @Test
    void testReprocessFailedDocumentResetsStateAndRunsPipeline() throws Exception {
        DocumentService documentService = mock(DocumentService.class);
        KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
        SemanticChunkingService chunkingService = mock(SemanticChunkingService.class);
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        DocumentParseResultService parseResultService = mock(DocumentParseResultService.class);
        DocumentChunkService documentChunkService = mock(DocumentChunkService.class);
        DocumentMetadataExtractionService metadataExtractionService = mock(DocumentMetadataExtractionService.class);
        DocumentSemanticDedupService semanticDedupService = mock(DocumentSemanticDedupService.class);
        ObjectMapper objectMapper = new ObjectMapper();

        DocumentParser parser = mock(DocumentParser.class);
        when(parser.supports("txt")).thenReturn(true);

        DocumentProcessingService service = new DocumentProcessingService(
                List.of(parser),
                documentService,
                knowledgeBaseService,
                chunkingService,
                vectorStoreService,
                parseResultService,
                documentChunkService,
                metadataExtractionService,
                semanticDedupService,
                objectMapper
        );

        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setKbId("kb_001");
        knowledgeBase.setMilvusCollection("kb_kb_001");

        Path tempFile = Files.createTempFile("kb-reprocess", ".txt");
        KnowledgeDocument document = new KnowledgeDocument();
        document.setKbId("kb_001");
        document.setDocId("doc_retry");
        document.setFileType("txt");
        document.setFilePath(tempFile.toString());
        document.setStatus("FAILED");
        document.setRetryCount(0);
        document.setTitle("标题");
        document.setTags("标签");
        document.setBusinessDomain("medical");
        document.setSource("manual");
        document.setApplicableScope("scope");
        document.setTrustLevel("HIGH");

        ParsedDocument parsedDocument = ParsedDocument.builder()
                .docId("doc_retry")
                .fileName("retry.txt")
                .fileType("txt")
                .paragraphs(List.of())
                .build();
        MetadataExtractionResult extractionResult = new MetadataExtractionResult();
        extractionResult.setSummary("summary");
        extractionResult.setKeywords(List.of("k1"));
        extractionResult.setSuggestedTags(List.of("s1"));
        extractionResult.setExtendedMetadata(Map.of("topic", "t"));

        when(documentService.getByDocId("doc_retry")).thenReturn(document);
        when(knowledgeBaseService.getByKbId("kb_001")).thenReturn(knowledgeBase);
        when(parser.parse(any(), eq("doc_retry"))).thenReturn(parsedDocument);
        when(chunkingService.chunkDocument(eq(parsedDocument), any(), any(), any())).thenReturn(List.of());
        when(metadataExtractionService.extract(eq(document), eq(parsedDocument))).thenReturn(extractionResult);
        when(semanticDedupService.detectDuplicate(eq(document), eq("summary"))).thenReturn(null);

        service.reprocessDocument("kb_001", "doc_retry");

        verify(documentService).incrementRetryCount("doc_retry");
        verify(documentService).prepareForReprocess("doc_retry");
        verify(documentService, atLeastOnce()).updateProcessingStage(eq("doc_retry"), anyString(), anyInt());
        verify(documentService).updateStatusToActive("doc_retry", 0);
        verify(parseResultService).saveSuccessResult(eq("doc_retry"), eq("summary"), eq("k1"), eq("s1"), anyString(), eq(null));
    }
}
