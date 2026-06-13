package com.lx.governance.aichat.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.springframework.stereotype.Service;

import com.lx.governance.aichat.dto.LayeredMemories;
import com.lx.governance.aichat.dto.MessageSummary;
import com.lx.governance.aichat.dto.RetrievalQuery;
import com.lx.governance.aichat.dto.SessionContext;
import com.lx.governance.aichat.dto.UserProfileDTO;
import com.lx.governance.aichat.util.MessageValidator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class LayeredMemoryOrchestrator {

    private final MemoryService memoryService;
    private final RetrievalQueryRewriteService retrievalQueryRewriteService;
    private final IntentMemoryFilterStrategy intentMemoryFilterStrategy;

    public CompletableFuture<LayeredMemories> loadMemories(
            String groupId,
            String customerId,
            String message,
            SessionContext sessionContext,
            UserProfileDTO userProfile,
            boolean memoryEnabled,
            int retrievalCount) {

        List<String> shortTermMemories = extractShortTermMemories(sessionContext);

        if (!memoryEnabled || retrievalCount == 0) {
            return CompletableFuture.completedFuture(LayeredMemories.builder()
                    .shortTermMemories(shortTermMemories)
                    .longTermMemories(List.of())
                    .build());
        }

        boolean shouldRetrieve = MessageValidator.shouldRetrieveMemories(
                message,
                sessionContext != null ? sessionContext.getComplexity() : null);

        if (!shouldRetrieve) {
            return CompletableFuture.completedFuture(LayeredMemories.builder()
                    .shortTermMemories(shortTermMemories)
                    .longTermMemories(List.of())
                    .build());
        }

        RetrievalQuery retrievalQuery = retrievalQueryRewriteService
                .rewrite(message, sessionContext, userProfile);

        String retrievalText = mergeRetrievalQuery(
                retrievalQuery.getRewrittenQuery(),
                retrievalQuery.getKeywords());

        IntentMemoryFilterStrategy.MemoryFilterDecision filterDecision = mergeFilterDecision(
                intentMemoryFilterStrategy.decide(sessionContext),
                retrievalQuery);

        log.info("长期记忆检索策略 - query: {}, source: {}, reason: {}, keywords: {}, suggestedTypes: {}, suggestedRecentDays: {}, contextDependent: {}, finalTypes: {}, finalRecentDays: {}",
                retrievalText,
                retrievalQuery.getRewriteSource(),
                retrievalQuery.getRewriteReason(),
                retrievalQuery.getKeywords(),
                retrievalQuery.getSuggestedMemoryTypes(),
                retrievalQuery.getSuggestedRecentDays(),
                retrievalQuery.getContextDependent(),
                filterDecision.getMemoryTypes(),
                filterDecision.getRecentDays());

        return memoryService.retrieveMemories(
                        groupId,
                        customerId,
                        retrievalText,
                        filterDecision.getMemoryTypes(),
                        filterDecision.getMinConfidence(),
                        filterDecision.getVerifiedOnly(),
                        filterDecision.getRecentDays())
                .thenApply(longTermMemories -> LayeredMemories.builder()
                        .shortTermMemories(shortTermMemories)
                        .longTermMemories(longTermMemories)
                        .build());
    }

    private IntentMemoryFilterStrategy.MemoryFilterDecision mergeFilterDecision(
            IntentMemoryFilterStrategy.MemoryFilterDecision base,
            RetrievalQuery retrievalQuery) {
        if (base == null) {
            return IntentMemoryFilterStrategy.MemoryFilterDecision.builder().build();
        }
        if (retrievalQuery == null) {
            return base;
        }

        Set<String> mergedMemoryTypes = mergeMemoryTypes(base.getMemoryTypes(), retrievalQuery.getSuggestedMemoryTypes());
        Integer mergedRecentDays = mergeRecentDays(
                base.getRecentDays(),
                retrievalQuery.getSuggestedRecentDays(),
                Boolean.TRUE.equals(retrievalQuery.getContextDependent()));

        return IntentMemoryFilterStrategy.MemoryFilterDecision.builder()
                .memoryTypes(mergedMemoryTypes)
                .minConfidence(base.getMinConfidence())
                .verifiedOnly(base.getVerifiedOnly())
                .recentDays(mergedRecentDays)
                .strategyName(base.getStrategyName())
                .build();
    }

    private Set<String> mergeMemoryTypes(Set<String> base, Set<String> suggested) {
        if (suggested == null || suggested.isEmpty()) {
            return base;
        }
        if (base == null || base.isEmpty()) {
            return suggested;
        }

        Set<String> intersection = new LinkedHashSet<>(base);
        intersection.retainAll(suggested);
        if (!intersection.isEmpty()) {
            return intersection;
        }
        return base;
    }

    private Integer mergeRecentDays(Integer base, Integer suggested, boolean contextDependent) {
        if (suggested == null || suggested <= 0) {
            suggested = null;
        }

        Integer merged;
        if (base == null || base <= 0) {
            merged = suggested;
        } else if (suggested == null) {
            merged = base;
        } else {
            merged = Math.min(base, suggested);
        }

        if (contextDependent) {
            if (merged == null || merged <= 0) {
                return 30;
            }
            return Math.min(merged, 30);
        }
        return merged;
    }

    private String mergeRetrievalQuery(String rewrittenQuery, List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return rewrittenQuery;
        }
        LinkedHashSet<String> segments = new LinkedHashSet<>();
        if (rewrittenQuery != null && !rewrittenQuery.isBlank()) {
            segments.add(rewrittenQuery.trim());
        }
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank() && !containsIgnoreCase(rewrittenQuery, keyword)) {
                segments.add(keyword.trim());
            }
            if (segments.size() >= 4) {
                break;
            }
        }
        return String.join(" ", segments).trim();
    }

    private boolean containsIgnoreCase(String text, String token) {
        if (text == null || token == null) {
            return false;
        }
        return text.toLowerCase().contains(token.toLowerCase());
    }

    List<String> extractShortTermMemories(SessionContext sessionContext) {
        List<String> shortTermMemories = new ArrayList<>();
        if (sessionContext == null) {
            return shortTermMemories;
        }

        if (sessionContext.getCurrentTopic() != null && !sessionContext.getCurrentTopic().isBlank()) {
            shortTermMemories.add("当前话题: " + sessionContext.getCurrentTopic());
        }
        if (sessionContext.getUserIntent() != null && !sessionContext.getUserIntent().isBlank()) {
            shortTermMemories.add("用户意图: " + sessionContext.getUserIntent());
        }
        if (sessionContext.getPendingQuestions() != null) {
            sessionContext.getPendingQuestions().stream()
                    .filter(question -> question != null && !question.isBlank())
                    .limit(2)
                    .forEach(question -> shortTermMemories.add("待确认: " + question));
        }
        if (sessionContext.getRecentSummaries() != null) {
            int start = Math.max(0, sessionContext.getRecentSummaries().size() - 3);
            for (int i = start; i < sessionContext.getRecentSummaries().size(); i++) {
                MessageSummary summary = sessionContext.getRecentSummaries().get(i);
                if (summary != null && summary.getUserMessage() != null && !summary.getUserMessage().isBlank()) {
                    shortTermMemories.add("最近对话: " + summary.getUserMessage());
                }
            }
        }

        return shortTermMemories;
    }
}
