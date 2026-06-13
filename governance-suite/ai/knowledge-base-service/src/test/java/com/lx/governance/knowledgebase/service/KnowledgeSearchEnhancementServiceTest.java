package com.lx.governance.knowledgebase.service;

import com.lx.governance.knowledgebase.dto.QueryRewriteResult;
import com.lx.governance.knowledgebase.dto.RerankItem;
import com.lx.governance.knowledgebase.dto.RerankResult;
import com.lx.governance.knowledgebase.dto.SearchRequest;
import com.lx.governance.knowledgebase.dto.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class KnowledgeSearchEnhancementServiceTest {

    @Test
    void rewriteIfNeededUsesDeepSeekResult() {
        DeepSeekKnowledgeService deepSeekKnowledgeService = mock(DeepSeekKnowledgeService.class);
        when(deepSeekKnowledgeService.isConfigured()).thenReturn(true);

        QueryRewriteResult rewriteResult = new QueryRewriteResult();
        rewriteResult.setRewrittenQuery("高血压 治疗 方案");
        when(deepSeekKnowledgeService.rewriteQuery("高血压怎么治")).thenReturn(rewriteResult);

        KnowledgeSearchEnhancementService service = new KnowledgeSearchEnhancementService(deepSeekKnowledgeService);

        SearchRequest request = new SearchRequest();
        request.setQuery("高血压怎么治");
        request.setUseRewrite(true);
        request.setTopK(5);
        request.setHybridSearch(true);

        SearchRequest rewritten = service.rewriteIfNeeded(request);
        assertEquals("高血压 治疗 方案", rewritten.getQuery());
    }

    @Test
    void rerankIfNeededReordersCandidates() {
        DeepSeekKnowledgeService deepSeekKnowledgeService = mock(DeepSeekKnowledgeService.class);
        when(deepSeekKnowledgeService.isConfigured()).thenReturn(true);

        RerankItem item = new RerankItem();
        item.setChunkId("chunk-2");
        item.setScore(0.98);
        item.setReason("最相关");
        RerankResult rerankResult = new RerankResult();
        rerankResult.setItems(List.of(item));
        when(deepSeekKnowledgeService.rerank(eq("高血压治疗"), anyList())).thenReturn(rerankResult);

        KnowledgeSearchEnhancementService service = new KnowledgeSearchEnhancementService(deepSeekKnowledgeService);

        SearchResult first = SearchResult.builder().chunkId("chunk-1").score(0.6).content("a").build();
        SearchResult second = SearchResult.builder().chunkId("chunk-2").score(0.5).content("b").build();

        List<SearchResult> reranked = service.rerankIfNeeded("高血压治疗", List.of(first, second), 2);
        assertEquals("chunk-2", reranked.get(0).getChunkId());
        assertEquals(0.98, reranked.get(0).getScore());
    }
}
