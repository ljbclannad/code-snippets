package com.lx.governance.knowledgebase.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.knowledgebase.dto.RerankResult;
import com.lx.governance.knowledgebase.dto.SearchRequest;
import com.lx.governance.knowledgebase.dto.SearchResult;
import com.lx.governance.knowledgebase.entity.KnowledgeBase;
import com.lx.governance.knowledgebase.service.DocumentChunkService;
import com.lx.governance.knowledgebase.service.KnowledgeBaseService;
import com.lx.governance.knowledgebase.service.KnowledgeSearchEnhancementService;
import com.lx.governance.knowledgebase.service.MedicalMetadataNormalizer;
import com.lx.governance.knowledgebase.service.MedicalQueryUnderstandingService;
import com.lx.governance.knowledgebase.service.MedicalRetrievalAuditService;
import com.lx.governance.knowledgebase.service.MedicalRetrievalProfileService;
import com.lx.governance.knowledgebase.service.MedicalSearchRerankService;
import com.lx.governance.knowledgebase.service.MedicalSearchTermNormalizer;
import com.lx.governance.knowledgebase.service.VectorStoreService;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class SearchControllerDegradationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private KnowledgeBaseService knowledgeBaseService;
    private VectorStoreService vectorStoreService;
    private DocumentChunkService documentChunkService;
    private KnowledgeSearchEnhancementService searchEnhancementService;
    private MedicalRetrievalProfileService retrievalProfileService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        knowledgeBaseService = mock(KnowledgeBaseService.class);
        vectorStoreService = mock(VectorStoreService.class);
        documentChunkService = mock(DocumentChunkService.class);
        searchEnhancementService = mock(KnowledgeSearchEnhancementService.class);
        retrievalProfileService = new MedicalRetrievalProfileService(
                new MedicalMetadataNormalizer());
        when(searchEnhancementService.normalizeRequest(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SearchController controller = new SearchController(
                knowledgeBaseService,
                vectorStoreService,
                documentChunkService,
                searchEnhancementService,
                retrievalProfileService,
                mock(MedicalRetrievalAuditService.class));
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        KnowledgeBase kb = new KnowledgeBase();
        kb.setKbId("kb-1");
        kb.setMilvusCollection("kb_collection_1");
        when(knowledgeBaseService.getByKbId("kb-1")).thenReturn(kb);
        when(vectorStoreService.hasCollection("kb_collection_1")).thenReturn(true);
    }

    @Test
    void shouldFallbackToKeywordRecallWhenVectorSearchFails() throws Exception {
        SearchRequest effectiveRequest = new SearchRequest();
        effectiveRequest.setQuery("高血压运动");
        effectiveRequest.setTopK(3);
        effectiveRequest.setHybridSearch(false);

        when(searchEnhancementService.rewriteIfNeeded(any())).thenReturn(effectiveRequest);
        when(vectorStoreService.search("kb_collection_1", effectiveRequest))
                .thenThrow(new RuntimeException("embedding unavailable"));

        SearchResult keywordResult = SearchResult.builder()
                .chunkId("chunk-k1")
                .docId("doc-1")
                .title("高血压运动建议")
                .content("建议中等强度有氧运动")
                .score(0.55)
                .hitReason("keyword")
                .build();
        when(documentChunkService.keywordSearch("kb-1", effectiveRequest, 3)).thenReturn(List.of(keywordResult));

        SearchRequest request = new SearchRequest();
        request.setQuery("高血压运动");
        request.setTopK(3);
        request.setHybridSearch(false);

        mockMvc.perform(post("/api/kb/{kbId}/search", "kb-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.results[0].chunkId").value("chunk-k1"))
                .andExpect(jsonPath("$.data.results[0].hitReason").value("keyword_fallback"));

        verify(documentChunkService).keywordSearch("kb-1", effectiveRequest, 3);
    }

    @Test
    void shouldReturnHybridRecallOrderWhenRerankFallsBack() throws Exception {
        MedicalSearchTermNormalizer termNormalizer =
                new MedicalSearchTermNormalizer(new com.fasterxml.jackson.databind.ObjectMapper());
        MedicalQueryUnderstandingService queryUnderstandingService = new MedicalQueryUnderstandingService();
        KnowledgeSearchEnhancementService realEnhancementService =
                new KnowledgeSearchEnhancementService(mock(com.lx.governance.knowledgebase.service.DeepSeekKnowledgeService.class),
                        termNormalizer,
                        queryUnderstandingService,
                        new MedicalSearchRerankService(),
                        new SimpleMeterRegistry());
        var deepSeekService = mock(com.lx.governance.knowledgebase.service.DeepSeekKnowledgeService.class);
        when(deepSeekService.isConfigured()).thenReturn(true);
        when(deepSeekService.rerank(eq("高血压治疗"), any())).thenThrow(new RuntimeException("rerank unavailable"));
        realEnhancementService = new KnowledgeSearchEnhancementService(
                deepSeekService,
                termNormalizer,
                queryUnderstandingService,
                new MedicalSearchRerankService(),
                new SimpleMeterRegistry());
        SearchController controller = new SearchController(
                knowledgeBaseService,
                vectorStoreService,
                documentChunkService,
                realEnhancementService,
                retrievalProfileService,
                mock(MedicalRetrievalAuditService.class));
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        SearchResult vectorResult = SearchResult.builder()
                .chunkId("chunk-1")
                .docId("doc-1")
                .title("向量命中")
                .content("vector")
                .score(0.91)
                .hitReason("vector")
                .build();
        SearchResult keywordResult = SearchResult.builder()
                .chunkId("chunk-2")
                .docId("doc-2")
                .title("关键词命中")
                .content("keyword")
                .score(0.83)
                .hitReason("keyword")
                .build();

        when(vectorStoreService.search(eq("kb_collection_1"), any(SearchRequest.class))).thenReturn(List.of(vectorResult));
        when(documentChunkService.keywordSearch(eq("kb-1"), any(SearchRequest.class), eq(2))).thenReturn(List.of(keywordResult));

        SearchRequest request = new SearchRequest();
        request.setQuery("高血压治疗");
        request.setTopK(2);
        request.setHybridSearch(true);
        request.setRerank(true);

        mockMvc.perform(post("/api/kb/{kbId}/search", "kb-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalCount").value(2))
                .andExpect(jsonPath("$.data.results[0].chunkId").value("chunk-1"))
                .andExpect(jsonPath("$.data.results[0].hitReason").value("vector"))
                .andExpect(jsonPath("$.data.results[1].chunkId").value("chunk-2"))
                .andExpect(jsonPath("$.data.results[1].hitReason").value("keyword"));
    }
}
