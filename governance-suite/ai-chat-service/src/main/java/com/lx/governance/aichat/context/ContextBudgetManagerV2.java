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

    // 估算方法将在下一个任务实现
    private int estimateProfileTokens(UserProfileDTO profile) {
        // TODO: Task 4实现
        return 0;
    }

    private int estimateContextTokens(SessionContext sessionContext) {
        // TODO: Task 4实现
        return 0;
    }

    private int estimateMemoriesTokens(List<String> memories) {
        // TODO: Task 4实现
        return 0;
    }
}
