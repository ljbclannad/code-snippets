package com.lx.governance.aichat.context;

import com.lx.governance.aichat.config.ContextBudgetProperties;
import com.lx.governance.aichat.dto.ConversationMessage;
import com.lx.governance.aichat.dto.MemoryItemDTO;
import com.lx.governance.aichat.dto.UserProfileDTO;
import com.lx.governance.aichat.mapper.AiChatHistoryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ContextBudgetManager 测试
 */
class ContextBudgetManagerTest {

    private ContextBudgetManager budgetManager;
    private ContextBudgetProperties properties;
    private ConversationHistoryManager conversationHistoryManager;
    private AiChatHistoryMapper chatHistoryMapper;

    private UserProfileDTO testProfile;
    private List<MemoryItemDTO> testMemories;

    @BeforeEach
    void setUp() {
        // 创建配置对象
        properties = new ContextBudgetProperties();
        properties.setTotalBudget(4000);
        properties.setUserMessageReserve(500);
        properties.setCoreProfileBudget(800);
        properties.setRelevantMemoriesBudget(1200);
        properties.setConversationHistoryBudget(600);
        properties.setExtendedProfileBudget(400);

        ContextBudgetProperties.MemoryConfig memoryConfig = new ContextBudgetProperties.MemoryConfig();
        memoryConfig.setSimilarityThreshold(0.7);
        memoryConfig.setShortTermDays(3);
        memoryConfig.setMidTermDays(30);
        properties.setMemory(memoryConfig);

        ContextBudgetProperties.ConversationConfig conversationConfig = new ContextBudgetProperties.ConversationConfig();
        conversationConfig.setEnabled(true);
        conversationConfig.setMaxRounds(5);
        properties.setConversation(conversationConfig);

        // Mock AiChatHistoryMapper
        chatHistoryMapper = mock(AiChatHistoryMapper.class);

        // 创建 ConversationHistoryManager
        conversationHistoryManager = new ConversationHistoryManager(properties, chatHistoryMapper);

        // 创建 ContextBudgetManager 并注入配置
        budgetManager = new ContextBudgetManager();
        ReflectionTestUtils.setField(budgetManager, "properties", properties);
        ReflectionTestUtils.setField(budgetManager, "conversationHistoryManager", conversationHistoryManager);

        // 创建测试用户画像
        testProfile = new UserProfileDTO();

        Map<String, Object> basicInfo = new HashMap<>();
        basicInfo.put("name", "张三");
        basicInfo.put("age", 45);
        basicInfo.put("gender", "男");
        testProfile.setBasicInfo(basicInfo);

        Map<String, Object> healthProfile = new HashMap<>();
        healthProfile.put("bloodPressure", "140/90");
        healthProfile.put("bmi", 26.5);
        healthProfile.put("bloodSugar", 6.2);
        testProfile.setHealthProfile(healthProfile);

        // 创建测试记忆
        testMemories = new ArrayList<>();

        MemoryItemDTO memory1 = new MemoryItemDTO();
        memory1.setContent("用户最近血压偏高，建议减少盐分摄入");
        memory1.setSimilarity(0.85);
        testMemories.add(memory1);

        MemoryItemDTO memory2 = new MemoryItemDTO();
        memory2.setContent("用户喜欢散步，每天坚持30分钟");
        memory2.setSimilarity(0.75);
        testMemories.add(memory2);

        MemoryItemDTO memory3 = new MemoryItemDTO();
        memory3.setContent("用户对海鲜过敏");
        memory3.setSimilarity(0.65); // 低于阈值
        testMemories.add(memory3);
    }

    @Test
    void testBuildBudgetedSystemPrompt_shouldUseConfiguredBudgets() {
        // When
        String prompt = budgetManager.buildBudgetedSystemPrompt(
                testProfile,
                testMemories,
                "user123",
                "conv-abc",
                "我应该如何控制血压？"
        );

        // Then
        assertNotNull(prompt);
        assertTrue(prompt.contains("你是一个专业的健康顾问助手"));
        assertTrue(prompt.contains("## 用户核心信息"));
        assertTrue(prompt.contains("张三"));
        assertTrue(prompt.contains("45岁"));

        // 验证 token 使用情况被记录
        assertTrue(budgetManager.getLastCoreProfileTokens() > 0);
        assertTrue(budgetManager.getLastTotalTokens() > 0);

        // 验证总 token 不超过上下文预算
        int contextBudget = properties.getTotalBudget() - properties.getUserMessageReserve();
        assertTrue(budgetManager.getLastTotalTokens() <= contextBudget,
                "总 tokens 应该不超过上下文预算");
    }

