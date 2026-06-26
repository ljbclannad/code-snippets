package com.lx.governance.knowledgebase.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.Builder;
import lombok.Value;

@Service
public class MedicalQueryUnderstandingService {

    private static final List<String> EMERGENCY_TERMS = List.of(
            "胸痛", "胸闷", "呼吸困难", "喘不上气", "黑蒙", "晕厥", "濒死感", "冷汗", "剧烈头痛");
    private static final List<String> MEDICATION_TERMS = List.of(
            "用药", "药", "服药", "剂量", "副作用", "不良反应", "禁忌", "格华止", "二甲双胍");
    private static final List<String> FOLLOW_UP_TERMS = List.of(
            "随访", "复诊", "复查", "最近", "近3天", "近一周", "记录", "监测", "趋势");
    private static final List<String> ADVICE_TERMS = List.of(
            "怎么", "如何", "建议", "能不能", "可以吗", "饮食", "运动", "安排", "处理");
    private static final List<String> LAB_TEST_TERMS = List.of(
            "糖化", "hba1c", "血糖", "ldl", "低密度", "血压", "指标", "检查");
    private static final List<String> CHRONIC_DISEASE_TERMS = List.of(
            "高血压", "糖尿病", "肥胖", "减重", "慢病");

    public QueryUnderstanding understand(String query) {
        String safeQuery = query == null ? "" : query.trim();
        QueryType queryType = classify(safeQuery);
        Set<String> expansions = new LinkedHashSet<>();
        expansions.addAll(expansionTermsFor(queryType));
        expansions.addAll(domainExpansionsFor(safeQuery));
        String rewrittenQuery = appendTerms(safeQuery, expansions);
        return QueryUnderstanding.builder()
                .queryType(queryType)
                .rewrittenQuery(rewrittenQuery)
                .expansionTerms(List.copyOf(expansions))
                .build();
    }

    QueryType classify(String query) {
        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, EMERGENCY_TERMS)) {
            return QueryType.EMERGENCY_RISK;
        }
        if (containsAny(normalized, MEDICATION_TERMS)) {
            return QueryType.MEDICATION_SAFETY;
        }
        if (containsAny(normalized, FOLLOW_UP_TERMS)) {
            return QueryType.FOLLOW_UP_RECOMMENDATION;
        }
        if (containsAny(normalized, ADVICE_TERMS)) {
            return QueryType.ADVICE_QUERY;
        }
        return QueryType.FACT_QUERY;
    }

    private List<String> expansionTermsFor(QueryType queryType) {
        return switch (queryType) {
            case FACT_QUERY -> List.of("定义", "风险", "处理");
            case ADVICE_QUERY -> List.of("处理建议", "生活方式", "注意事项");
            case EMERGENCY_RISK -> List.of("红旗症状", "分诊", "就医建议");
            case FOLLOW_UP_RECOMMENDATION -> List.of("随访", "监测", "下一步");
            case MEDICATION_SAFETY -> List.of("适应症", "用法", "禁忌", "注意事项");
        };
    }

    private List<String> domainExpansionsFor(String query) {
        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT);
        Set<String> expansions = new LinkedHashSet<>();
        if (containsAny(normalized, LAB_TEST_TERMS)) {
            expansions.addAll(List.of("检查指标", "含义", "风险", "下一步"));
        }
        if (containsAny(normalized, CHRONIC_DISEASE_TERMS)) {
            expansions.addAll(List.of("生活方式", "随访", "监测"));
        }
        if (normalized.contains("症状") || normalized.contains("头晕") || normalized.contains("胸痛")) {
            expansions.addAll(List.of("疾病", "分诊", "处理建议"));
        }
        return List.copyOf(expansions);
    }

    private boolean containsAny(String query, List<String> terms) {
        return terms.stream().anyMatch(term -> query.contains(term.toLowerCase(Locale.ROOT)));
    }

    private String appendTerms(String query, Set<String> expansions) {
        if (query == null || query.isBlank() || expansions.isEmpty()) {
            return query;
        }
        StringBuilder builder = new StringBuilder(query.trim());
        for (String expansion : expansions) {
            if (!builder.toString().contains(expansion)) {
                builder.append(' ').append(expansion);
            }
        }
        return builder.toString();
    }

    public enum QueryType {
        FACT_QUERY,
        ADVICE_QUERY,
        EMERGENCY_RISK,
        FOLLOW_UP_RECOMMENDATION,
        MEDICATION_SAFETY
    }

    @Value
    @Builder
    public static class QueryUnderstanding {
        QueryType queryType;
        String rewrittenQuery;
        List<String> expansionTerms;
    }
}
