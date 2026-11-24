package com.lx.governance.aichat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.lx.governance.aichat.client.RetrievalRewriteClient;
import com.lx.governance.aichat.config.ContextBudgetProperties;
import com.lx.governance.aichat.dto.MessageSummary;
import com.lx.governance.aichat.dto.RetrievalQuery;
import com.lx.governance.aichat.dto.RetrievalRewriteResult;
import com.lx.governance.aichat.dto.SessionContext;
import com.lx.governance.aichat.dto.StructuredMemoryQuery;
import com.lx.governance.aichat.dto.UserProfileDTO;

class RetrievalQueryRewriteServiceTest {

    private final RetrievalQueryRewriteService service = new RetrievalQueryRewriteService();

    @Test
    void shouldRewriteContextDependentMessageWithSessionAndProfileSignals() {
        SessionContext sessionContext = SessionContext.builder()
                .currentTopic("减重管理")
                .userIntent("饮食建议")
                .recentSummaries(List.of(
                        MessageSummary.builder().userMessage("最近血糖偏高").build(),
                        MessageSummary.builder().userMessage("晚上总想加餐").build()))
                .build();

        UserProfileDTO profile = new UserProfileDTO();
        profile.setHealthProfile(Map.of("goal", "控制血糖"));

        RetrievalQuery query = service.rewrite("那我这种情况晚饭怎么吃？", sessionContext, profile);

        assertEquals("RULE", query.getRewriteSource());
        assertTrue(query.getRewrittenQuery().contains("减重管理"));
        assertTrue(query.getRewrittenQuery().contains("饮食建议"));
        assertTrue(query.getRewrittenQuery().contains("最近血糖偏高"));
        assertTrue(query.getRewrittenQuery().contains("晚上总想加餐"));
        assertTrue(query.getRewrittenQuery().contains("控制血糖"));
        assertTrue(query.getRewrittenQuery().contains("那我这种情况晚饭怎么吃"));
    }

    @Test
    void shouldBypassWhenOnlyOriginalMessageExists() {
        RetrievalQuery query = service.rewrite("血压高怎么办", null, null);

        assertEquals("BYPASS", query.getRewriteSource());
        assertEquals("血压高怎么办", query.getRewrittenQuery());
    }

    @Test
    void shouldKeepRuleWhenReferenceHasNoPriorMessage() {
        ContextBudgetProperties properties = new ContextBudgetProperties();
        RetrievalRewriteClient rewriteClient = mock(RetrievalRewriteClient.class);
        RetrievalQueryRewriteService rewriteService = new RetrievalQueryRewriteService(properties, rewriteClient);
        SessionContext context = SessionContext.builder()
                .currentTopic("减重管理")
                .userIntent("随访")
                .complexity("complex")
                .recentSummaries(List.of(MessageSummary.builder().userMessage("还是没怎么掉？").build()))
                .build();

        RetrievalQuery query = rewriteService.rewrite("还是没怎么掉", context, null);

        assertEquals("RULE", query.getRewriteSource());
        verifyNoInteractions(rewriteClient);
    }

    @Test
    void shouldKeepCurrentMessageWhenHistoryConsumesRuleQueryBudget() {
        SessionContext context = SessionContext.builder()
                .currentTopic("减重管理")
                .recentSummaries(List.of(MessageSummary.builder().userMessage("合成摘要".repeat(40)).build()))
                .build();

        RetrievalQuery query = service.rewrite("血压高怎么办", context, null);

        assertTrue(query.getRewrittenQuery().endsWith("血压高怎么办"));
        assertTrue(query.getRewrittenQuery().length() <= new ContextBudgetProperties()
                .getMemory().getRewrite().getMaxQueryLength());
    }

