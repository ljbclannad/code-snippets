package com.lx.governance.aichat.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.lx.governance.aichat.client.RetrievalRewriteClient;
import com.lx.governance.aichat.config.ContextBudgetProperties;
import com.lx.governance.aichat.dto.MessageSummary;
import com.lx.governance.aichat.dto.RetrievalQuery;
import com.lx.governance.aichat.dto.RetrievalRewriteResult;
import com.lx.governance.aichat.dto.SessionContext;
import com.lx.governance.aichat.dto.StructuredMemoryQuery;
import com.lx.governance.aichat.dto.UserProfileDTO;
import com.lx.governance.aichat.metrics.AiChatMetrics;

@Service
public class RetrievalQueryRewriteService {

    private static final int MAX_SUMMARY_COUNT = 2;
    private static final String[] SHORT_FOLLOW_UP_PREFIXES = {
            "还是没", "还是不", "仍然没", "依然没", "那接下来", "那怎么办", "那怎么做",
            "那还能", "还能继续", "还可以吗", "可以继续吗", "需要调整吗", "这个呢", "这样呢"
    };
    private static final String[] REFERENTIAL_PATTERNS = {
            "这种情况", "这个情况", "这样吃", "上次说的", "刚才说的", "刚刚说的",
            "前面提到的", "之前说的", "之前的记录", "上次的记录", "结合我的历史"
    };

    private ContextBudgetProperties properties = new ContextBudgetProperties();

    private RetrievalRewriteClient retrievalRewriteClient;
    private AiChatMetrics metrics;

    public RetrievalQueryRewriteService() {
    }

    public RetrievalQueryRewriteService(
            ContextBudgetProperties properties,
            RetrievalRewriteClient retrievalRewriteClient) {
        this(properties, retrievalRewriteClient, null);
    }

