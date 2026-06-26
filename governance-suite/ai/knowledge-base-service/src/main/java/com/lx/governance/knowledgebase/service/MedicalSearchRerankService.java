package com.lx.governance.knowledgebase.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;

import com.lx.governance.knowledgebase.dto.SearchRequest;
import com.lx.governance.knowledgebase.dto.SearchResult;

@Service
public class MedicalSearchRerankService {

    public List<SearchResult> rerank(SearchRequest request, List<SearchResult> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<SearchResult> reranked = new ArrayList<>(candidates.size());
        for (SearchResult candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            double score = candidate.getScore() != null ? candidate.getScore() : 0D;
            score += applySceneBoost(request, candidate);
            score += applySafetyBoost(request, candidate);
            score -= applyLowQualityPenalty(candidate);
            candidate.setScore(score);
            reranked.add(candidate);
        }
        reranked.sort(Comparator.comparing(SearchResult::getScore, Comparator.nullsLast(Comparator.reverseOrder())));
        return reranked.stream().limit(topK).toList();
    }

    private double applySceneBoost(SearchRequest request, SearchResult candidate) {
        String scope = normalize(candidate.getApplicableScope());
        String trustLevel = normalize(candidate.getTrustLevel());
        String profile = normalize(request != null ? request.getRetrievalProfile() : null);
        double boost = 0D;
        if ("patient_education".equals(profile) && scope.contains("patient")) {
            boost += 0.12D;
        }
        if ("doctor_workflow".equals(profile) && scope.contains("doctor")) {
            boost += 0.12D;
        }
        if ("high".equals(trustLevel)) {
            boost += 0.08D;
        } else if ("low".equals(trustLevel)) {
            boost -= 0.18D;
        }
        return boost;
    }

    private double applySafetyBoost(SearchRequest request, SearchResult candidate) {
        String query = normalize(request != null ? request.getQuery() : null);
        String content = normalize(candidate.getContent());
        String title = normalize(candidate.getTitle());
        if (query.isBlank()) {
            return 0D;
        }
        boolean medication = containsAny(query, "停药", "减量", "加量", "禁忌", "不良反应", "药");
        boolean emergency = containsAny(query, "胸痛", "呼吸困难", "昏迷", "抽搐", "急诊", "红旗");
        if (medication && containsAny(content + " " + title, "禁忌", "注意事项", "说明书", "剂量", "不良反应")) {
            return 0.16D;
        }
        if (emergency && containsAny(content + " " + title, "急诊", "立即就医", "红旗", "预警")) {
            return 0.18D;
        }
        return 0D;
    }

    private double applyLowQualityPenalty(SearchResult candidate) {
        String content = normalize(candidate.getContent());
        String source = normalize(candidate.getSource());
        int length = content.length();
        double penalty = 0D;
        if (length < 40) {
            penalty += 0.18D;
        } else if (length < 80) {
            penalty += 0.08D;
        }
        if (containsAny(content, "点击展开", "上一篇", "下一篇", "版权所有", "返回顶部")) {
            penalty += 0.15D;
        }
        if (source.contains("crawler") && containsAny(content, "javascript", "cookie", "登录后查看")) {
            penalty += 0.12D;
        }
        return penalty;
    }

    private boolean containsAny(String text, String... patterns) {
        for (String pattern : patterns) {
            if (text.contains(pattern.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String raw) {
        return raw == null ? "" : raw.toLowerCase(Locale.ROOT);
    }
}
