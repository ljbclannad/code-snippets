package com.lx.governance.knowledgebase.controller;

import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.knowledgebase.dto.SearchRequest;
import com.lx.governance.knowledgebase.dto.SearchResponse;
import com.lx.governance.knowledgebase.dto.SearchResult;
import com.lx.governance.knowledgebase.entity.KnowledgeBase;
import com.lx.governance.knowledgebase.service.DocumentChunkService;
import com.lx.governance.knowledgebase.service.KnowledgeSearchEnhancementService;
import com.lx.governance.knowledgebase.service.KnowledgeBaseService;
import com.lx.governance.knowledgebase.service.VectorStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

        SearchRequest effectiveRequest = searchEnhancementService.rewriteIfNeeded(request);

        // 执行检索
        List<SearchResult> vectorResults = vectorStoreService.search(collectionName, effectiveRequest);
        List<SearchResult> keywordResults = Boolean.TRUE.equals(effectiveRequest.getHybridSearch())
                ? documentChunkService.keywordSearch(kbId, effectiveRequest, effectiveRequest.getTopK())
                : List.of();
        List<SearchResult> mergedResults = mergeResults(vectorResults, keywordResults, effectiveRequest.getTopK());
        List<SearchResult> results = Boolean.TRUE.equals(request.getRerank())
                ? searchEnhancementService.rerankIfNeeded(request.getQuery(), mergedResults, request.getTopK())
                : mergedResults;

        SearchResponse response = SearchResponse.builder()
                .success(true)
                .results(results)
                .totalCount(results.size())
                .build();

        return ApiResponse.ok(response, null);
    }

    private List<SearchResult> mergeResults(List<SearchResult> vectorResults,
                                            List<SearchResult> keywordResults,
                                            int topK) {
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

        return merged.values().stream()
                .sorted((left, right) -> Double.compare(right.getScore(), left.getScore()))
                .limit(topK)
                .toList();
    }
}
