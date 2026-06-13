package com.lx.governance.knowledgebase.service;

import com.lx.governance.knowledgebase.dto.QueryRewriteResult;
import com.lx.governance.knowledgebase.dto.RerankItem;
import com.lx.governance.knowledgebase.dto.RerankResult;
import com.lx.governance.knowledgebase.dto.SearchRequest;
import com.lx.governance.knowledgebase.dto.SearchResult;
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
            return candidates.stream().limit(topK).toList();
        }

        try {
            RerankResult rerankResult = deepSeekKnowledgeService.rerank(originalQuery, candidates);
            if (rerankResult == null || rerankResult.getItems() == null || rerankResult.getItems().isEmpty()) {
                return candidates.stream().limit(topK).toList();
            }

            Map<String, SearchResult> candidateMap = candidates.stream()
                    .collect(Collectors.toMap(SearchResult::getChunkId, result -> result, (left, right) -> left, LinkedHashMap::new));

            return rerankResult.getItems().stream()
                    .map(item -> applyRerank(candidateMap.get(item.getChunkId()), item))
                    .filter(java.util.Objects::nonNull)
                    .limit(topK)
                    .toList();
        } catch (Exception e) {
            log.warn("DeepSeek rerank 失败，回退原排序: {}", e.getMessage());
            return candidates.stream().limit(topK).toList();
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
}
