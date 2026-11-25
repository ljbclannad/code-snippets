package com.lx.governance.aichat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.lx.governance.aichat.client.RetrievalRewriteClient;
import com.lx.governance.aichat.config.ContextBudgetProperties;
import com.lx.governance.aichat.context.DynamicBudgetStrategy;
import com.lx.governance.aichat.context.PromptBudgetPolicy;
import com.lx.governance.aichat.dto.LayeredMemories;
import com.lx.governance.aichat.dto.MemoryItemDTO;
import com.lx.governance.aichat.dto.MessageSummary;
import com.lx.governance.aichat.dto.RetrievalQuery;
import com.lx.governance.aichat.dto.RetrievalRewriteResult;
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
        verify(memoryService, never()).retrieveMemories(anyString(), anyString(), any(StructuredMemoryQuery.class));
        verify(retrievalQueryRewriteService, never()).rewrite(anyString(), any(), any());
    }

    @Test
    void shouldRetainEvidenceHitsWhileReducingModelRewritesOnSyntheticCorpus() {
        Map<String, String> corpus = Map.of(
                "血压", "最近一次血压记录",
                "体重波动", "近两周体重和睡眠记录",
                "平台期", "连续数周体重稳定的随访记录",
                "旅行", "无关的旅行记录");
        // 用固定词项匹配模拟召回，比较查询和调用策略，无需访问模型或向量库。
        when(memoryService.retrieveMemories(anyString(), anyString(), any(StructuredMemoryQuery.class)))
                .thenAnswer(invocation -> {
                    StructuredMemoryQuery query = invocation.getArgument(2);
                    List<MemoryItemDTO> matches = corpus.entrySet().stream()
                            .filter(entry -> query.getSemanticQuery().contains(entry.getKey()))
                            .map(entry -> {
                                MemoryItemDTO memory = new MemoryItemDTO();
                                memory.setContent(entry.getValue());
                                memory.setSimilarity(0.9);
                                return memory;
                            })
                            .toList();
                    return CompletableFuture.completedFuture(matches);
                });

        ContextBudgetProperties rulesOnly = new ContextBudgetProperties();
        rulesOnly.getMemory().getRewrite().setEnabled(false);
        RetrievalRewriteClient disabledClient = syntheticRewriteClient();
        assertEquals(List.of(true, true, false), recallEvidence(rulesOnly, disabledClient));
        verifyNoInteractions(disabledClient);

        ContextBudgetProperties complexRewrite = new ContextBudgetProperties();
        complexRewrite.getMemory().getRewrite().setComplexOnly(true);
        RetrievalRewriteClient broadClient = syntheticRewriteClient();
        assertEquals(List.of(true, true, true), recallEvidence(complexRewrite, broadClient));
        verify(broadClient, times(3)).rewriteStructured(anyString(), any(), any(), anyString());

        ContextBudgetProperties selectiveRewrite = new ContextBudgetProperties();
        RetrievalRewriteClient selectiveClient = syntheticRewriteClient();
        assertEquals(List.of(true, true, true), recallEvidence(selectiveRewrite, selectiveClient));
        verify(selectiveClient).rewriteStructured(eq("还是没怎么掉"), any(), any(), anyString());
        verify(selectiveClient, times(1)).rewriteStructured(anyString(), any(), any(), anyString());
    }

    private RetrievalRewriteClient syntheticRewriteClient() {
        RetrievalRewriteClient client = mock(RetrievalRewriteClient.class);
        when(client.rewriteStructured(anyString(), any(), any(), anyString()))
                .thenAnswer(invocation -> {
                    String message = invocation.getArgument(0);
                    return RetrievalRewriteResult.builder()
                            .rewrittenQuery("还是没怎么掉".equals(message) ? "减重管理 体重 平台期" : message)
                            .build();
                });
        return client;
    }

    private List<Boolean> recallEvidence(ContextBudgetProperties properties, RetrievalRewriteClient client) {
        LayeredMemoryOrchestrator testedOrchestrator = new LayeredMemoryOrchestrator(
                memoryService, new RetrievalQueryRewriteService(properties, client), intentMemoryFilterStrategy,
                memorySelfQueryPlanner, new PromptBudgetPolicy(properties, new DynamicBudgetStrategy()));
        List<RecallCase> cases = List.of(
                new RecallCase("血压高怎么办", "complex", "最近一次血压记录"),
                new RecallCase("请分析近两周体重波动和睡眠变化", "complex", "近两周体重和睡眠记录"),
                new RecallCase("还是没怎么掉", "simple", "连续数周体重稳定的随访记录"));
        return cases.stream().map(testCase -> {
            SessionContext context = SessionContext.builder()
                    .currentTopic("减重管理")
                    .complexity(testCase.complexity())
                    .recentSummaries(List.of(MessageSummary.builder().userMessage("近两周体重没有变化").build()))
                    .build();
            return testedOrchestrator.loadMemories("group-1", "customer-1", testCase.message(), context,
                            new UserProfileDTO(), true, 5).join().getLongTermMemories().stream()
                    .anyMatch(memory -> testCase.expectedEvidence().equals(memory.getContent()));
        }).toList();
    }

    private record RecallCase(String message, String complexity, String expectedEvidence) {}

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
        // 让第三个候选在默认预算提高后仍能触发裁剪。
        keywordHigherSimilarity.setContent("关键词记忆".repeat(360));
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
