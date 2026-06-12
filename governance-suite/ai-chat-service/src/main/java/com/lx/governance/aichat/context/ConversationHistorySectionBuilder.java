package com.lx.governance.aichat.context;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ConversationHistorySectionBuilder implements PromptSectionBuilder {

    private final PromptSectionSupport support;

    @Override
    public int getOrder() {
        return 50;
    }

    @Override
    public PromptSectionResult build(PromptSectionContext context) {
        if (context.getRemainingBudget() <= 0) {
            return PromptSectionResult.empty(PromptSectionType.CONVERSATION_HISTORY);
        }

        int sectionBudget = Math.min(
                context.getBudgetAllocation().getSessionContextBudget(),
                context.getRemainingBudget());
        String content = support.buildConversationHistory(
                context.getInput().getCustomerId(),
                context.getInput().getConversationId(),
                sectionBudget);

        if (content.isBlank()) {
            return PromptSectionResult.empty(PromptSectionType.CONVERSATION_HISTORY);
        }

        return PromptSectionResult.builder()
                .type(PromptSectionType.CONVERSATION_HISTORY)
                .title("## 对话历史")
                .content(content)
                .usedTokens(support.estimateTokens(content))
                .build();
    }
}
