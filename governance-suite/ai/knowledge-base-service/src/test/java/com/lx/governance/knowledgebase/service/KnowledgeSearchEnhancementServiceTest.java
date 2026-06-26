package com.lx.governance.knowledgebase.service;

import com.lx.governance.knowledgebase.dto.QueryRewriteResult;
import com.lx.governance.knowledgebase.dto.RerankItem;
import com.lx.governance.knowledgebase.dto.RerankResult;
import com.lx.governance.knowledgebase.dto.SearchRequest;
import com.lx.governance.knowledgebase.dto.SearchResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class KnowledgeSearchEnhancementServiceTest {

    private MedicalSearchTermNormalizer newTermNormalizer() {
        return new MedicalSearchTermNormalizer(new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private MedicalQueryUnderstandingService newQueryUnderstandingService() {
        return new MedicalQueryUnderstandingService();
    }

    @Test
    void rewriteIfNeededUsesDeepSeekResult() {
        DeepSeekKnowledgeService deepSeekKnowledgeService = mock(DeepSeekKnowledgeService.class);
        when(deepSeekKnowledgeService.isConfigured()).thenReturn(true);

        QueryRewriteResult rewriteResult = new QueryRewriteResult();
        rewriteResult.setRewrittenQuery("高血压 治疗 方案");
        when(deepSeekKnowledgeService.rewriteQuery("高血压怎么治")).thenReturn(rewriteResult);

        KnowledgeSearchEnhancementService service =
                new KnowledgeSearchEnhancementService(
                        deepSeekKnowledgeService,
                        newTermNormalizer(),
                        newQueryUnderstandingService(),
                        new MedicalSearchRerankService(),
                        new SimpleMeterRegistry());

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

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        KnowledgeSearchEnhancementService service =
                new KnowledgeSearchEnhancementService(
                        deepSeekKnowledgeService,
                        newTermNormalizer(),
                        newQueryUnderstandingService(),
                        new MedicalSearchRerankService(),
                        meterRegistry);

        SearchResult first = SearchResult.builder().chunkId("chunk-1").score(0.6).content("a").build();
        SearchResult second = SearchResult.builder().chunkId("chunk-2").score(0.5).content("b").build();

        List<SearchResult> reranked = service.rerankIfNeeded("高血压治疗", List.of(first, second), 2);
        assertEquals("chunk-2", reranked.get(0).getChunkId());
        org.junit.jupiter.api.Assertions.assertTrue(reranked.get(0).getScore() >= 0.8);
        assertEquals(1.0, meterRegistry.get("kb.search.rerank.total")
                .tag("outcome", "hit")
                .counter()
                .count());
        assertEquals(1L, meterRegistry.get("kb.search.rerank.duration")
                .tag("outcome", "hit")
                .timer()
                .count());
    }

    @Test
    void rerankIfNeededRecordsErrorOutcomeWhenRerankFails() {
        DeepSeekKnowledgeService deepSeekKnowledgeService = mock(DeepSeekKnowledgeService.class);
        when(deepSeekKnowledgeService.isConfigured()).thenReturn(true);
        when(deepSeekKnowledgeService.rerank(eq("高血压治疗"), anyList()))
                .thenThrow(new RuntimeException("rerank unavailable"));

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        KnowledgeSearchEnhancementService service =
                new KnowledgeSearchEnhancementService(
                        deepSeekKnowledgeService,
                        newTermNormalizer(),
                        newQueryUnderstandingService(),
                        new MedicalSearchRerankService(),
                        meterRegistry);

        SearchResult first = SearchResult.builder().chunkId("chunk-1").score(0.6).content("a").build();
        SearchResult second = SearchResult.builder().chunkId("chunk-2").score(0.5).content("b").build();

        List<SearchResult> reranked = service.rerankIfNeeded("高血压治疗", List.of(first, second), 1);

        assertEquals(1, reranked.size());
        assertEquals("chunk-1", reranked.get(0).getChunkId());
        assertEquals(1.0, meterRegistry.get("kb.search.rerank.total")
                .tag("outcome", "error")
                .counter()
                .count());
        assertEquals(1L, meterRegistry.get("kb.search.rerank.duration")
                .tag("outcome", "error")
                .timer()
                .count());
    }

    @Test
    void normalizeRequestShouldExpandAliasesIntoCanonicalTerms() {
        DeepSeekKnowledgeService deepSeekKnowledgeService = mock(DeepSeekKnowledgeService.class);
        KnowledgeSearchEnhancementService service =
                new KnowledgeSearchEnhancementService(
                        deepSeekKnowledgeService,
                        newTermNormalizer(),
                        newQueryUnderstandingService(),
                        new MedicalSearchRerankService(),
                        new SimpleMeterRegistry());

        SearchRequest request = new SearchRequest();
        request.setQuery("血压高患者能不能继续运动");
        request.setTitleKeyword("格华止");
        request.setTags(List.of("糖化", "胸口疼"));

        SearchRequest normalized = service.normalizeRequest(request);

        assertEquals("血压高患者能不能继续运动 高血压 处理建议 生活方式 注意事项 检查指标 含义 风险 下一步 随访 监测",
                normalized.getQuery());
        assertEquals("二甲双胍", normalized.getTitleKeyword());
        assertEquals(List.of("糖化血红蛋白", "胸痛"), normalized.getTags());
    }
}
