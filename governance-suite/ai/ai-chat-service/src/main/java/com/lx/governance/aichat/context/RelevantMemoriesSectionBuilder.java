package com.lx.governance.aichat.context;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class RelevantMemoriesSectionBuilder implements PromptSectionBuilder {

    private final PromptSectionSupport support;

    @Override
    public int getOrder() {
        return 40;
    }

    @Override
    public PromptSectionResult build(PromptSectionContext context) {
        if (context.getInput().hasLayeredMemories() || context.getRemainingBudget() <= 0) {
            return PromptSectionResult.empty(PromptSectionType.RELEVANT_MEMORY);
        }

        int sectionBudget = Math.min(
                context.getBudgetAllocation().getLongTermMemoryBudget(),
                context.getRemainingBudget());
        String content = support.selectRelevantMemories(context.getInput().getMemories(), sectionBudget);

        if (content.isBlank()) {
            return PromptSectionResult.empty(PromptSectionType.RELEVANT_MEMORY);
        }

        int usedTokens = support.estimateTokens(content);

        return PromptSectionResult.builder()
                .type(PromptSectionType.RELEVANT_MEMORY)
                .title("## 相关历史记忆")
                .content(content)
                .usedTokens(usedTokens)
                .allocatedTokens(sectionBudget)
                .originalTokens(usedTokens)
                .compressed(false)
                .build();
    }
}
