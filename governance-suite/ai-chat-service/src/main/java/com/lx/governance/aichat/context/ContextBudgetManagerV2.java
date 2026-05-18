package com.lx.governance.aichat.context;

import com.lx.governance.aichat.dto.SessionContext;
import com.lx.governance.aichat.dto.UserProfileDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Slf4j
public class ContextBudgetManagerV2 {

    @Autowired
    private ContentCompressor compressor;

    // 预算常量
    private static final int TOTAL_BUDGET = 4000;
    private static final int CORE_PROFILE_BUDGET = 600;
    private static final int SESSION_CONTEXT_BUDGET = 800;
    private static final int MEMORY_BUDGET = 1200;
    private static final int EXTENDED_PROFILE_BUDGET = 200;
    private static final int USER_MESSAGE_RESERVE = 500;

    /**
     * 分配预算
     */
    public BudgetAllocation allocateBudget(
            UserProfileDTO profile,
            SessionContext sessionContext,
            List<String> memories,
            String userMessage) {

        BudgetAllocation allocation = BudgetAllocation.builder()
            .coreProfileBudget(CORE_PROFILE_BUDGET)
            .sessionContextBudget(SESSION_CONTEXT_BUDGET)
            .memoryBudget(MEMORY_BUDGET)
            .extendedProfileBudget(EXTENDED_PROFILE_BUDGET)
            .userMessageReserve(USER_MESSAGE_RESERVE)
            .totalBudget(TOTAL_BUDGET)
            .build();

        // 计算实际使用
        int actualUsage = calculateActualUsage(profile, sessionContext, memories, userMessage);
        allocation.setActualUsage(actualUsage);

        // 判断是否需要压缩
        allocation.setNeedsCompression(actualUsage > TOTAL_BUDGET);

        return allocation;
    }

    /**
     * 计算实际使用量
     */
    private int calculateActualUsage(
            UserProfileDTO profile,
            SessionContext sessionContext,
            List<String> memories,
            String userMessage) {

        int profileTokens = estimateProfileTokens(profile);
        int contextTokens = estimateContextTokens(sessionContext);
        int memoryTokens = estimateMemoriesTokens(memories);
        int messageTokens = compressor.estimateTokens(userMessage);

        return profileTokens + contextTokens + memoryTokens + messageTokens;
    }

    private int estimateProfileTokens(UserProfileDTO profile) {
        if (profile == null) {
            return 0;
        }

        StringBuilder profileText = new StringBuilder();

        // 基本信息
        if (profile.getBasicInfo() != null) {
            profileText.append("基本信息:").append(profile.getBasicInfo().toString());
        }

        // 健康档案
        if (profile.getHealthProfile() != null) {
            profileText.append(",健康档案:").append(profile.getHealthProfile().toString());
        }

        return compressor.estimateTokens(profileText.toString());
    }

    private int estimateContextTokens(SessionContext sessionContext) {
        if (sessionContext == null) {
            return 0;
        }

        StringBuilder contextText = new StringBuilder();

        contextText.append("话题:").append(sessionContext.getCurrentTopic())
            .append(",阶段:").append(sessionContext.getConversationPhase())
            .append(",意图:").append(sessionContext.getUserIntent());

        // 最近消息摘要
        if (sessionContext.getRecentSummaries() != null) {
            for (var summary : sessionContext.getRecentSummaries()) {
                contextText.append(",").append(summary.getUserMessage());
            }
        }

        return compressor.estimateTokens(contextText.toString());
    }

    private int estimateMemoriesTokens(List<String> memories) {
        if (memories == null || memories.isEmpty()) {
            return 0;
        }

        return memories.stream()
            .mapToInt(compressor::estimateTokens)
            .sum();
    }

    /**
     * 压缩内容以适应预算
     */
    public CompressedContent compressContent(
            UserProfileDTO profile,
            SessionContext sessionContext,
            List<String> memories,
            BudgetAllocation allocation) {

        if (!allocation.isNeedsCompression()) {
            return CompressedContent.builder()
                .coreProfile(buildCoreProfile(profile))
                .sessionContext(buildSessionContext(sessionContext))
                .memories(memories)
                .build();
        }

        log.info("内容超出预算，开始压缩: actual={}, budget={}",
            allocation.getActualUsage(), allocation.getTotalBudget());

        // 1. 核心画像（必需层，不压缩）
        String coreProfile = buildCoreProfile(profile);

        // 2. 会话上下文（必需层，轻度压缩）
        String compressedContext = compressor.smartSummarize(
            buildSessionContext(sessionContext),
            allocation.getSessionContextBudget()
        );

        // 3. 相关记忆（重要层，按需压缩）
        List<String> compressedMemories = compressor.compressListToTokens(
            memories,
            allocation.getMemoryBudget()
        );

        // 4. 扩展画像（可选层，可能被丢弃）
        String extendedProfile = "";
        int usedTokens = compressor.estimateTokens(coreProfile) +
                         compressor.estimateTokens(compressedContext) +
                         estimateMemoriesTokens(compressedMemories);

        if (usedTokens + allocation.getUserMessageReserve() < allocation.getTotalBudget()) {
            int remaining = allocation.getTotalBudget() - usedTokens - allocation.getUserMessageReserve();
            extendedProfile = buildExtendedProfile(profile, remaining);
        }

        return CompressedContent.builder()
            .coreProfile(coreProfile)
            .sessionContext(compressedContext)
            .memories(compressedMemories)
            .extendedProfile(extendedProfile)
            .build();
    }

    /**
     * 构建核心画像文本
     */
    private String buildCoreProfile(UserProfileDTO profile) {
        if (profile == null || profile.getBasicInfo() == null) {
            return "";
        }

        return String.format("用户基本信息：%s", profile.getBasicInfo().toString());
    }

    /**
     * 构建会话上下文文本
     */
    private String buildSessionContext(SessionContext context) {
        if (context == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("当前话题：").append(context.getCurrentTopic()).append("\n");
        sb.append("对话阶段：").append(context.getConversationPhase()).append("\n");
        sb.append("用户意图：").append(context.getUserIntent()).append("\n");

        if (context.getRecentSummaries() != null && !context.getRecentSummaries().isEmpty()) {
            sb.append("最近对话：\n");
            for (var summary : context.getRecentSummaries()) {
                sb.append("- ").append(summary.getUserMessage()).append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * 构建扩展画像文本
     */
    private String buildExtendedProfile(UserProfileDTO profile, int maxTokens) {
        if (profile == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        // 健康档案
        if (profile.getHealthProfile() != null) {
            sb.append("健康档案：").append(profile.getHealthProfile().toString()).append("\n");
        }

        // 行为偏好
        if (profile.getBehaviorPreferences() != null) {
            sb.append("行为偏好：").append(profile.getBehaviorPreferences().toString()).append("\n");
        }

        return compressor.compressToTokens(sb.toString(), maxTokens);
    }

    /**
     * 压缩后的内容
     */
    @lombok.Data
    @lombok.Builder
    public static class CompressedContent {
        private String coreProfile;
        private String sessionContext;
        private List<String> memories;
        private String extendedProfile;
    }
}
