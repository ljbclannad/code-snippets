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
            public String rewrite(String message, SessionContext sessionContext, UserProfileDTO userProfile, String baselineQuery) {
                return "减重管理 饮食建议 晚饭怎么吃";
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
    }

    @Test
    void shouldFallbackToRuleWhenLlmRewriteIsInvalid() {
        ContextBudgetProperties properties = new ContextBudgetProperties();
        RetrievalRewriteClient rewriteClient = new RetrievalRewriteClient(null, properties) {
            @Override
            public String rewrite(String message, SessionContext sessionContext, UserProfileDTO userProfile, String baselineQuery) {
                return "建议你晚饭少吃点";
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
}