    @Test
    void testBuildBudgetedSystemPrompt_shouldFilterMemoriesBySimilarity() {
        // When
        String prompt = budgetManager.buildBudgetedSystemPrompt(
                testProfile,
                testMemories,
                "user123",
                "conv-abc",
                "我应该如何控制血压？"
        );

        // Then
        // 应该包含高相似度的记忆
        assertTrue(prompt.contains("血压偏高"));
        assertTrue(prompt.contains("散步"));

        // 不应该包含低相似度的记忆（0.65 < 0.7）
        assertFalse(prompt.contains("海鲜过敏"));
    }

    @Test
    void testBuildBudgetedSystemPrompt_withNullProfile_shouldHandleGracefully() {
        // When
        String prompt = budgetManager.buildBudgetedSystemPrompt(
                null,
                testMemories,
                "user123",
                "conv-abc",
                "健康建议"
        );

        // Then
        assertNotNull(prompt);
        assertTrue(prompt.contains("你是一个专业的健康顾问助手"));
        // 核心画像部分应该为空或很少
        assertTrue(budgetManager.getLastCoreProfileTokens() == 0);
    }

    @Test
    void testBuildBudgetedSystemPrompt_withEmptyMemories_shouldHandleGracefully() {
        // When
        String prompt = budgetManager.buildBudgetedSystemPrompt(
                testProfile,
                new ArrayList<>(),
                "user123",
                "conv-abc",
                "健康建议"
        );

        // Then
        assertNotNull(prompt);
        assertTrue(prompt.contains("## 用户核心信息"));
        // 记忆部分应该为空
        assertEquals(0, budgetManager.getLastMemoriesTokens());
    }

    @Test
    void testTokenCounters_shouldResetBetweenCalls() {
        // First call
        budgetManager.buildBudgetedSystemPrompt(testProfile, testMemories, "user123", "conv-abc", "问题1");
        int firstTotal = budgetManager.getLastTotalTokens();
        assertTrue(firstTotal > 0);

        // Second call with different data
        UserProfileDTO simpleProfile = new UserProfileDTO();
        Map<String, Object> basicInfo = new HashMap<>();
        basicInfo.put("name", "李四");
        simpleProfile.setBasicInfo(basicInfo);

        budgetManager.buildBudgetedSystemPrompt(simpleProfile, new ArrayList<>(), "user456", "conv-xyz", "问题2");
        int secondTotal = budgetManager.getLastTotalTokens();

        // 第二次调用的 token 应该更少（因为没有记忆）
        assertTrue(secondTotal < firstTotal);
    }

    @Test
    void testBuildBudgetedSystemPrompt_shouldRespectCoreProfileBudget() {
        // Given: 创建一个非常大的用户画像
        UserProfileDTO largeProfile = new UserProfileDTO();
        Map<String, Object> basicInfo = new HashMap<>();
        basicInfo.put("name", "张三");
        basicInfo.put("age", 45);
        basicInfo.put("gender", "男");
        largeProfile.setBasicInfo(basicInfo);

        Map<String, Object> healthProfile = new HashMap<>();
        // 添加大量健康数据
        for (int i = 0; i < 50; i++) {
            healthProfile.put("metric" + i, "value" + i);
        }
        largeProfile.setHealthProfile(healthProfile);

        // When
        String prompt = budgetManager.buildBudgetedSystemPrompt(
                largeProfile,
                testMemories,
                "user123",
                "conv-abc",
                "健康建议"
        );

        // Then
        assertNotNull(prompt);
        // 核心画像 tokens 应该不超过配置的预算
        assertTrue(budgetManager.getLastCoreProfileTokens() <= properties.getCoreProfileBudget(),
                "核心画像 tokens 应该不超过预算");
    }

    // Task 9 新增测试用例

