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
                context.getBudgetAllocation().getShortTermMemoryBudget(),
                context.getRemainingBudget());
        String content = support.formatShortTermMemories(
                context.getInput().getLayeredMemories().getShortTermMemories(),
                sectionBudget);

        if (content.isBlank()) {
            return PromptSectionResult.empty(PromptSectionType.SHORT_TERM_MEMORY);
        }

        int usedTokens = support.estimateTokens(content);

        return PromptSectionResult.builder()
                .type(PromptSectionType.SHORT_TERM_MEMORY)
                .title("## 短期记忆")
                .content(content)
                .usedTokens(usedTokens)
                .allocatedTokens(sectionBudget)
                .originalTokens(usedTokens)
                .compressed(false)
                .build();
    }
}
