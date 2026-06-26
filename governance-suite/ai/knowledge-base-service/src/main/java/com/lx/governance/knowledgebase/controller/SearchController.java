package com.lx.governance.knowledgebase.controller;

import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.knowledgebase.dto.SearchRequest;
import com.lx.governance.knowledgebase.dto.SearchResponse;
import com.lx.governance.knowledgebase.dto.SearchResult;
import com.lx.governance.knowledgebase.entity.KnowledgeBase;
import com.lx.governance.knowledgebase.service.DocumentChunkService;
import com.lx.governance.knowledgebase.service.KnowledgeSearchEnhancementService;
import com.lx.governance.knowledgebase.service.KnowledgeBaseService;
import com.lx.governance.knowledgebase.service.MedicalRetrievalProfileService;
import com.lx.governance.knowledgebase.service.MedicalRetrievalAuditService;
import com.lx.governance.knowledgebase.service.VectorStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

/**
 * 语义检索控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/kb/{kbId}/search")
@RequiredArgsConstructor
public class SearchController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final VectorStoreService vectorStoreService;
    private final DocumentChunkService documentChunkService;
    private final KnowledgeSearchEnhancementService searchEnhancementService;
    private final MedicalRetrievalProfileService retrievalProfileService;
    private final MedicalRetrievalAuditService retrievalAuditService;

    /**
     * 语义检索
     */
    @PostMapping
    public ApiResponse<SearchResponse> search(
            @PathVariable String kbId,
            @Validated @RequestBody SearchRequest request) {

        log.info("语义检索: kbId={}, query={}, topK={}", kbId, request.getQuery(), request.getTopK());

        // 查询知识库
        KnowledgeBase kb = knowledgeBaseService.getByKbId(kbId);
        if (kb == null) {
            return ApiResponse.fail("KB_NOT_FOUND", "知识库不存在", null);
        }

        String collectionName = kb.getMilvusCollection();

        // 检查集合是否存在
        if (!vectorStoreService.hasCollection(collectionName)) {
            log.warn("集合不存在: {}", collectionName);
            SearchResponse response = SearchResponse.builder()
                    .success(true)
                    .results(List.of())
                    .totalCount(0)
                    .build();
            return ApiResponse.ok(response, null);
        }

        SearchRequest normalizedRequest = searchEnhancementService.normalizeRequest(request);
        normalizedRequest = retrievalProfileService.applyProfile(normalizedRequest);
        SearchRequest effectiveRequest = searchEnhancementService.rewriteIfNeeded(normalizedRequest);

        // 执行检索
        List<SearchResult> vectorResults = List.of();
        boolean vectorSearchFailed = false;
        try {
            vectorResults = vectorStoreService.search(collectionName, effectiveRequest);
        } catch (Exception e) {
            vectorSearchFailed = true;
            log.warn("向量检索失败，回退 keyword recall: kbId={}, query={}, message={}",
                    kbId, effectiveRequest.getQuery(), e.getMessage());
        }

        boolean shouldRunKeywordSearch = Boolean.TRUE.equals(effectiveRequest.getHybridSearch()) || vectorSearchFailed;
        List<SearchResult> keywordResults = List.of();
        List<SearchResult> faqResults = List.of();
        List<SearchResult> lexiconResults = List.of();
        if (shouldRunKeywordSearch) {
            try {
                keywordResults = documentChunkService.keywordSearch(kbId, effectiveRequest, effectiveRequest.getTopK());
                faqResults = documentChunkService.faqExactSearch(kbId, effectiveRequest, Math.min(3, effectiveRequest.getTopK()));
                lexiconResults = documentChunkService.lexiconRecallSearch(kbId, effectiveRequest, effectiveRequest.getTopK());
                if (vectorSearchFailed) {
                    keywordResults = markKeywordFallback(keywordResults);
                }
            } catch (Exception e) {
                log.warn("关键词检索失败: kbId={}, query={}, message={}",
                        kbId, effectiveRequest.getQuery(), e.getMessage());
            }
        }
        List<SearchResult> mergedResults = mergeResults(
                vectorResults,
                keywordResults,
                faqResults,
                lexiconResults,
                effectiveRequest.getTopK());
        mergedResults = retrievalProfileService.applyPostFilters(effectiveRequest, mergedResults);
        List<SearchResult> results = Boolean.TRUE.equals(request.getRerank())
                ? searchEnhancementService.rerankIfNeeded(request.getQuery(), mergedResults, request.getTopK())
                : mergedResults;

        retrievalAuditService.recordSearch(kbId, effectiveRequest, results, null, false, null);

        SearchResponse response = SearchResponse.builder()
                .success(true)
                .results(results)
                .totalCount(results.size())
                .build();

        return ApiResponse.ok(response, null);
    }

    private List<SearchResult> mergeResults(List<SearchResult> vectorResults,
                                            List<SearchResult> keywordResults,
                                            List<SearchResult> faqResults,
                                            List<SearchResult> lexiconResults,
                                            int topK) {
        vectorResults = vectorResults != null ? vectorResults : List.of();
        keywordResults = keywordResults != null ? keywordResults : List.of();
        faqResults = faqResults != null ? faqResults : List.of();
        lexiconResults = lexiconResults != null ? lexiconResults : List.of();
        Map<String, SearchResult> merged = new LinkedHashMap<>();

        vectorResults.forEach(result -> merged.put(result.getChunkId(), result));
        keywordResults.forEach(result -> merged.merge(result.getChunkId(), result, (left, right) -> {
            if (left.getScore() >= right.getScore()) {
                left.setHitReason("hybrid");
                return left;
            }
            right.setHitReason("hybrid");
            return right;
        }));
        faqResults.forEach(result -> merged.merge(result.getChunkId(), result, (left, right) ->
                chooseByScore(left, right, "faq_exact")));
        lexiconResults.forEach(result -> merged.merge(result.getChunkId(), result, (left, right) ->
                chooseByScore(left, right, "lexicon_hybrid")));

        return merged.values().stream()
                .sorted((left, right) -> Double.compare(right.getScore(), left.getScore()))
                .limit(topK)
                .toList();
    }

    private SearchResult chooseByScore(SearchResult left, SearchResult right, String mergedHitReason) {
        if (left.getScore() >= right.getScore()) {
            left.setHitReason(mergedHitReason);
            return left;
        }
        right.setHitReason(mergedHitReason);
        return right;
    }

    private List<SearchResult> markKeywordFallback(List<SearchResult> keywordResults) {
        if (keywordResults == null || keywordResults.isEmpty()) {
            return List.of();
        }
        List<SearchResult> fallbackResults = new ArrayList<>(keywordResults.size());
        for (SearchResult result : keywordResults) {
            if (result == null) {
                continue;
            }
            result.setHitReason("keyword_fallback");
            fallbackResults.add(result);
        }
        return fallbackResults;
    }
}
