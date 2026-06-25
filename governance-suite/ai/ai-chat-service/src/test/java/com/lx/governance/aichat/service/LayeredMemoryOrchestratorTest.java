package com.lx.governance.aichat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.lx.governance.aichat.config.ContextBudgetProperties;
import com.lx.governance.aichat.context.DynamicBudgetStrategy;
import com.lx.governance.aichat.context.PromptBudgetPolicy;
import com.lx.governance.aichat.dto.LayeredMemories;
import com.lx.governance.aichat.dto.MemoryItemDTO;
import com.lx.governance.aichat.dto.MessageSummary;
import com.lx.governance.aichat.dto.RetrievalQuery;
import com.lx.governance.aichat.dto.SessionContext;
import com.lx.governance.aichat.dto.StructuredMemoryQuery;
import com.lx.governance.aichat.dto.UserProfileDTO;

class LayeredMemoryOrchestratorTest {

    @Mock
    private MemoryService memoryService;
    @Mock
    private RetrievalQueryRewriteService retrievalQueryRewriteService;
    @Mock
    private IntentMemoryFilterStrategy intentMemoryFilterStrategy;

    private MemorySelfQueryPlanner memorySelfQueryPlanner;
    private LayeredMemoryOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        memorySelfQueryPlanner = new MemorySelfQueryPlanner(new StructuredMemoryQueryValidator());
        ContextBudgetProperties properties = new ContextBudgetProperties();
        orchestrator = new LayeredMemoryOrchestrator(
                memoryService,
                retrievalQueryRewriteService,
                intentMemoryFilterStrategy,
                memorySelfQueryPlanner,
                new PromptBudgetPolicy(properties, new DynamicBudgetStrategy()));
        when(intentMemoryFilterStrategy.decide(any())).thenReturn(IntentMemoryFilterStrategy.MemoryFilterDecision.builder()
                .memoryTypes(java.util.Set.of("DIET"))
                .minConfidence(0.7)
                .verifiedOnly(false)
                .recentDays(90)
                .strategyName("test")
                .build());
    }

    @Test
    void shouldReturnShortTermMemoriesWithoutLongTermRecallWhenRetrievalSkipped() throws Exception {
        SessionContext context = SessionContext.builder()
                .currentTopic("减重管理")
                .userIntent("饮食建议")
                .complexity("simple")
                .recentSummaries(List.of(MessageSummary.builder().userMessage("最近血糖偏高").build()))
                .build();

        LayeredMemories memories = orchestrator.loadMemories(
                "group-1",
                "customer-1",
                "我头疼",
                context,
                new UserProfileDTO(),
                true,
                5).get();

        assertEquals(3, memories.getShortTermMemories().size());
        assertTrue(memories.getLongTermMemories().isEmpty());
        verify(memoryService, never()).retrieveMemories(anyString(), anyString(), anyString());
    }

    @Test
    void shouldUseRewriteForLongTermRecallAndKeepShortTermMemories() throws Exception {
        SessionContext context = SessionContext.builder()
                .currentTopic("减重管理")
                .userIntent("饮食建议")
                .complexity("complex")
                .recentSummaries(List.of(MessageSummary.builder().userMessage("最近血糖偏高").build()))
                .build();

        MemoryItemDTO longTerm = new MemoryItemDTO();
        longTerm.setContent("长期记忆");
        longTerm.setSimilarity(0.91);

        when(retrievalQueryRewriteService.rewrite(anyString(), any(), any()))
                .thenReturn(RetrievalQuery.builder()
                        .rewrittenQuery("减重管理 饮食建议 晚饭怎么吃")
                        .keywords(List.of("控糖", "晚饭"))
                        .suggestedMemoryTypes(new java.util.LinkedHashSet<>(List.of("DIET", "PREFERENCE")))
                        .suggestedRecentDays(30)
                        .build());
        when(memoryService.retrieveMemories(eq("group-1"), eq("customer-1"), any(StructuredMemoryQuery.class)))
                .thenReturn(CompletableFuture.completedFuture(List.of(longTerm)));

        LayeredMemories memories = orchestrator.loadMemories(
                "group-1",
                "customer-1",
                "那我这种情况晚饭怎么吃？",
                context,
                new UserProfileDTO(),
                true,
                5).get();

        assertEquals(3, memories.getShortTermMemories().size());
        assertEquals(1, memories.getLongTermMemories().size());
        verify(memoryService).retrieveMemories(eq("group-1"), eq("customer-1"),
                org.mockito.ArgumentMatchers.<StructuredMemoryQuery>argThat(query -> "减重管理 饮食建议 晚饭怎么吃 控糖".equals(query.getSemanticQuery())
                        && query.getFilter() != null
                        && query.getFilterExpression() != null
                        && "AND".equals(query.getFilterExpression().getOperator())
                        && java.util.Set.of("DIET").equals(query.getFilter().getMemoryTypes())
                        && Double.valueOf(0.7).equals(query.getFilter().getMinConfidence())
                        && Integer.valueOf(30).equals(query.getFilter().getRecentDays())));
    }

    @Test
    void shouldKeepIntentTypesWhenRewriteSuggestionsDoNotOverlap() throws Exception {
        SessionContext context = SessionContext.builder()
                .currentTopic("减重管理")
                .userIntent("饮食建议")
                .complexity("complex")
                .build();

        MemoryItemDTO longTerm = new MemoryItemDTO();
        longTerm.setContent("长期记忆");
        longTerm.setSimilarity(0.91);

        when(retrievalQueryRewriteService.rewrite(anyString(), any(), any()))
                .thenReturn(RetrievalQuery.builder()
                        .rewrittenQuery("减重管理 饮食建议 晚饭怎么吃")
                        .suggestedMemoryTypes(new java.util.LinkedHashSet<>(List.of("MEDICATION")))
                        .suggestedRecentDays(120)
                        .build());
        when(memoryService.retrieveMemories(eq("group-1"), eq("customer-1"), any(StructuredMemoryQuery.class)))
                .thenReturn(CompletableFuture.completedFuture(List.of(longTerm)));

        LayeredMemories memories = orchestrator.loadMemories(
                "group-1",
                "customer-1",
                "那我这种情况晚饭怎么吃？",
                context,
                new UserProfileDTO(),
                true,
                5).get();

        assertEquals(1, memories.getLongTermMemories().size());
        verify(memoryService).retrieveMemories(eq("group-1"), eq("customer-1"),
                org.mockito.ArgumentMatchers.<StructuredMemoryQuery>argThat(query -> "减重管理 饮食建议 晚饭怎么吃".equals(query.getSemanticQuery())
                        && java.util.Set.of("DIET").equals(query.getFilter().getMemoryTypes())
                        && Integer.valueOf(90).equals(query.getFilter().getRecentDays())));
    }

    @Test
    void shouldTightenRecentDaysWhenRewriteMarksContextDependent() throws Exception {
        SessionContext context = SessionContext.builder()
                .currentTopic("减重管理")
                .userIntent("饮食建议")
                .complexity("complex")
                .recentSummaries(List.of(MessageSummary.builder().userMessage("最近血糖偏高").build()))
                .build();

        MemoryItemDTO longTerm = new MemoryItemDTO();
        longTerm.setContent("长期记忆");
        longTerm.setSimilarity(0.91);

        when(retrievalQueryRewriteService.rewrite(anyString(), any(), any()))
                .thenReturn(RetrievalQuery.builder()
                        .rewrittenQuery("减重管理 饮食建议 那我这种情况晚饭怎么吃")
                        .contextDependent(true)
                        .build());
        when(memoryService.retrieveMemories(eq("group-1"), eq("customer-1"), any(StructuredMemoryQuery.class)))
                .thenReturn(CompletableFuture.completedFuture(List.of(longTerm)));

        LayeredMemories memories = orchestrator.loadMemories(
                "group-1",
                "customer-1",
                "那我这种情况晚饭怎么吃？",
                context,
                new UserProfileDTO(),
                true,
                5).get();

        assertEquals(1, memories.getLongTermMemories().size());
        verify(memoryService).retrieveMemories(eq("group-1"), eq("customer-1"),
                org.mockito.ArgumentMatchers.<StructuredMemoryQuery>argThat(query -> "减重管理 饮食建议 那我这种情况晚饭怎么吃".equals(query.getSemanticQuery())
                        && Integer.valueOf(30).equals(query.getFilter().getRecentDays())
                        && Boolean.FALSE.equals(query.getFilter().getVerifiedOnly())));
    }

    @Test
    void shouldTrimLongTermMemoriesBySimilarityTypeAndBudget() throws Exception {
        SessionContext context = SessionContext.builder()
                .currentTopic("减重管理")
                .userIntent("饮食建议")
                .complexity("complex")
                .build();

        MemoryItemDTO preferredHigh = new MemoryItemDTO();
        preferredHigh.setContent("饮食记忆".repeat(120));
        preferredHigh.setSimilarity(0.95);
        preferredHigh.setConfidenceScore(0.9);
        preferredHigh.setSource("DIET");

        MemoryItemDTO keywordHigherSimilarity = new MemoryItemDTO();
        keywordHigherSimilarity.setContent("关键词记忆".repeat(220));
        keywordHigherSimilarity.setSimilarity(0.99);
        keywordHigherSimilarity.setConfidenceScore(0.95);
        keywordHigherSimilarity.setSource("KEYWORD");

        MemoryItemDTO preferredSecond = new MemoryItemDTO();
        preferredSecond.setContent("饮食补充");
        preferredSecond.setSimilarity(0.8);
        preferredSecond.setConfidenceScore(0.8);
        preferredSecond.setSource("DIET");

        when(retrievalQueryRewriteService.rewrite(anyString(), any(), any()))
                .thenReturn(RetrievalQuery.builder()
                        .rewrittenQuery("减重管理 饮食建议 晚饭怎么吃")
                        .suggestedMemoryTypes(new java.util.LinkedHashSet<>(List.of("DIET")))
                        .build());
        when(memoryService.retrieveMemories(eq("group-1"), eq("customer-1"), any(StructuredMemoryQuery.class)))
                .thenReturn(CompletableFuture.completedFuture(List.of(keywordHigherSimilarity, preferredSecond, preferredHigh)));

        LayeredMemories memories = orchestrator.loadMemories(
                "group-1",
                "customer-1",
                "那我这种情况晚饭和加餐应该怎么一起调整，才能兼顾控糖和减重？",
                context,
                new UserProfileDTO(),
                true,
                5).get();

        assertEquals(2, memories.getLongTermMemories().size());
        assertEquals("DIET", memories.getLongTermMemories().get(0).getSource());
        assertEquals("DIET", memories.getLongTermMemories().get(1).getSource());
    }
}