    @Test
    void shouldIncludeConversationHistoryInSystemPrompt() {
        // 模拟对话历史
        List<ConversationMessage> history = Arrays.asList(
                ConversationMessage.builder()
                        .role(ConversationMessage.Role.USER)
                        .content("我的血压是多少？")
                        .timestamp(LocalDateTime.now().minusMinutes(10))
                        .build(),
                ConversationMessage.builder()
                        .role(ConversationMessage.Role.ASSISTANT)
                        .content("您的血压是 120/80 mmHg，属于正常范围。")
                        .timestamp(LocalDateTime.now().minusMinutes(9))
                        .build()
        );

        // Mock conversationHistoryManager 的行为
        ConversationHistoryManager mockManager = mock(ConversationHistoryManager.class);
        when(mockManager.getRecentHistory(anyString(), anyString(), anyInt()))
                .thenReturn(history);
        when(mockManager.formatHistory(any(), anyInt()))
                .thenReturn("用户: 我的血压是多少？\n助手: 您的血压是 120/80 mmHg，属于正常范围。\n");

        ReflectionTestUtils.setField(budgetManager, "conversationHistoryManager", mockManager);

        UserProfileDTO profile = testProfile;
        List<MemoryItemDTO> memories = createTestMemories(5);

        String systemPrompt = budgetManager.buildBudgetedSystemPrompt(
                profile, memories, "user123", "conv-abc", "今天吃什么好？");

        // 验证包含对话历史
        assertThat(systemPrompt).contains("## 对话历史");
        assertThat(systemPrompt).contains("我的血压是多少？");
        assertThat(systemPrompt).contains("您的血压是 120/80 mmHg");

        // 验证 token 计数
        assertThat(budgetManager.getLastConversationTokens()).isGreaterThan(0);
    }

    @Test
    void shouldSkipConversationHistoryWhenEmpty() {
        // Mock conversationHistoryManager 返回空列表
        ConversationHistoryManager mockManager = mock(ConversationHistoryManager.class);
        when(mockManager.getRecentHistory(anyString(), anyString(), anyInt()))
                .thenReturn(Collections.emptyList());

        ReflectionTestUtils.setField(budgetManager, "conversationHistoryManager", mockManager);

        UserProfileDTO profile = testProfile;
        List<MemoryItemDTO> memories = createTestMemories(5);

        String systemPrompt = budgetManager.buildBudgetedSystemPrompt(
                profile, memories, "user123", "conv-abc", "测试问题");

        // 不应该包含对话历史部分
        assertThat(systemPrompt).doesNotContain("## 对话历史");
        assertThat(budgetManager.getLastConversationTokens()).isEqualTo(0);
    }

    @Test
    void shouldRespectConversationHistoryBudget() {
        // 模拟很长的对话历史
        List<ConversationMessage> longHistory = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            longHistory.add(ConversationMessage.builder()
                    .role(ConversationMessage.Role.USER)
                    .content("很长的消息内容".repeat(50))
                    .timestamp(LocalDateTime.now().minusMinutes(i * 2))
                    .build());
        }

        // Mock conversationHistoryManager 的行为
        ConversationHistoryManager mockManager = mock(ConversationHistoryManager.class);
        when(mockManager.getRecentHistory(anyString(), anyString(), anyInt()))
                .thenReturn(longHistory);
        when(mockManager.formatHistory(any(), anyInt()))
                .thenAnswer(invocation -> {
                    int budget = invocation.getArgument(1);
                    // 模拟格式化逻辑，返回受预算约束的文本
                    return "用户: 测试\n".repeat(Math.min(budget / 20, 10));
                });

        ReflectionTestUtils.setField(budgetManager, "conversationHistoryManager", mockManager);

        properties.setConversationHistoryBudget(600);

        UserProfileDTO profile = testProfile;
        List<MemoryItemDTO> memories = createTestMemories(5);

        String systemPrompt = budgetManager.buildBudgetedSystemPrompt(
                profile, memories, "user123", "conv-abc", "测试问题");

        // 验证对话历史 token 不超过预算
        assertThat(budgetManager.getLastConversationTokens()).isLessThanOrEqualTo(600);
    }

    private List<MemoryItemDTO> createTestMemories(int count) {
        List<MemoryItemDTO> memories = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            MemoryItemDTO memory = new MemoryItemDTO();
            memory.setContent("测试记忆内容 " + i);
            memory.setSimilarity(0.8 - i * 0.05);
            memories.add(memory);
        }
        return memories;
    }
}
