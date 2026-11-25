package com.lx.governance.aichat.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.lx.governance.aichat.exception.ConversationException;
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

class ContextBudgetManagerTest {

    private ContextBudgetManager manager;

    @BeforeEach
    void setUp() {
        ContextBudgetProperties properties = new ContextBudgetProperties();
        properties.setCoreProfileBudget(200);
        properties.setRelevantMemoriesBudget(200);
        properties.setConversationHistoryBudget(120);
        properties.setExtendedProfileBudget(80);
        properties.getMemory().setSimilarityThreshold(0.7);

        ConversationHistoryManager historyManager = mock(ConversationHistoryManager.class);
        when(historyManager.getRecentHistory("c1", "conv1", properties.getConversation().getMaxRounds()))
                .thenReturn(List.of());

        PromptSectionSupport support = new PromptSectionSupport(properties, historyManager);
        List<PromptSectionBuilder> builders = List.of(
                new CoreProfileSectionBuilder(support),
                new SessionContextSectionBuilder(support),
                new ShortTermMemorySectionBuilder(support),
                new LongTermMemorySectionBuilder(support),
                new RelevantMemoriesSectionBuilder(support),
                new ConversationHistorySectionBuilder(support),
                new ExtendedProfileSectionBuilder(support));

        manager = new ContextBudgetManager(
                new PromptBudgetPolicy(properties, new DynamicBudgetStrategy()),
                builders);
    }

    @Test
    void longUserInputShrinksPromptAndOversizedInputIsRejected() {
        String user = "测试".repeat(2200);
        var profile = new UserProfileDTO();
        profile.setBasicInfo(Map.of("name", "测试".repeat(5000)));
        String prompt = manager.buildBudgetedSystemPrompt(profile, null,
                LayeredMemories.builder().build(), "c1", "conv1", user);
        assertTrue(PromptTokenBudget.estimate(prompt) + PromptTokenBudget.estimate(user) <= 8192);
        assertThrows(ConversationException.class, () -> manager.buildBudgetedSystemPrompt(profile, null,
                LayeredMemories.builder().build(), "c1", "conv1", "测试".repeat(3000)));
    }

    @Test
    void sectionTitlesAndFixedInstructionsCountTowardsBudget() {
        var properties = new ContextBudgetProperties();
        properties.setTotalBudget(300);
        properties.setUserMessageReserve(10);
        PromptSectionBuilder builder = mock(PromptSectionBuilder.class);
        when(builder.build(org.mockito.ArgumentMatchers.any())).thenReturn(PromptSectionResult.builder()
                .type(PromptSectionType.CORE_PROFILE).title("很长的标题".repeat(3))
                .content("中文".repeat(300)).usedTokens(1).build());
        var tightManager = new ContextBudgetManager(
                new PromptBudgetPolicy(properties, new DynamicBudgetStrategy()), List.of(builder));
        String user = "本轮问题";
        String prompt = tightManager.buildBudgetedSystemPrompt(null, null,
                LayeredMemories.builder().build(), "c1", "conv1", user);
        assertTrue(PromptTokenBudget.estimate(prompt) + PromptTokenBudget.estimate(user) <= 300);
        assertTrue(prompt.endsWith("请基于以上信息，提供专业、个性化的健康建议。"));
    }

    @Test
    void shouldBuildLayeredPromptViaPipelineInOrder() {
        UserProfileDTO profile = new UserProfileDTO();
        profile.setBasicInfo(Map.of("name", "张三", "age", 35, "gender", "男"));
        profile.setHealthProfile(Map.of("bloodSugar", "6.1", "allergies", List.of("花生")));
        profile.setBehaviorPreferences(Map.of("dietaryPreferences", List.of("低糖")));

        SessionContext sessionContext = SessionContext.builder()
                .currentTopic("控糖")
                .conversationPhase("consulting")
                .userIntent("饮食建议")
                .complexity("medium")
                .mentionedEntities(List.of("血糖"))
                .recentSummaries(List.of(MessageSummary.builder().userMessage("最近早餐后血糖偏高").build()))
                .build();

        MemoryItemDTO memoryItem = new MemoryItemDTO();
        memoryItem.setContent("用户早餐后血糖容易升高");
        memoryItem.setSimilarity(0.92);

        LayeredMemories layeredMemories = LayeredMemories.builder()
                .shortTermMemories(List.of("用户想控制早餐后血糖"))
                .longTermMemories(List.of(memoryItem))
                .build();

        String prompt = manager.buildBudgetedSystemPrompt(
                profile, sessionContext, layeredMemories, "c1", "conv1", "饮食上怎么调整比较好");

        assertTrue(prompt.contains("## 用户核心信息"));
        assertTrue(prompt.contains("## 会话上下文"));
        assertTrue(prompt.contains("## 短期记忆"));
        assertTrue(prompt.contains("## 长期记忆"));
        assertTrue(prompt.contains("## 补充信息"));
        assertTrue(prompt.indexOf("## 用户核心信息") < prompt.indexOf("## 会话上下文"));
        assertTrue(prompt.indexOf("## 会话上下文") < prompt.indexOf("## 短期记忆"));
        assertTrue(prompt.indexOf("## 短期记忆") < prompt.indexOf("## 长期记忆"));
        assertTrue(manager.getLastCoreProfileTokens() > 0);
        assertTrue(manager.getLastSessionContextTokens() > 0);
        assertTrue(manager.getLastMemoriesTokens() > 0);
        assertTrue(manager.getLastExtendedProfileTokens() > 0);
        assertEquals(PromptTokenBudget.estimate(prompt), manager.getLastTotalTokens());
    }

    @Test
    void shouldUseLegacyMemorySectionWhenLayeredMemoriesAbsent() {
        UserProfileDTO profile = new UserProfileDTO();
        profile.setBasicInfo(Map.of("name", "李四"));

        MemoryItemDTO memoryItem = new MemoryItemDTO();
        memoryItem.setContent("用户晚餐偏咸");
        memoryItem.setSimilarity(0.88);

        String prompt = manager.buildBudgetedSystemPrompt(
                profile, null, List.of(memoryItem), "c1", "conv1", "有什么健康建议");

        assertTrue(prompt.contains("## 相关历史记忆"));
        assertTrue(!prompt.contains("## 短期记忆"));
        assertTrue(!prompt.contains("## 长期记忆"));
    }
}
