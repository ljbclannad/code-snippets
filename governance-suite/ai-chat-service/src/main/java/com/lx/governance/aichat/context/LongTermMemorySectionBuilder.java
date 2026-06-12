package com.lx.governance.aichat.context;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class LongTermMemorySectionBuilder implements PromptSectionBuilder {

    private final PromptSectionSupport support;

    @Override
    public int getOrder() {
        return 40;
    }

    @Override
    public PromptSectionResult build(PromptSectionContext context) {
        if (!context.getInput().hasLayeredMemories() || context.getRemainingBudget() <= 0) {
            return PromptSectionResult.empty(PromptSectionType.LONG_TERM_MEMORY);
        }

        int sectionBudget = Math.min(
                context.getBudgetAllocation().getMemoryBudget(),
                context.getRemainingBudget());
        String content = support.selectRelevantMemories(
                context.getInput().getLayeredMemories().getLongTermMemories(),
                sectionBudget);

        if (content.isBlank()) {
            return PromptSectionResult.empty(PromptSectionType.LONG_TERM_MEMORY);
        }

        return PromptSectionResult.builder()
                .type(PromptSectionType.LONG_TERM_MEMORY)
                .title("## 长期记忆")
                .content(content)
                .usedTokens(support.estimateTokens(content))
                .build();
    }
}