    public RetrievalQueryRewriteService(
            ContextBudgetProperties properties,
            RetrievalRewriteClient retrievalRewriteClient,
            AiChatMetrics metrics) {
        if (properties != null) {
            this.properties = properties;
        }
        this.retrievalRewriteClient = retrievalRewriteClient;
        this.metrics = metrics;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setProperties(ContextBudgetProperties properties) {
        if (properties != null) {
            this.properties = properties;
        }
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRetrievalRewriteClient(RetrievalRewriteClient retrievalRewriteClient) {
        this.retrievalRewriteClient = retrievalRewriteClient;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMetrics(AiChatMetrics metrics) {
        this.metrics = metrics;
    }

    public RetrievalQuery rewrite(
            String message,
            SessionContext sessionContext,
            UserProfileDTO userProfile) {
        long startTime = System.currentTimeMillis();

        String normalizedMessage = normalize(message);
        if (normalizedMessage.isEmpty()) {
            RetrievalQuery result = RetrievalQuery.builder()
                    .originalMessage(message)
                    .rewrittenQuery("")
                    .rewriteSource("BYPASS")
                    .rewriteReason("empty_message")
                    .build();
            recordRewriteMetrics(result, startTime);
            return result;
        }

        // 先用确定性规则把当前话题、意图、最近摘要和核心画像字段拼进查询，
        // 目的是为“那我这种情况呢”这类上下文依赖问题补足检索语义，同时避免一上来就走 LLM 重写。
        List<String> parts = new ArrayList<>();
        appendIfPresent(parts, sessionContext != null ? sessionContext.getCurrentTopic() : null);
        appendIfPresent(parts, sessionContext != null ? sessionContext.getUserIntent() : null);
        appendRecentSummaries(parts, sessionContext);
        appendCoreProfile(parts, userProfile);
        appendIfPresent(parts, normalizedMessage);

        String ruleQuery = joinAndTrim(parts, normalizedMessage);
        String source = ruleQuery.equals(normalizedMessage) ? "BYPASS" : "RULE";
        String reason = source.equals("RULE") ? "context_enriched" : "original_message_sufficient";

        String llmReason = determineLlmReason(normalizedMessage, sessionContext);
        if (llmReason == null) {
            RetrievalQuery result = RetrievalQuery.builder()
                    .originalMessage(message)
                    .rewrittenQuery(ruleQuery)
                    .rewriteSource(source)
                    .rewriteReason(reason)
                    .build();
            recordRewriteMetrics(result, startTime);
            return result;
        }

        // 模型以规则查询为基线，仅在满足配置的触发条件时补全检索语义。
        RetrievalRewriteResult rewriteResult = retrievalRewriteClient
                .rewriteStructured(message, sessionContext, userProfile, ruleQuery);
        String llmQuery = rewriteResult == null ? null : rewriteResult.getRewrittenQuery();

        // LLM 重写结果必须通过长度、空值、答案化倾向和语义重叠校验；
        // 一旦不可靠，就立刻回退到 ruleQuery，避免把检索查询改坏。
        if (!isValidLlmRewrite(llmQuery, normalizedMessage, ruleQuery)) {
            RetrievalQuery result = RetrievalQuery.builder()
                    .originalMessage(message)
                    .rewrittenQuery(ruleQuery)
                    .rewriteSource("FALLBACK_RULE")
                    .rewriteReason("llm_invalid_or_empty")
                    .build();
            recordRewriteMetrics(result, startTime);
            return result;
        }

        RetrievalQuery result = RetrievalQuery.builder()
                .originalMessage(message)
                .rewrittenQuery(llmQuery)
                .rewriteSource("LLM")
                .rewriteReason(llmReason)
                .keywords(rewriteResult.getKeywords())
                .suggestedMemoryTypes(rewriteResult.getSuggestedMemoryTypes())
                .suggestedMinConfidence(rewriteResult.getSuggestedMinConfidence())
                .suggestedVerifiedOnly(rewriteResult.getSuggestedVerifiedOnly())
                .suggestedRecentDays(rewriteResult.getSuggestedRecentDays())
                .contextDependent(rewriteResult.getContextDependent())
                .structuredQuery(StructuredMemoryQuery.builder()
                        .originalMessage(message)
                        .semanticQuery(llmQuery)
                        .keywords(rewriteResult.getKeywords())
                        .filter(StructuredMemoryQuery.MemoryFilter.builder()
                                .memoryTypes(rewriteResult.getSuggestedMemoryTypes())
                                .minConfidence(rewriteResult.getSuggestedMinConfidence())
                                .verifiedOnly(rewriteResult.getSuggestedVerifiedOnly())
                                .recentDays(rewriteResult.getSuggestedRecentDays())
                                .build())
                        .filterExpression(rewriteResult.getFilterExpression())
                        .planSource("LLM")
                        .planReason(llmReason)
                        .contextDependent(rewriteResult.getContextDependent())
                        .build())
                .build();
        recordRewriteMetrics(result, startTime);
        return result;
    }

    private void recordRewriteMetrics(RetrievalQuery result, long startTime) {
        if (metrics == null || result == null) {
            return;
        }
        metrics.recordRewrite(
                result.getRewriteSource(),
                result.getRewriteReason(),
                System.currentTimeMillis() - startTime);
    }

    private String determineLlmReason(String normalizedMessage, SessionContext sessionContext) {
        ContextBudgetProperties.RewriteConfig rewrite = properties.getMemory().getRewrite();
        if (!rewrite.isEnabled() || retrievalRewriteClient == null) {
            return null;
        }
        if (rewrite.isComplexOnly() && sessionContext != null
                && "complex".equalsIgnoreCase(sessionContext.getComplexity())) {
            return "complex_context_rewrite";
        }
        if (!rewrite.isContextualSignalsEnabled()) {
            // 保留两个触发门槛都关闭时的全量改写配置。
            return rewrite.isComplexOnly() ? null : "context_signal_rewrite";
        }
        return hasContextReference(normalizedMessage, sessionContext) ? "context_signal_rewrite" : null;
    }

    boolean isContextDependentFollowUp(String message, SessionContext sessionContext) {
        return properties.getMemory().getRewrite().isContextualSignalsEnabled()
                && hasContextReference(normalize(message), sessionContext);
    }

    private boolean hasContextReference(String normalizedMessage, SessionContext sessionContext) {
        if (sessionContext == null || normalizedMessage.isEmpty()) {
            return false;
        }
        List<MessageSummary> summaries = sessionContext.getRecentSummaries();
        if (summaries == null || summaries.isEmpty()) {
            return false;
        }
        int start = Math.max(0, summaries.size() - MAX_SUMMARY_COUNT);
        // 话题和意图可能来自本轮，只有确有不同的近期用户原话才补全指代。
        boolean hasPriorMessage = summaries.subList(start, summaries.size()).stream()
                .filter(summary -> summary != null)
                .map(summary -> normalize(summary.getUserMessage()))
                .anyMatch(summary -> !summary.isEmpty() && !summary.equals(normalizedMessage));
        if (!hasPriorMessage) {
            return false;
        }
        if (containsAny(normalizedMessage, REFERENTIAL_PATTERNS)) {
            return true;
        }
        if (normalizedMessage.length() <= properties.getMemory().getRewrite().getShortMessageThreshold()) {
            for (String prefix : SHORT_FOLLOW_UP_PREFIXES) {
                if (normalizedMessage.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void appendRecentSummaries(List<String> parts, SessionContext sessionContext) {
        if (sessionContext == null || sessionContext.getRecentSummaries() == null) {
            return;
        }
        int start = Math.max(0, sessionContext.getRecentSummaries().size() - MAX_SUMMARY_COUNT);
        for (int i = start; i < sessionContext.getRecentSummaries().size(); i++) {
            MessageSummary summary = sessionContext.getRecentSummaries().get(i);
            if (summary != null) {
                appendIfPresent(parts, summary.getUserMessage());
            }
        }
    }

    private void appendCoreProfile(List<String> parts, UserProfileDTO userProfile) {
        if (userProfile == null) {
            return;
        }
        appendMapValues(parts, userProfile.getHealthProfile(), "disease", "goal", "chronicDisease", "healthGoal");
        appendMapValues(parts, userProfile.getBehaviorPreferences(), "dietPreference", "exerciseFrequency");
    }

    private void appendMapValues(List<String> parts, Map<String, Object> source, String... keys) {
        if (source == null) {
            return;
        }
        for (String key : keys) {
            Object value = source.get(key);
            if (value != null) {
                appendIfPresent(parts, String.valueOf(value));
            }
        }
    }

    private void appendIfPresent(List<String> parts, String value) {
        String normalized = normalize(value);
        if (!normalized.isEmpty()) {
            parts.add(normalized);
        }
    }

    private String joinAndTrim(List<String> parts, String normalizedMessage) {
        Set<String> deduplicated = new LinkedHashSet<>(parts);
        String joined = String.join(" ", deduplicated).trim();
        int maxQueryLength = properties.getMemory().getRewrite().getMaxQueryLength();
        if (joined.length() <= maxQueryLength) {
            return joined;
        }

        // 规则路径优先保留本轮原话，避免历史摘要耗尽检索词预算。
        if (normalizedMessage.length() >= maxQueryLength) {
            return normalizedMessage.substring(0, maxQueryLength).trim();
        }
        deduplicated.remove(normalizedMessage);
        String context = String.join(" ", deduplicated);
        int contextLength = Math.min(context.length(), maxQueryLength - normalizedMessage.length() - 1);
        return (context.substring(0, contextLength).trim() + " " + normalizedMessage).trim();
    }

    private boolean isValidLlmRewrite(String llmQuery, String normalizedMessage, String ruleQuery) {
        String normalized = normalize(llmQuery);
        if (normalized.isEmpty()) {
            return false;
        }
        if (normalized.length() > properties.getMemory().getRewrite().getMaxQueryLength()) {
            return false;
        }
        if (isAnswerLike(normalized)) {
            return false;
        }

        String baseline = normalize(ruleQuery);
        if (baseline.isEmpty()) {
            return normalized.equals(normalizedMessage);
        }
        return hasOverlap(normalized, baseline) || hasOverlap(normalized, normalizedMessage);
    }

    private boolean hasOverlap(String left, String right) {
        if (left.isEmpty() || right.isEmpty()) {
            return false;
        }
        for (String token : right.split(" ")) {
            if (!token.isBlank() && left.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private boolean isAnswerLike(String normalized) {
        return normalized.startsWith("建议你")
                || normalized.startsWith("你可以")
                || normalized.startsWith("可以")
                || normalized.startsWith("应该")
                || normalized.startsWith("需要");
    }

    private boolean containsAny(String normalized, String... patterns) {
        for (String pattern : patterns) {
            if (normalized.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("[\\s,，。！？!?.；;：:]+", " ").trim();
    }
}
