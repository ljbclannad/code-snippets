package com.lx.governance.aichat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.lx.governance.aichat.client.RetrievalRewriteClient;
import com.lx.governance.aichat.config.ContextBudgetProperties;
import com.lx.governance.aichat.dto.MessageSummary;
import com.lx.governance.aichat.dto.RetrievalQuery;
import com.lx.governance.aichat.dto.RetrievalRewriteResult;
import com.lx.governance.aichat.dto.SessionContext;
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
    void shouldUseLlmRewriteForComplexConversation() {
        ContextBudgetProperties properties = new ContextBudgetProperties();
        RetrievalRewriteClient rewriteClient = new RetrievalRewriteClient(null, properties) {
            @Override
            public RetrievalRewriteResult rewriteStructured(String message, SessionContext sessionContext, UserProfileDTO userProfile, String baselineQuery) {
                return RetrievalRewriteResult.builder()
                        .rewrittenQuery("减重管理 饮食建议 晚饭怎么吃")
                        .keywords(List.of("减重管理", "晚饭"))
                        .suggestedMemoryTypes(new java.util.LinkedHashSet<>(List.of("DIET", "PREFERENCE")))
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
        assertEquals(30, query.getSuggestedRecentDays());
        assertTrue(Boolean.TRUE.equals(query.getContextDependent()));
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
                .build();

        RetrievalQuery query = llmService.rewrite("那我这种情况晚饭怎么吃？", sessionContext, null);

        assertEquals("FALLBACK_RULE", query.getRewriteSource());
        assertEquals("减重管理 饮食建议 那我这种情况晚饭怎么吃", query.getRewrittenQuery());
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
                .build();

        RetrievalQuery query = llmService.rewrite("那我这种情况晚饭怎么吃？", sessionContext, null);

        assertEquals("LLM", query.getRewriteSource());
        assertTrue(query.getKeywords().isEmpty());
    }
}