    @Test
    void shouldUseLlmRewriteForComplexConversation() {
        ContextBudgetProperties properties = new ContextBudgetProperties();
        RetrievalRewriteClient rewriteClient = new RetrievalRewriteClient(null, properties) {
            @Override
            public RetrievalRewriteResult rewriteStructured(String message, SessionContext sessionContext, UserProfileDTO userProfile, String baselineQuery) {
                return RetrievalRewriteResult.builder()
                        .rewrittenQuery("减重管理 饮食建议 晚饭怎么吃")
                        .keywords(List.of("减重管理", "晚饭"))
                        .suggestedMemoryTypes(new java.util.LinkedHashSet<>(List.of("DIET", "PREFERENCE")))
                        .suggestedMinConfidence(0.8)
                        .suggestedVerifiedOnly(true)
                        .suggestedRecentDays(30)
                        .contextDependent(true)
                        .build();
            }
        };
        RetrievalQueryRewriteService llmService = new RetrievalQueryRewriteService(properties, rewriteClient);

        SessionContext sessionContext = SessionContext.builder()
                .currentTopic("减重管理")
                .userIntent("饮食建议")
                .complexity("complex")
                .recentSummaries(List.of(
                        MessageSummary.builder().userMessage("最近血糖偏高").build()))
                .build();

        RetrievalQuery query = llmService.rewrite("那我这种情况晚饭怎么吃？", sessionContext, null);

        assertEquals("LLM", query.getRewriteSource());
        assertEquals("减重管理 饮食建议 晚饭怎么吃", query.getRewrittenQuery());
        assertEquals(List.of("减重管理", "晚饭"), query.getKeywords());
        assertTrue(query.getSuggestedMemoryTypes().contains("DIET"));
        assertEquals(0.8, query.getSuggestedMinConfidence());
        assertTrue(Boolean.TRUE.equals(query.getSuggestedVerifiedOnly()));
        assertEquals(30, query.getSuggestedRecentDays());
        assertTrue(Boolean.TRUE.equals(query.getContextDependent()));
        assertEquals("减重管理 饮食建议 晚饭怎么吃", query.getStructuredQuery().getSemanticQuery());
        assertTrue(query.getStructuredQuery().getFilter().getMemoryTypes().contains("DIET"));
    }

    @Test
    void shouldCarryNativeFilterExpressionFromLlm() {
        ContextBudgetProperties properties = new ContextBudgetProperties();
        RetrievalRewriteClient rewriteClient = new RetrievalRewriteClient(null, properties) {
            @Override
            public RetrievalRewriteResult rewriteStructured(String message, SessionContext sessionContext, UserProfileDTO userProfile, String baselineQuery) {
                return RetrievalRewriteResult.builder()
                        .rewrittenQuery("减重管理 饮食建议 晚饭怎么吃")
                        .filterExpression(StructuredMemoryQuery.FilterExpression.builder()
                                .operator("AND")
                                .clauses(List.of(
                                        StructuredMemoryQuery.FilterClause.builder()
                                                .field("memoryTypes")
                                                .op("IN")
                                                .values(List.of("DIET", "PREFERENCE"))
                                                .build(),
                                        StructuredMemoryQuery.FilterClause.builder()
                                                .field("recentDays")
                                                .op("LTE")
                                                .values(List.of("30"))
                                                .build()))
                                .build())
                        .contextDependent(true)
                        .build();
            }
        };
        RetrievalQueryRewriteService llmService = new RetrievalQueryRewriteService(properties, rewriteClient);

        SessionContext sessionContext = SessionContext.builder()
                .currentTopic("减重管理")
                .userIntent("饮食建议")
                .complexity("complex")
                .recentSummaries(List.of(MessageSummary.builder().userMessage("最近血糖偏高").build()))
                .build();

        RetrievalQuery query = llmService.rewrite("那我这种情况晚饭怎么吃？", sessionContext, null);

        assertEquals("LLM", query.getRewriteSource());
        assertTrue(query.getStructuredQuery().getFilterExpression() != null);
        assertEquals("AND", query.getStructuredQuery().getFilterExpression().getOperator());
        assertEquals(2, query.getStructuredQuery().getFilterExpression().getClauses().size());
        assertEquals("memoryTypes", query.getStructuredQuery().getFilterExpression().getClauses().get(0).getField());
    }

