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
import com.lx.governance.aichat.dto.UserProfileDTO;
import com.lx.governance.aichat.metrics.AiChatMetrics;

@Service
public class RetrievalQueryRewriteService {

    private static final int MAX_SUMMARY_COUNT = 2;
    private static final String[] FOLLOW_UP_MARKERS = {
            "那", "这个", "这种", "这样", "刚才", "上面", "前面", "继续", "还能", "需要调整", "可以吗", "怎么办", "怎么做"
    };
    private static final String[] REFERENTIAL_PATTERNS = {
            "我这种情况", "我这个情况", "那我", "那这种", "这个情况", "上次说的", "刚刚说的", "前面提到的"
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

        List<String> parts = new ArrayList<>();
        appendIfPresent(parts, sessionContext != null ? sessionContext.getCurrentTopic() : null);
        appendIfPresent(parts, sessionContext != null ? sessionContext.getUserIntent() : null);
        appendRecentSummaries(parts, sessionContext);
        appendCoreProfile(parts, userProfile);
        appendIfPresent(parts, normalizedMessage);

        String ruleQuery = joinAndTrim(parts);
        String source = ruleQuery.equals(normalizedMessage) ? "BYPASS" : "RULE";
        String reason = source.equals("RULE") ? "context_enriched" : "original_message_sufficient";

        if (!shouldUseLlmRewrite(normalizedMessage, sessionContext, ruleQuery)) {
            RetrievalQuery result = RetrievalQuery.builder()
                    .originalMessage(message)
                    .rewrittenQuery(ruleQuery)
                    .rewriteSource(source)
                    .rewriteReason(reason)
                    .build();
            recordRewriteMetrics(result, startTime);
            return result;
        }

        RetrievalRewriteResult rewriteResult = retrievalRewriteClient != null
                ? retrievalRewriteClient.rewriteStructured(message, sessionContext, userProfile, ruleQuery)
                : RetrievalRewriteResult.builder().rewrittenQuery("").build();
        String llmQuery = rewriteResult.getRewrittenQuery();
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
                .rewriteReason(determineLlmReason(normalizedMessage, sessionContext))
                .keywords(rewriteResult.getKeywords())
                .suggestedMemoryTypes(rewriteResult.getSuggestedMemoryTypes())
                .suggestedRecentDays(rewriteResult.getSuggestedRecentDays())
                .contextDependent(rewriteResult.getContextDependent())
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

    private boolean shouldUseLlmRewrite(String normalizedMessage, SessionContext sessionContext, String ruleQuery) {
        if (!properties.getMemory().getRewrite().isEnabled()) {
            return false;
        }
        if (retrievalRewriteClient == null) {
            return false;
        }
        if (!properties.getMemory().getRewrite().isComplexOnly()
                && !properties.getMemory().getRewrite().isContextualSignalsEnabled()) {
            return true;
        }
        if (sessionContext != null && "complex".equalsIgnoreCase(sessionContext.getComplexity())) {
            return true;
        }
        if (!properties.getMemory().getRewrite().isContextualSignalsEnabled()) {
            return false;
        }
        return isContextDependentFollowUp(normalizedMessage, sessionContext, ruleQuery);
    }

    private boolean isContextDependentFollowUp(String normalizedMessage, SessionContext sessionContext, String ruleQuery) {
        if (sessionContext == null) {
            return false;
        }

        boolean hasSessionSignals = !normalize(sessionContext.getCurrentTopic()).isEmpty()
                || !normalize(sessionContext.getUserIntent()).isEmpty()
                || (sessionContext.getRecentSummaries() != null && !sessionContext.getRecentSummaries().isEmpty());
        if (!hasSessionSignals) {
            return false;
        }
        boolean hasHistorySignals = (sessionContext.getRecentSummaries() != null && !sessionContext.getRecentSummaries().isEmpty())
                || (sessionContext.getPendingQuestions() != null && !sessionContext.getPendingQuestions().isEmpty())
                || (sessionContext.getMentionedEntities() != null && !sessionContext.getMentionedEntities().isEmpty());

        int threshold = properties.getMemory().getRewrite().getShortMessageThreshold();
        boolean isShortMessage = normalizedMessage.length() <= threshold;
        boolean hasFollowUpMarker = containsAny(normalizedMessage, FOLLOW_UP_MARKERS);
        boolean hasReferentialPattern = containsAny(normalizedMessage, REFERENTIAL_PATTERNS);
        boolean ruleNeedsContext = !normalize(ruleQuery).equals(normalizedMessage);

        return (isShortMessage && hasFollowUpMarker && hasHistorySignals)
                || hasReferentialPattern
                || (isShortMessage && ruleNeedsContext && hasHistorySignals);
    }

    private String determineLlmReason(String normalizedMessage, SessionContext sessionContext) {
        if (sessionContext != null && "complex".equalsIgnoreCase(sessionContext.getComplexity())) {
            return "complex_context_rewrite";
        }
        return "context_signal_rewrite";
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

    private String joinAndTrim(List<String> parts) {
        Set<String> deduplicated = new LinkedHashSet<>(parts);
        String joined = String.join(" ", deduplicated).trim();
        int maxQueryLength = properties.getMemory().getRewrite().getMaxQueryLength();
        if (joined.length() <= maxQueryLength) {
            return joined;
        }
        return joined.substring(0, maxQueryLength).trim();
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
