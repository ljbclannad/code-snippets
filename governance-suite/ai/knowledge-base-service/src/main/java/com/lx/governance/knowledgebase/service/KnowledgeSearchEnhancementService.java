package com.lx.governance.knowledgebase.service;

import com.lx.governance.knowledgebase.dto.QueryRewriteResult;
import com.lx.governance.knowledgebase.dto.RerankItem;
import com.lx.governance.knowledgebase.dto.RerankResult;
import com.lx.governance.knowledgebase.dto.SearchRequest;
import com.lx.governance.knowledgebase.dto.SearchResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeSearchEnhancementService {

    private final DeepSeekKnowledgeService deepSeekKnowledgeService;
    private final MedicalSearchTermNormalizer termNormalizer;
    private final MedicalQueryUnderstandingService queryUnderstandingService;
    private final MedicalSearchRerankService medicalSearchRerankService;
    private final MeterRegistry meterRegistry;

    public SearchRequest normalizeRequest(SearchRequest originalRequest) {
        SearchRequest normalized = termNormalizer.normalizeRequest(originalRequest);
        if (normalized == null || normalized.getQuery() == null || normalized.getQuery().isBlank()) {
            return normalized;
        }
        MedicalQueryUnderstandingService.QueryUnderstanding understanding =
                queryUnderstandingService.understand(normalized.getQuery());
        normalized.setQuery(understanding.getRewrittenQuery());
        return normalized;
    }

    public SearchRequest rewriteIfNeeded(SearchRequest originalRequest) {
        if (!Boolean.TRUE.equals(originalRequest.getUseRewrite()) || !deepSeekKnowledgeService.isConfigured()) {
            return originalRequest;
        }

        try {
            QueryRewriteResult rewriteResult = deepSeekKnowledgeService.rewriteQuery(originalRequest.getQuery());
            if (rewriteResult == null || rewriteResult.getRewrittenQuery() == null || rewriteResult.getRewrittenQuery().isBlank()) {
                return originalRequest;
            }

            SearchRequest rewritten = new SearchRequest();
            rewritten.setQuery(rewriteResult.getRewrittenQuery());
            rewritten.setTopK(originalRequest.getTopK());
            rewritten.setSimilarityThreshold(originalRequest.getSimilarityThreshold());
            rewritten.setRerank(originalRequest.getRerank());
            rewritten.setHybridSearch(originalRequest.getHybridSearch());
            rewritten.setUseRewrite(originalRequest.getUseRewrite());
            rewritten.setTitleKeyword(originalRequest.getTitleKeyword());
            rewritten.setTags(originalRequest.getTags());
            rewritten.setRetrievalProfile(originalRequest.getRetrievalProfile());
            rewritten.setDocumentTypes(originalRequest.getDocumentTypes());
            rewritten.setDiseases(originalRequest.getDiseases());
            rewritten.setPopulations(originalRequest.getPopulations());
            rewritten.setRegions(originalRequest.getRegions());
            rewritten.setSourceTypes(originalRequest.getSourceTypes());
            rewritten.setVersion(originalRequest.getVersion());
            rewritten.setPublishDateFrom(originalRequest.getPublishDateFrom());
            rewritten.setBusinessDomain(originalRequest.getBusinessDomain());
            rewritten.setSource(originalRequest.getSource());
            rewritten.setApplicableScope(originalRequest.getApplicableScope());
            rewritten.setTrustLevel(originalRequest.getTrustLevel());
            rewritten.setDocIds(originalRequest.getDocIds());
            return rewritten;
        } catch (Exception e) {
            log.warn("DeepSeek query rewrite 失败，回退原查询: {}", e.getMessage());
            return originalRequest;
        }
    }

    public List<SearchResult> rerankIfNeeded(String originalQuery, List<SearchResult> candidates, int topK) {
        if (!deepSeekKnowledgeService.isConfigured() || candidates.isEmpty()) {
            recordRerankMetrics(!deepSeekKnowledgeService.isConfigured() ? "skipped_unconfigured" : "skipped_empty_candidates", 0L);
            return medicalSearchRerankService.rerank(buildMinimalRequest(originalQuery), candidates, topK);
        }

        long startNanos = System.nanoTime();
        try {
            RerankResult rerankResult = deepSeekKnowledgeService.rerank(originalQuery, candidates);
            if (rerankResult == null || rerankResult.getItems() == null || rerankResult.getItems().isEmpty()) {
                recordRerankMetrics("empty", startNanos);
                return medicalSearchRerankService.rerank(buildMinimalRequest(originalQuery), candidates, topK);
            }

            Map<String, SearchResult> candidateMap = candidates.stream()
                    .collect(Collectors.toMap(SearchResult::getChunkId, result -> result, (left, right) -> left, LinkedHashMap::new));

            List<SearchResult> reranked = rerankResult.getItems().stream()
                    .map(item -> applyRerank(candidateMap.get(item.getChunkId()), item))
                    .filter(java.util.Objects::nonNull)
                    .toList();
            List<SearchResult> postProcessed = medicalSearchRerankService.rerank(buildMinimalRequest(originalQuery), reranked, topK);
            recordRerankMetrics(postProcessed.isEmpty() ? "empty" : "hit", startNanos);
            return postProcessed;
        } catch (Exception e) {
            recordRerankMetrics("error", startNanos);
            log.warn("DeepSeek rerank 失败，回退原排序: {}", e.getMessage());
            return medicalSearchRerankService.rerank(buildMinimalRequest(originalQuery), candidates, topK);
        }
    }

    private SearchResult applyRerank(SearchResult original, RerankItem item) {
        if (original == null) {
            return null;
        }
        original.setScore(item.getScore());
        original.setHitReason(item.getReason() != null && !item.getReason().isBlank() ? item.getReason() : "rerank");
        return original;
    }

    private void recordRerankMetrics(String outcome, long startNanos) {
        String safeOutcome = outcome == null || outcome.isBlank() ? "unknown" : outcome;
        meterRegistry.counter("kb.search.rerank.total", "outcome", safeOutcome).increment();

        long durationNanos = startNanos > 0L ? Math.max(0L, System.nanoTime() - startNanos) : 0L;
        Timer.builder("kb.search.rerank.duration")
                .description("Knowledge search rerank duration")
                .tag("outcome", safeOutcome)
                .register(meterRegistry)
                .record(durationNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    private SearchRequest buildMinimalRequest(String query) {
        SearchRequest request = new SearchRequest();
        request.setQuery(query);
        return request;
    }
}
