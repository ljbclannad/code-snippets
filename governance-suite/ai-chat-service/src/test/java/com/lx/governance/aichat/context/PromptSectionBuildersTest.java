package com.lx.governance.aichat.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.lx.governance.aichat.config.ContextBudgetProperties;
import com.lx.governance.aichat.dto.LayeredMemories;
import com.lx.governance.aichat.dto.MemoryItemDTO;
import com.lx.governance.aichat.dto.MessageSummary;
import com.lx.governance.aichat.dto.SessionContext;
import com.lx.governance.aichat.dto.UserProfileDTO;

class PromptSectionBuildersTest {

    private PromptSectionSupport support;

    @BeforeEach
    void setUp() {
        ContextBudgetProperties properties = new ContextBudgetProperties();
        properties.setCoreProfileBudget(120);
        properties.setRelevantMemoriesBudget(120);
        properties.setConversationHistoryBudget(80);
        properties.setExtendedProfileBudget(80);
        properties.getMemory().setSimilarityThreshold(0.7);

        ConversationHistoryManager historyManager = mock(ConversationHistoryManager.class);
        when(historyManager.getRecentHistory("u1", "conv1", properties.getConversation().getMaxRounds()))
                .thenReturn(List.of());
        when(historyManager.formatHistory(List.of(), 80)).thenReturn("");

        support = new PromptSectionSupport(properties, historyManager);
    }

    @Test
    void coreProfileBuilderShouldBuildIndependentSection() {
        UserProfileDTO profile = new UserProfileDTO();
        profile.setBasicInfo(Map.of("name", "王五", "age", 29));

        PromptSectionResult result = new CoreProfileSectionBuilder(support).build(contextBuilder()
                .input(PromptBuildInput.builder().profile(profile).build())
                .remainingBudget(200)
                .build());

        assertEquals(PromptSectionType.CORE_PROFILE, result.getType());
        assertTrue(result.getContent().contains("姓名：王五"));
    }

    @Test
    void sessionContextBuilderShouldFormatSummariesIndependently() {
        SessionContext sessionContext = SessionContext.builder()
                .currentTopic("减重")
                .recentSummaries(List.of(MessageSummary.builder().userMessage("用户上周开始跑步").build()))
                .build();

        PromptSectionResult result = new SessionContextSectionBuilder(support).build(contextBuilder()
                .input(PromptBuildInput.builder().sessionContext(sessionContext).build())
                .remainingBudget(200)
                .build());

        assertEquals(PromptSectionType.SESSION_CONTEXT, result.getType());
        assertTrue(result.getContent().contains("当前话题：减重"));
        assertTrue(result.getContent().contains("用户上周开始跑步"));
    }

    @Test
    void shortTermBuilderShouldOnlyWorkForLayeredMemories() {
        LayeredMemories layeredMemories = LayeredMemories.builder()
                .shortTermMemories(List.of("用户本轮重点关注睡眠"))
                .build();

        PromptSectionResult result = new ShortTermMemorySectionBuilder(support).build(contextBuilder()
                .input(PromptBuildInput.builder().layeredMemories(layeredMemories).build())
                .remainingBudget(200)
                .build());

        assertEquals(PromptSectionType.SHORT_TERM_MEMORY, result.getType());
        assertTrue(result.getContent().contains("用户本轮重点关注睡眠"));
    }

    @Test
    void longTermBuilderShouldFilterLowSimilarityMemories() {
        MemoryItemDTO low = new MemoryItemDTO();
        low.setContent("低相关");
        low.setSimilarity(0.5);

        MemoryItemDTO high = new MemoryItemDTO();
        high.setContent("高相关");
        high.setSimilarity(0.9);

        LayeredMemories layeredMemories = LayeredMemories.builder()
                .longTermMemories(List.of(low, high))
                .build();

        PromptSectionResult result = new LongTermMemorySectionBuilder(support).build(contextBuilder()
                .input(PromptBuildInput.builder().layeredMemories(layeredMemories).build())
                .remainingBudget(200)
                .build());

        assertTrue(result.getContent().contains("高相关"));
        assertTrue(!result.getContent().contains("低相关"));
    }

    @Test
    void relevantMemoriesBuilderShouldHandleLegacyMemoryPath() {
        MemoryItemDTO memory = new MemoryItemDTO();
        memory.setContent("用户常吃夜宵");
        memory.setSimilarity(0.86);

        PromptSectionResult result = new RelevantMemoriesSectionBuilder(support).build(contextBuilder()
                .input(PromptBuildInput.builder().memories(List.of(memory)).build())
                .remainingBudget(200)
                .build());

        assertEquals("## 相关历史记忆", result.getTitle());
        assertTrue(result.getContent().contains("用户常吃夜宵"));
    }

    @Test
    void conversationHistoryBuilderShouldDelegateFormattingIndependently() {
        ContextBudgetProperties properties = new ContextBudgetProperties();
        ConversationHistoryManager historyManager = mock(ConversationHistoryManager.class);
        when(historyManager.getRecentHistory("u2", "conv2", properties.getConversation().getMaxRounds()))
                .thenReturn(List.of());
        PromptSectionSupport localSupport = new PromptSectionSupport(properties, historyManager) {
            @Override
            public String buildConversationHistory(String customerId, String conversationId, int budget) {
                return "用户: 你好\n助手: 你好，请说";
            }
        };

        PromptSectionResult result = new ConversationHistorySectionBuilder(localSupport).build(contextBuilder()
                .input(PromptBuildInput.builder().customerId("u2").conversationId("conv2").build())
                .remainingBudget(200)
                .build());

        assertEquals(PromptSectionType.CONVERSATION_HISTORY, result.getType());
        assertTrue(result.getContent().contains("助手: 你好，请说"));
    }

    @Test
    void extendedProfileBuilderShouldSelectQuestionSpecificFields() {
        UserProfileDTO profile = new UserProfileDTO();
        profile.setBehaviorPreferences(Map.of("dietaryPreferences", List.of("少油", "低糖")));

        PromptSectionResult result = new ExtendedProfileSectionBuilder(support).build(contextBuilder()
                .input(PromptBuildInput.builder()
                        .profile(profile)
                        .userQuestion("饮食方面怎么吃更合适")
                        .build())
                .remainingBudget(200)
                .build());

        assertEquals(PromptSectionType.EXTENDED_PROFILE, result.getType());
        assertTrue(result.getContent().contains("饮食偏好：少油、低糖"));
    }

    private PromptSectionContext.PromptSectionContextBuilder contextBuilder() {
        return PromptSectionContext.builder()
                .budgetAllocation(BudgetAllocation.builder()
                        .coreProfileBudget(120)
                        .sessionContextBudget(80)
                        .memoryBudget(120)
                        .extendedProfileBudget(80)
                        .totalBudget(400)
                        .build());
    }
}
