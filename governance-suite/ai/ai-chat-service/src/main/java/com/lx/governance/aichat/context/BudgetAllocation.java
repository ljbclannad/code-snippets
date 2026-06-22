package com.lx.governance.aichat.context;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BudgetAllocation {

    /**
     * 核心画像预算
     */
    private int coreProfileBudget;

    /**
     * 会话上下文预算
     */
    private int sessionContextBudget;

    /**
     * 相关记忆预算
     */
    private int memoryBudget;

    /**
     * 短期记忆预算
     */
    private int shortTermMemoryBudget;

    /**
     * 长期记忆预算
     */
    private int longTermMemoryBudget;

    /**
     * 对话历史预算
     */
    private int conversationHistoryBudget;

    /**
     * 扩展画像预算
     */
    private int extendedProfileBudget;

    /**
     * 画像总预算
     */
    private int profileBudget;

    /**
     * 用户消息预留
     */
    private int userMessageReserve;

    /**
     * 系统 Prompt 可用预算
     */
    private int systemPromptBudget;

    /**
     * 工具结果预算
     */
    private int toolResultBudget;

    /**
     * 总预算
     */
    private int totalBudget;

    /**
     * 实际使用
     */
    private int actualUsage;

    /**
     * 是否需要压缩
     */
    private boolean needsCompression;
}
