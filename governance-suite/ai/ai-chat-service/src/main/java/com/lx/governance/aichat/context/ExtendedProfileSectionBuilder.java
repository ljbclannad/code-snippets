package com.lx.governance.aichat.context;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ExtendedProfileSectionBuilder implements PromptSectionBuilder {

    private final PromptSectionSupport support;

    @Override
    public int getOrder() {
        return 60;
    }

    @Override
    public PromptSectionResult build(PromptSectionContext context) {
        if (context.getRemainingBudget() <= 0) {
            return PromptSectionResult.empty(PromptSectionType.EXTENDED_PROFILE);
        }

        int sectionBudget = Math.min(
                context.getBudgetAllocation().getExtendedProfileBudget(),
                context.getRemainingBudget());
        String content = support.extractExtendedProfile(
                context.getInput().getProfile(),
                context.getInput().getUserQuestion(),
                sectionBudget);

        if (content.isBlank()) {
            return PromptSectionResult.empty(PromptSectionType.EXTENDED_PROFILE);
        }

        return PromptSectionResult.builder()
                .type(PromptSectionType.EXTENDED_PROFILE)
                .title("## 补充信息")
                .content(content)
                .usedTokens(support.estimateTokens(content))
                .build();
    }
}
