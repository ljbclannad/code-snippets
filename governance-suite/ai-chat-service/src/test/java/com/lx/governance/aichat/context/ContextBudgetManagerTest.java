package com.lx.governance.aichat.context;

import com.lx.governance.aichat.config.ContextBudgetProperties;
import com.lx.governance.aichat.dto.MemoryItemDTO;
import com.lx.governance.aichat.dto.UserProfileDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ContextBudgetManager 测试
 */
class ContextBudgetManagerTest {

    private ContextBudgetManager budgetManager;
    private ContextBudgetProperties properties;

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
        properties.setExtendedProfileBudget(400);

        ContextBudgetProperties.MemoryConfig memoryConfig = new ContextBudgetProperties.MemoryConfig();
        memoryConfig.setSimilarityThreshold(0.7);
        properties.setMemory(memoryConfig);

        // 创建 ContextBudgetManager 并注入配置
        budgetManager = new ContextBudgetManager();
        ReflectionTestUtils.setField(budgetManager, "properties", properties);

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
        budgetManager.buildBudgetedSystemPrompt(testProfile, testMemories, "问题1");
        int firstTotal = budgetManager.getLastTotalTokens();
        assertTrue(firstTotal > 0);

        // Second call with different data
        UserProfileDTO simpleProfile = new UserProfileDTO();
        Map<String, Object> basicInfo = new HashMap<>();
        basicInfo.put("name", "李四");
        simpleProfile.setBasicInfo(basicInfo);

        budgetManager.buildBudgetedSystemPrompt(simpleProfile, new ArrayList<>(), "问题2");
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
                "健康建议"
        );

        // Then
        assertNotNull(prompt);
        // 核心画像 tokens 应该不超过配置的预算
        assertTrue(budgetManager.getLastCoreProfileTokens() <= properties.getCoreProfileBudget(),
                "核心画像 tokens 应该不超过预算");
    }
}
