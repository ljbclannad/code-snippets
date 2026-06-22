package com.lx.governance.aichat.context;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class SessionContextSectionBuilder implements PromptSectionBuilder {

    private final PromptSectionSupport support;

    @Override
    public int getOrder() {
        return 20;
    }

    @Override
    public PromptSectionResult build(PromptSectionContext context) {
        if (context.getInput().getSessionContext() == null || context.getRemainingBudget() <= 0) {
            return PromptSectionResult.empty(PromptSectionType.SESSION_CONTEXT);
        }

        int sectionBudget = Math.min(
                context.getBudgetAllocation().getSessionContextBudget(),
                context.getRemainingBudget());
        String content = support.formatSessionContext(context.getInput().getSessionContext());
        int estimatedTokens = support.estimateTokens(content);

        if (content.isBlank() || sectionBudget <= 0) {
            return PromptSectionResult.empty(PromptSectionType.SESSION_CONTEXT);
        }

        if (estimatedTokens > sectionBudget) {
            content = support.compressText(content, sectionBudget);
        }

        return PromptSectionResult.builder()
                .type(PromptSectionType.SESSION_CONTEXT)
                .title("## 会话上下文")
                .content(content)
                .usedTokens(support.estimateTokens(content))
                .allocatedTokens(sectionBudget)
                .originalTokens(estimatedTokens)
                .compressed(estimatedTokens > sectionBudget)
                .build();
    }
}