    @Test
    void shouldFallbackToRuleWhenLlmRewriteIsInvalid() {
        ContextBudgetProperties properties = new ContextBudgetProperties();
        RetrievalRewriteClient rewriteClient = new RetrievalRewriteClient(null, properties) {
            @Override
            public RetrievalRewriteResult rewriteStructured(String message, SessionContext sessionContext, UserProfileDTO userProfile, String baselineQuery) {
                return RetrievalRewriteResult.builder()
                        .rewrittenQuery("建议你晚饭少吃点")
                        .build();
            }
        };
        RetrievalQueryRewriteService llmService = new RetrievalQueryRewriteService(properties, rewriteClient);

        SessionContext sessionContext = SessionContext.builder()
                .currentTopic("减重管理")
                .userIntent("饮食建议")
                .complexity("complex")
                .recentSummaries(List.of(MessageSummary.builder().userMessage("最近血糖偏高").build()))
                .build();

        RetrievalQuery query = llmService.rewrite("那我这种情况晚饭怎么吃？", sessionContext, null);

        assertEquals("FALLBACK_RULE", query.getRewriteSource());
        assertEquals("减重管理 饮食建议 最近血糖偏高 那我这种情况晚饭怎么吃", query.getRewrittenQuery());
        assertFalse(query.getRewrittenQuery().contains("建议你"));
    }

    @Test
    void shouldUseLlmRewriteForContextDependentFollowUpEvenWhenSimple() {
        ContextBudgetProperties properties = new ContextBudgetProperties();
        RetrievalRewriteClient rewriteClient = new RetrievalRewriteClient(null, properties) {
            @Override
            public RetrievalRewriteResult rewriteStructured(String message, SessionContext sessionContext, UserProfileDTO userProfile, String baselineQuery) {
                return RetrievalRewriteResult.builder()
                        .rewrittenQuery("减重管理 饮食建议 晚饭怎么吃")
                        .contextDependent(true)
                        .build();
            }
        };
        RetrievalQueryRewriteService llmService = new RetrievalQueryRewriteService(properties, rewriteClient);

        SessionContext sessionContext = SessionContext.builder()
                .currentTopic("减重管理")
                .userIntent("饮食建议")
                .complexity("simple")
                .recentSummaries(List.of(MessageSummary.builder().userMessage("最近血糖偏高").build()))
                .build();

        RetrievalQuery query = llmService.rewrite("那我这种情况呢？", sessionContext, null);

        assertEquals("LLM", query.getRewriteSource());
        assertEquals("context_signal_rewrite", query.getRewriteReason());
    }

    @Test
    void shouldKeepRuleRewriteForStandaloneSimpleQuestion() {
        ContextBudgetProperties properties = new ContextBudgetProperties();
        RetrievalRewriteClient rewriteClient = new RetrievalRewriteClient(null, properties) {
            @Override
            public RetrievalRewriteResult rewriteStructured(String message, SessionContext sessionContext, UserProfileDTO userProfile, String baselineQuery) {
                return RetrievalRewriteResult.builder()
                        .rewrittenQuery("不应该被调用")
                        .build();
            }
        };
        RetrievalQueryRewriteService llmService = new RetrievalQueryRewriteService(properties, rewriteClient);

        SessionContext sessionContext = SessionContext.builder()
                .currentTopic("健康咨询")
                .userIntent("一般咨询")
                .complexity("simple")
                .build();

        RetrievalQuery query = llmService.rewrite("血压高怎么办", sessionContext, null);

        assertEquals("RULE", query.getRewriteSource());
        assertEquals("健康咨询 一般咨询 血压高怎么办", query.getRewrittenQuery());
    }

    @Test
    void shouldFallbackToPlainStringStructuredResult() {
        ContextBudgetProperties properties = new ContextBudgetProperties();
        RetrievalRewriteClient rewriteClient = new RetrievalRewriteClient(null, properties) {
            @Override
            public RetrievalRewriteResult rewriteStructured(String message, SessionContext sessionContext, UserProfileDTO userProfile, String baselineQuery) {
                return RetrievalRewriteResult.builder()
                        .rewrittenQuery("减重管理 饮食建议 晚饭怎么吃")
                        .build();
            }
        };
        RetrievalQueryRewriteService llmService = new RetrievalQueryRewriteService(properties, rewriteClient);

        SessionContext sessionContext = SessionContext.builder()
                .currentTopic("减重管理")
                .userIntent("饮食建议")
                .complexity("complex")
                .recentSummaries(List.of(MessageSummary.builder().userMessage("最近血糖偏高").build()))
                .build();

        RetrievalQuery query = llmService.rewrite("那我这种情况晚饭怎么吃？", sessionContext, null);

        assertEquals("LLM", query.getRewriteSource());
        assertTrue(query.getKeywords().isEmpty());
    }
}
