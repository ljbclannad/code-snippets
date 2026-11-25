package com.lx.governance.aichat.context;

import org.springframework.stereotype.Component;

import com.lx.governance.aichat.config.ContextBudgetProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 统一的 Prompt budget 策略。
 * 负责把静态配置和复杂度策略收敛成可执行的 section 级预算。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PromptBudgetPolicy {

    private static final double SESSION_CONTEXT_RATIO = 0.4D;
    private static final double SHORT_TERM_MEMORY_RATIO = 0.35D;

    private final ContextBudgetProperties properties;
    private final DynamicBudgetStrategy dynamicBudgetStrategy;

    public BudgetAllocation resolve(String complexity) {
        return resolve(complexity, 0);
    }

    public BudgetAllocation resolve(String complexity, int userTokens) {
        DynamicBudgetStrategy.BaseBudgetConfig baseBudget = new DynamicBudgetStrategy.BaseBudgetConfig();
        baseBudget.setCoreProfileBudget(properties.getCoreProfileBudget());
        baseBudget.setRelevantMemoriesBudget(properties.getRelevantMemoriesBudget());
        baseBudget.setConversationHistoryBudget(properties.getConversationHistoryBudget());
        baseBudget.setExtendedProfileBudget(properties.getExtendedProfileBudget());

        BudgetAllocation allocation = dynamicBudgetStrategy.calculateBudget(complexity, baseBudget);
        allocation.setTotalBudget(properties.getTotalBudget());
        allocation.setUserMessageReserve(Math.max(properties.getUserMessageReserve(), userTokens));
        allocation.setSystemPromptBudget(Math.max(0, properties.getTotalBudget() - allocation.getUserMessageReserve()));
        allocation.setToolResultBudget(0);
        allocation.setProfileBudget(allocation.getCoreProfileBudget() + allocation.getExtendedProfileBudget());

        int contextEnvelopeBudget = Math.max(0, allocation.getSessionContextBudget());
        int sessionContextBudget = clampSplit(contextEnvelopeBudget, SESSION_CONTEXT_RATIO);
        allocation.setSessionContextBudget(sessionContextBudget);
        allocation.setConversationHistoryBudget(Math.max(0, contextEnvelopeBudget - sessionContextBudget));

        int memoryEnvelopeBudget = Math.max(0, allocation.getMemoryBudget());
        int shortTermMemoryBudget = clampSplit(memoryEnvelopeBudget, SHORT_TERM_MEMORY_RATIO);
        allocation.setShortTermMemoryBudget(shortTermMemoryBudget);
        allocation.setLongTermMemoryBudget(Math.max(0, memoryEnvelopeBudget - shortTermMemoryBudget));

        log.info(
                "Prompt budget policy resolved - complexity: {}, systemPromptBudget: {}, userMessageReserve: {}, coreProfile: {}, sessionContext: {}, conversationHistory: {}, shortTermMemory: {}, longTermMemory: {}, extendedProfile: {}, toolResult: {}",
                complexity,
                allocation.getSystemPromptBudget(),
                allocation.getUserMessageReserve(),
                allocation.getCoreProfileBudget(),
                allocation.getSessionContextBudget(),
                allocation.getConversationHistoryBudget(),
                allocation.getShortTermMemoryBudget(),
                allocation.getLongTermMemoryBudget(),
                allocation.getExtendedProfileBudget(),
                allocation.getToolResultBudget());

        return allocation;
    }

    private int clampSplit(int totalBudget, double ratio) {
        if (totalBudget <= 0) {
            return 0;
        }
        int split = (int) Math.round(totalBudget * ratio);
        if (split <= 0) {
            return Math.min(1, totalBudget);
        }
        return Math.min(split, totalBudget);
    }
}
