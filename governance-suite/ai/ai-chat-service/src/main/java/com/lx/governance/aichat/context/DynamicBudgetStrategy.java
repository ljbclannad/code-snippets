package com.lx.governance.aichat.context;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 动态预算调整策略
 * 根据对话复杂度动态调整各部分的 token 预算
 *
 * @author LeJinBo
 * @since 2026-05-25
 */
@Slf4j
@Component
public class DynamicBudgetStrategy {

    /**
     * 根据复杂度计算动态预算
     *
     * @param complexity 对话复杂度（simple, medium, complex）
     * @param baseBudget 基础预算配置
     * @return 动态调整后的预算
     */
    public BudgetAllocation calculateBudget(String complexity, BaseBudgetConfig baseBudget) {
        if (complexity == null) {
            complexity = "medium"; // 默认中等复杂度
        }

        BudgetAllocation allocation = new BudgetAllocation();

        switch (complexity.toLowerCase()) {
            case "simple":
                // 简单对话：减少记忆和历史预算，保留核心画像
                allocation.setCoreProfileBudget((int) (baseBudget.getCoreProfileBudget() * 0.75));
                allocation.setMemoryBudget((int) (baseBudget.getRelevantMemoriesBudget() * 0.5));
                allocation.setSessionContextBudget((int) (baseBudget.getConversationHistoryBudget() * 0.5));
                allocation.setExtendedProfileBudget((int) (baseBudget.getExtendedProfileBudget() * 0.5));
                log.debug("使用 simple 复杂度预算策略");
                break;

            case "complex":
                // 复杂对话：增加记忆和历史预算
                allocation.setCoreProfileBudget((int) (baseBudget.getCoreProfileBudget() * 1.25));
                allocation.setMemoryBudget((int) (baseBudget.getRelevantMemoriesBudget() * 1.5));
                allocation.setSessionContextBudget((int) (baseBudget.getConversationHistoryBudget() * 1.5));
                allocation.setExtendedProfileBudget((int) (baseBudget.getExtendedProfileBudget() * 1.5));
                log.debug("使用 complex 复杂度预算策略");
                break;

            case "medium":
            default:
                // 中等复杂度：使用基础预算
                allocation.setCoreProfileBudget(baseBudget.getCoreProfileBudget());
                allocation.setMemoryBudget(baseBudget.getRelevantMemoriesBudget());
                allocation.setSessionContextBudget(baseBudget.getConversationHistoryBudget());
                allocation.setExtendedProfileBudget(baseBudget.getExtendedProfileBudget());
                log.debug("使用 medium 复杂度预算策略");
                break;
        }

        // 计算总预算
        int totalAllocated = allocation.getCoreProfileBudget()
                + allocation.getMemoryBudget()
                + allocation.getSessionContextBudget()
                + allocation.getExtendedProfileBudget();

        allocation.setTotalBudget(totalAllocated);

        log.info("动态预算分配 - complexity: {}, 核心画像: {}, 记忆: {}, 历史: {}, 扩展: {}, 总计: {}",
                complexity,
                allocation.getCoreProfileBudget(),
                allocation.getMemoryBudget(),
                allocation.getSessionContextBudget(),
                allocation.getExtendedProfileBudget(),
                allocation.getTotalBudget());

        return allocation;
    }

    /**
     * 基础预算配置（从 ContextBudgetProperties 读取）
     */
    @Data
    public static class BaseBudgetConfig {
        private int coreProfileBudget;
        private int relevantMemoriesBudget;
        private int conversationHistoryBudget;
        private int extendedProfileBudget;
    }
}
