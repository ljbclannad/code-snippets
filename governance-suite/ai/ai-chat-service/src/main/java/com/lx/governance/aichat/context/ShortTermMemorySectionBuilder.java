package com.lx.governance.aichat.context;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ShortTermMemorySectionBuilder implements PromptSectionBuilder {

    private final PromptSectionSupport support;

    @Override
    public int getOrder() {
        return 30;
    }

    @Override
    public PromptSectionResult build(PromptSectionContext context) {
        if (!context.getInput().hasLayeredMemories() || context.getRemainingBudget() <= 0) {
            return PromptSectionResult.empty(PromptSectionType.SHORT_TERM_MEMORY);
        }

        int sectionBudget = Math.min(
                context.getBudgetAllocation().getSessionContextBudget(),
                context.getRemainingBudget());
        String content = support.formatShortTermMemories(
                context.getInput().getLayeredMemories().getShortTermMemories(),
                sectionBudget);

        if (content.isBlank()) {
            return PromptSectionResult.empty(PromptSectionType.SHORT_TERM_MEMORY);
        }

        return PromptSectionResult.builder()
                .type(PromptSectionType.SHORT_TERM_MEMORY)
                .title("## 短期记忆")
                .content(content)
                .usedTokens(support.estimateTokens(content))
                .build();
    }
}
