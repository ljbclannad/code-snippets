package com.lx.governance.aichat.context;

import com.lx.governance.aichat.dto.MessageSummary;
import com.lx.governance.aichat.dto.SessionContext;
import com.lx.governance.aichat.dto.UserProfileDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ContextBudgetManagerV2Test {

    private ContextBudgetManagerV2 budgetManager;
    private ContentCompressor compressor;

    private UserProfileDTO mockProfile;
    private SessionContext mockContext;
    private List<String> mockMemories;

    @BeforeEach
    void setUp() {
        // 使用真实的ContentCompressor
        compressor = new ContentCompressor();
        budgetManager = new ContextBudgetManagerV2();

        // 通过反射注入compressor
        try {
            var field = ContextBudgetManagerV2.class.getDeclaredField("compressor");
            field.setAccessible(true);
            field.set(budgetManager, compressor);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // Mock profile
        mockProfile = new UserProfileDTO();
        Map<String, Object> basicInfo = new HashMap<>();
        basicInfo.put("name", "张三");
        basicInfo.put("age", 35);
        basicInfo.put("gender", "男");
        mockProfile.setBasicInfo(basicInfo);

        Map<String, Object> healthProfile = new HashMap<>();
        healthProfile.put("bmi", 24.5);
        healthProfile.put("bloodPressure", "120/80");
        mockProfile.setHealthProfile(healthProfile);

        // Mock context
        mockContext = SessionContext.builder()
            .currentTopic("血糖管理")
            .conversationPhase("consulting")
            .userIntent("咨询血糖控制")
            .recentSummaries(Arrays.asList(
                MessageSummary.builder().userMessage("我的血糖有点高").build(),
                MessageSummary.builder().userMessage("应该怎么控制").build()
            ))
            .build();

        // Mock memories
        mockMemories = Arrays.asList(
            "用户喜欢吃苹果",
            "用户每天运动30分钟",
            "用户血糖目标是5.5"
        );
    }

    @Test
    void testAllocateBudget_WithinLimit() {
        // Given
        String userMessage = "今天吃什么好？";

        // When
        BudgetAllocation allocation = budgetManager.allocateBudget(
            mockProfile, mockContext, mockMemories, userMessage);

        // Then
        assertNotNull(allocation);
        assertEquals(600, allocation.getCoreProfileBudget());
        assertEquals(800, allocation.getSessionContextBudget());
        assertEquals(1200, allocation.getMemoryBudget());
        assertEquals(200, allocation.getExtendedProfileBudget());
        assertEquals(500, allocation.getUserMessageReserve());
        assertEquals(4000, allocation.getTotalBudget());
        assertTrue(allocation.getActualUsage() < allocation.getTotalBudget());
        assertFalse(allocation.isNeedsCompression());
    }

    @Test
    void testAllocateBudget_ExceedsLimit() {
        // Given - 创建大量内容超出预算
        String longMessage = "这是一条很长的消息，".repeat(500);

        List<String> manyMemories = Arrays.asList(
            "记忆1".repeat(100),
            "记忆2".repeat(100),
            "记忆3".repeat(100),
            "记忆4".repeat(100),
            "记忆5".repeat(100)
        );

        // When
        BudgetAllocation allocation = budgetManager.allocateBudget(
            mockProfile, mockContext, manyMemories, longMessage);

        // Then
        assertTrue(allocation.isNeedsCompression());
        assertTrue(allocation.getActualUsage() > allocation.getTotalBudget());
    }

    @Test
    void testCompressContent_NoCompressionNeeded() {
        // Given
        String userMessage = "今天吃什么好？";
        BudgetAllocation allocation = budgetManager.allocateBudget(
            mockProfile, mockContext, mockMemories, userMessage);

        // When
        var result = budgetManager.compressContent(
            mockProfile, mockContext, mockMemories, allocation);

        // Then
        assertNotNull(result);
        assertNotNull(result.getCoreProfile());
        assertNotNull(result.getSessionContext());
        assertEquals(3, result.getMemories().size());
        assertTrue(result.getCoreProfile().contains("张三"));
        assertTrue(result.getSessionContext().contains("血糖管理"));
    }

    @Test
    void testCompressContent_CompressionNeeded() {
        // Given - 创建需要压缩的场景
        String longMessage = "这是一条很长的消息，".repeat(500);

        List<String> manyMemories = Arrays.asList(
            "记忆1：用户喜欢吃苹果，每天都要吃一个苹果".repeat(50),
            "记忆2：用户每天运动30分钟，主要是跑步和游泳".repeat(50),
            "记忆3：用户血糖目标是5.5，需要严格控制饮食".repeat(50)
        );

        BudgetAllocation allocation = budgetManager.allocateBudget(
            mockProfile, mockContext, manyMemories, longMessage);

        // When
        var result = budgetManager.compressContent(
            mockProfile, mockContext, manyMemories, allocation);

        // Then
        assertNotNull(result);
        assertNotNull(result.getCoreProfile());
        assertNotNull(result.getSessionContext());
        assertNotNull(result.getMemories());

        // 验证内容被压缩了
        int totalTokens = compressor.estimateTokens(result.getCoreProfile()) +
                         compressor.estimateTokens(result.getSessionContext()) +
                         result.getMemories().stream()
                             .mapToInt(compressor::estimateTokens)
                             .sum();

        // 压缩后应该更接近预算
        assertTrue(totalTokens < allocation.getTotalBudget());
    }
}
