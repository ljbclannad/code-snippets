package com.lx.governance.aichat.context;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class PromptSectionContext {
    PromptBuildInput input;
    BudgetAllocation budgetAllocation;
    int remainingBudget;
}
