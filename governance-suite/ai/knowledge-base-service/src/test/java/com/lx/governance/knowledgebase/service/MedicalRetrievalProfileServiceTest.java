package com.lx.governance.knowledgebase.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.lx.governance.knowledgebase.dto.SearchRequest;
import com.lx.governance.knowledgebase.dto.SearchResult;

class MedicalRetrievalProfileServiceTest {

    @Test
    void shouldApplyPatientEducationProfileDefaults() {
        MedicalRetrievalProfileService service = new MedicalRetrievalProfileService(new MedicalMetadataNormalizer());

        SearchRequest request = new SearchRequest();
        request.setQuery("家庭血压怎么测");
        request.setRetrievalProfile("patient_education");

        SearchRequest profiled = service.applyProfile(request);

        assertEquals("patient_education", profiled.getApplicableScope());
        assertEquals("MEDIUM", profiled.getTrustLevel());
        assertTrue(profiled.getTags().contains("doc_type:患教材料"));
    }

    @Test
    void shouldAugmentTagsFromMetadataFilters() {
        MedicalRetrievalProfileService service = new MedicalRetrievalProfileService(new MedicalMetadataNormalizer());

        SearchRequest request = new SearchRequest();
        request.setQuery("高血压怎么管理");
        request.setDiseases(List.of("hypertension"));
        request.setDocumentTypes(List.of("guideline"));
        request.setRegions(List.of("中国"));
        request.setVersion("2024");

        SearchRequest profiled = service.applyProfile(request);

        assertTrue(profiled.getTags().contains("disease:高血压"));
        assertTrue(profiled.getTags().contains("doc_type:指南"));
        assertTrue(profiled.getTags().contains("region:CN"));
        assertTrue(profiled.getTags().contains("version:2024"));
    }

    @Test
    void shouldFilterResultsByPublishDateFrom() {
        MedicalRetrievalProfileService service = new MedicalRetrievalProfileService(new MedicalMetadataNormalizer());

        SearchRequest request = new SearchRequest();
        request.setPublishDateFrom("2024-05-01");

        SearchResult older = SearchResult.builder()
                .chunkId("chunk-old")
                .tags("medical,publish_date:2024-04-10")
                .build();
        SearchResult newer = SearchResult.builder()
                .chunkId("chunk-new")
                .tags("medical,publish_date:2024-06-01")
                .build();

        List<SearchResult> filtered = service.applyPostFilters(request, List.of(older, newer));

        assertEquals(1, filtered.size());
        assertEquals("chunk-new", filtered.get(0).getChunkId());
    }
}
