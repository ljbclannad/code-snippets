package com.lx.governance.aichat.context;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class PromptSectionResult {

    PromptSectionType type;
    String title;
    String content;
    int usedTokens;

    public boolean isEmpty() {
        return content == null || content.isBlank();
    }

    public static PromptSectionResult empty(PromptSectionType type) {
        return PromptSectionResult.builder()
                .type(type)
                .title("")
                .content("")
                .usedTokens(0)
                .build();
    }
}
