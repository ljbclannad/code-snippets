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
     * 扩展画像预算
     */
    private int extendedProfileBudget;

    /**
     * 用户消息预留
     */
    private int userMessageReserve;

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
