package com.lx.governance.aichat.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.springframework.stereotype.Service;

import com.lx.governance.aichat.dto.LayeredMemories;
import com.lx.governance.aichat.dto.MessageSummary;
import com.lx.governance.aichat.dto.SessionContext;
import com.lx.governance.aichat.dto.UserProfileDTO;
import com.lx.governance.aichat.util.MessageValidator;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class LayeredMemoryOrchestrator {

    private final MemoryService memoryService;
    private final RetrievalQueryRewriteService retrievalQueryRewriteService;

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

        String retrievalQuery = retrievalQueryRewriteService
                .rewrite(message, sessionContext, userProfile)
                .getRewrittenQuery();

        return memoryService.retrieveMemories(groupId, customerId, retrievalQuery)
                .thenApply(longTermMemories -> LayeredMemories.builder()
                        .shortTermMemories(shortTermMemories)
                        .longTermMemories(longTermMemories)
                        .build());
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
