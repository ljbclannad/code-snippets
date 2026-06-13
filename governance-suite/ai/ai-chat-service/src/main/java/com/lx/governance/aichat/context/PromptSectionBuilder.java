package com.lx.governance.aichat.context;

public interface PromptSectionBuilder {

    int getOrder();

    PromptSectionResult build(PromptSectionContext context);
}
