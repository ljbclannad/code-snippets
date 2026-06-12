package com.lx.governance.aichat.context;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class CoreProfileSectionBuilder implements PromptSectionBuilder {

    private final PromptSectionSupport support;

    @Override
    public int getOrder() {
        return 10;
    }

    @Override
    public PromptSectionResult build(PromptSectionContext context) {
        String coreProfile = support.extractCoreProfile(context.getInput().getProfile());
        if (coreProfile.isBlank()) {
            return PromptSectionResult.empty(PromptSectionType.CORE_PROFILE);
        }

        int sectionBudget = context.getBudgetAllocation().getCoreProfileBudget();
        int estimatedTokens = support.estimateTokens(coreProfile);
        String content = estimatedTokens <= sectionBudget
                ? coreProfile
                : support.compressText(coreProfile, sectionBudget);
        int usedTokens = Math.min(support.estimateTokens(content), sectionBudget);

        return PromptSectionResult.builder()
                .type(PromptSectionType.CORE_PROFILE)
                .title("## 用户核心信息")
                .content(content)
                .usedTokens(usedTokens)
                .build();
    }
}
