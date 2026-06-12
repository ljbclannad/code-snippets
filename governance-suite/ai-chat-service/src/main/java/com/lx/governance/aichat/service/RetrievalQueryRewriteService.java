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
import com.lx.governance.aichat.dto.SessionContext;
import com.lx.governance.aichat.dto.UserProfileDTO;

@Service
public class RetrievalQueryRewriteService {

    private static final int MAX_SUMMARY_COUNT = 2;

    private ContextBudgetProperties properties = new ContextBudgetProperties();

    private RetrievalRewriteClient retrievalRewriteClient;

    public RetrievalQueryRewriteService() {
    }

    public RetrievalQueryRewriteService(
            ContextBudgetProperties properties,
            RetrievalRewriteClient retrievalRewriteClient) {
        if (properties != null) {
            this.properties = properties;
        }
        this.retrievalRewriteClient = retrievalRewriteClient;
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

    public RetrievalQuery rewrite(
            String message,
            SessionContext sessionContext,
            UserProfileDTO userProfile) {

        String normalizedMessage = normalize(message);
        if (normalizedMessage.isEmpty()) {
            return RetrievalQuery.builder()
                    .originalMessage(message)
                    .rewrittenQuery("")
                    .rewriteSource("BYPASS")
                    .rewriteReason("empty_message")
                    .build();
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

        if (!shouldUseLlmRewrite(sessionContext)) {
            return RetrievalQuery.builder()
                    .originalMessage(message)
                    .rewrittenQuery(ruleQuery)
                    .rewriteSource(source)
                    .rewriteReason(reason)
                    .build();
        }

        String llmQuery = retrievalRewriteClient != null
                ? retrievalRewriteClient.rewrite(message, sessionContext, userProfile, ruleQuery)
                : "";
        if (!isValidLlmRewrite(llmQuery, normalizedMessage, ruleQuery)) {
            return RetrievalQuery.builder()
                    .originalMessage(message)
                    .rewrittenQuery(ruleQuery)
                    .rewriteSource("FALLBACK_RULE")
                    .rewriteReason("llm_invalid_or_empty")
                    .build();
        }

        return RetrievalQuery.builder()
                .originalMessage(message)
                .rewrittenQuery(llmQuery)
                .rewriteSource("LLM")
                .rewriteReason("complex_context_rewrite")
                .build();
    }

    private boolean shouldUseLlmRewrite(SessionContext sessionContext) {
        if (!properties.getMemory().getRewrite().isEnabled()) {
            return false;
        }
        if (retrievalRewriteClient == null) {
            return false;
        }
        if (!properties.getMemory().getRewrite().isComplexOnly()) {
            return true;
        }
        return sessionContext != null && "complex".equalsIgnoreCase(sessionContext.getComplexity());
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

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("[\\s,，。！？!?.；;：:]+", " ").trim();
    }
}
