package com.lx.governance.knowledgebase.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.knowledgebase.dto.LocalBatchImportRequest;
import com.lx.governance.knowledgebase.dto.LocalBatchImportResponse;
import com.lx.governance.knowledgebase.dto.UploadDocumentRequest;
import com.lx.governance.knowledgebase.entity.KnowledgeDocument;

class LocalDocumentBatchImportServiceTest {

    @Test
    void shouldImportMarkdownFilesFromManifestWithNormalizedMetadata() throws Exception {
        Path tempDir = Files.createTempDirectory("kb-local-import-test");
        Path markdown = tempDir.resolve("hypertension-guide.md");
        Files.writeString(markdown, "# 高血压运动管理\n\n建议每周进行中等强度有氧运动。\n");
        Path manifest = tempDir.resolve("manifest.json");
        Files.writeString(manifest, """
                {
                  "manifestVersion": "1.0",
                  "libraryName": "medical-seed-library",
                  "documents": [
                    {
                      "path": "hypertension-guide.md",
                      "title": "高血压运动管理",
                      "tags": ["慢病管理", "运动"],
                      "businessDomain": "chronic_disease",
                      "source": "seed_library",
                      "applicableScope": "doctor_assistant",
                      "trustLevel": "HIGH",
                      "documentType": "guideline",
                      "disease": "hypertension",
                      "population": "adult",
                      "region": "中国",
                      "version": "2024",
                      "publishDate": "2024-05-01"
                    }
                  ]
                }
                """);

        DocumentProcessingService processingService = mock(DocumentProcessingService.class);
        KnowledgeDocument imported = new KnowledgeDocument();
        imported.setDocId("doc-001");
        imported.setStatus("ACTIVE");
        when(processingService.uploadDocument(eq("kb-001"), any())).thenReturn(imported);

        LocalDocumentBatchImportService service = new LocalDocumentBatchImportService(
                processingService,
                new MedicalMetadataNormalizer(),
                new ObjectMapper());

        LocalBatchImportRequest request = new LocalBatchImportRequest();
        request.setManifestPath(manifest.toString());
        request.setDryRun(false);
        request.setCreatedBy("seed-import");

        LocalBatchImportResponse response = service.importFromManifest("kb-001", request);

        assertEquals(1, response.getImportedDocuments());
        assertEquals(0, response.getSkippedDocuments());
        assertEquals("ACTIVE", response.getItems().get(0).getStatus());
        assertEquals("doc-001", response.getItems().get(0).getDocId());

        org.mockito.ArgumentCaptor<UploadDocumentRequest> captor =
                org.mockito.ArgumentCaptor.forClass(UploadDocumentRequest.class);
        verify(processingService).uploadDocument(eq("kb-001"), captor.capture());
        UploadDocumentRequest upload = captor.getValue();
        assertEquals("高血压运动管理", upload.getTitle());
        assertTrue(upload.getTags().contains("doc_type:指南"));
        assertTrue(upload.getTags().contains("disease:高血压"));
        assertEquals("chronic_disease", upload.getBusinessDomain());
        assertEquals("doctor_assistant", upload.getApplicableScope());
        assertEquals("HIGH", upload.getTrustLevel());
    }
}
