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
    int allocatedTokens;
    int originalTokens;
    boolean compressed;

    public boolean isEmpty() {
        return content == null || content.isBlank();
    }

    public static PromptSectionResult empty(PromptSectionType type) {
        return PromptSectionResult.builder()
                .type(type)
                .title("")
                .content("")
                .usedTokens(0)
                .allocatedTokens(0)
                .originalTokens(0)
                .compressed(false)
                .build();
    }
}
