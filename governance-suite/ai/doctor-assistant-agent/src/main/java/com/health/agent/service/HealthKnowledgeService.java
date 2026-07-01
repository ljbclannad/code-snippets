package com.health.agent.service;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import com.health.agent.client.KnowledgeBaseClient;
import com.health.agent.dto.kb.KbSearchResult;
import com.health.agent.mapper.HealthKnowledgeMapper;
import com.health.agent.metrics.AgentMetrics;
import com.health.agent.model.HealthKnowledge;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 健康知识服务。
 * 优先走 knowledge-base-service，失败或无结果时回退本地 health_knowledge 表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HealthKnowledgeService {

    private final HealthKnowledgeMapper healthKnowledgeMapper;
    private final KnowledgeBaseClient knowledgeBaseClient;
    private final AgentMetrics agentMetrics;

    /**
     * 根据主题查询健康知识
     */
    @Cacheable(value = "healthKnowledge", key = "'topic:' + #topic")
    public List<HealthKnowledge> getByTopic(String topic, int limit) {
        log.info("查询健康知识: topic={}, limit={}", topic, limit);
        List<HealthKnowledge> knowledgeList = searchKnowledgeBase(topic, limit);
        if (!knowledgeList.isEmpty()) {
            return knowledgeList;
        }
        List<HealthKnowledge> localResults = healthKnowledgeMapper.findByTopic(topic, limit);
        recordFallbackRetrieval(localResults.size());
        return localResults;
    }

    /**
     * 根据分类查询健康知识
     */
    @Cacheable(value = "healthKnowledge", key = "'category:' + #category")
    public List<HealthKnowledge> getByCategory(String category, int limit) {
        log.info("查询健康知识: category={}, limit={}", category, limit);
        return healthKnowledgeMapper.findByCategory(category, limit);
    }

    /**
     * 全文搜索健康知识
     */
    @Cacheable(value = "healthKnowledge", key = "'search:' + #keyword")
    public List<HealthKnowledge> searchByKeyword(String keyword, int limit) {
        log.info("搜索健康知识: keyword={}, limit={}", keyword, limit);
        List<HealthKnowledge> knowledgeList = searchKnowledgeBase(keyword, limit);
        if (!knowledgeList.isEmpty()) {
            return knowledgeList;
        }
        List<HealthKnowledge> localResults = healthKnowledgeMapper.searchByKeyword(keyword, limit);
        recordFallbackRetrieval(localResults.size());
        return localResults;
    }

    public List<HealthKnowledge> searchMedicalKnowledge(String query, int limit) {
        return searchKnowledgeBase(query, limit, "doctor_workflow", "doctor_assistant", "HIGH", List.of("guideline", "consensus", "web"));
    }

    public List<HealthKnowledge> searchMedicationGuidance(String query, int limit) {
        return searchKnowledgeBase(query, limit, "medication", "doctor_assistant", "HIGH", List.of("drug_label", "guideline"));
    }

    public List<HealthKnowledge> searchFollowUpProtocol(String query, int limit) {
        return searchKnowledgeBase(query, limit, "doctor_workflow", "doctor_assistant", "HIGH", List.of("guideline", "faq", "hospital"));
    }

    public List<HealthKnowledge> searchRedFlagGuideline(String query, int limit) {
        return searchKnowledgeBase(query, limit, "risk_triage", "doctor_assistant", "HIGH", List.of("rule", "guideline", "hospital"));
    }

    private List<HealthKnowledge> searchKnowledgeBase(String query, int limit) {
        return searchKnowledgeBase(query, limit, null, null, null, null);
    }

    private List<HealthKnowledge> searchKnowledgeBase(
            String query,
            int limit,
            String retrievalProfile,
            String applicableScope,
            String trustLevel,
            List<String> sourceTypes) {
        if (query == null || query.isBlank() || !knowledgeBaseClient.isEnabled()) {
            return List.of();
        }
        long startNanos = System.nanoTime();
        try {
            List<HealthKnowledge> knowledgeList = knowledgeBaseClient
                    .searchMedicalKnowledge(query, limit, retrievalProfile, applicableScope, trustLevel, sourceTypes)
                    .stream()
                    .map(this::toHealthKnowledge)
                    .collect(Collectors.toList());
            recordKnowledgeRetrieval("knowledge_base", knowledgeList.isEmpty() ? "empty" : "hit",
                    startNanos, knowledgeList.size());
            return knowledgeList;
        } catch (IOException e) {
            recordKnowledgeRetrieval("knowledge_base", "error", startNanos, 0);
            log.warn("知识库检索失败，回退本地健康知识: query={}, message={}", query, e.getMessage());
            return List.of();
        }
    }

    private void recordKnowledgeRetrieval(String source, String outcome, long startNanos, int resultCount) {
        if (agentMetrics == null) {
            return;
        }
        long durationMs = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
        agentMetrics.recordKnowledgeRetrieval(source, outcome, durationMs, resultCount);
    }

    private void recordFallbackRetrieval(int resultCount) {
        if (agentMetrics == null) {
            return;
        }
        agentMetrics.recordKnowledgeRetrieval("local_db", resultCount > 0 ? "hit" : "empty", 0L, resultCount);
    }

    private HealthKnowledge toHealthKnowledge(KbSearchResult result) {
        return HealthKnowledge.builder()
                .topic(result.getTitle() != null && !result.getTitle().isBlank() ? result.getTitle() : "medical")
                .category(inferCategory(result))
                .title(result.getTitle())
                .content(result.getContent())
                .tags(result.getTags())
                .source(result.getSource())
                .trustLevel(result.getTrustLevel())
                .hitReason(result.getHitReason())
                .score(result.getScore())
                .applicableScope(result.getApplicableScope())
                .build();
    }

    private String inferCategory(KbSearchResult result) {
        String joined = ((result.getTitle() != null ? result.getTitle() : "")
                + " "
                + (result.getTags() != null ? result.getTags() : "")
                + " "
                + (result.getContent() != null ? result.getContent() : ""))
                .toLowerCase(Locale.ROOT);
        if (joined.contains("运动") || joined.contains("exercise")) {
            return HealthKnowledge.CategoryEnum.EXERCISE.name();
        }
        if (joined.contains("药") || joined.contains("medication")) {
            return HealthKnowledge.CategoryEnum.MEDICATION.name();
        }
        if (joined.contains("饮食") || joined.contains("营养") || joined.contains("nutrition")) {
            return HealthKnowledge.CategoryEnum.NUTRITION.name();
        }
        if (joined.contains("生活方式") || joined.contains("作息") || joined.contains("lifestyle")) {
            return HealthKnowledge.CategoryEnum.LIFESTYLE.name();
        }
        return HealthKnowledge.CategoryEnum.DISEASE.name();
    }
}
