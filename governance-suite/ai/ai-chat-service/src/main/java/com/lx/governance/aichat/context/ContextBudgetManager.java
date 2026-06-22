package com.lx.governance.aichat.context;

import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

import com.lx.governance.aichat.dto.LayeredMemories;
import com.lx.governance.aichat.dto.MemoryItemDTO;
import com.lx.governance.aichat.dto.SessionContext;
import com.lx.governance.aichat.dto.UserProfileDTO;

import lombok.extern.slf4j.Slf4j;

/**
 * 上下文预算管理器
 * 负责在 token 预算约束下，按 section pipeline 顺序组织上下文信息
 *
 * @author LeJinBo
 * @since 2026-05-14
 */
@Slf4j
@Component
public class ContextBudgetManager {

    private final PromptBudgetPolicy promptBudgetPolicy;
    private final List<PromptSectionBuilder> sectionBuilders;

    private int lastCoreProfileTokens = 0;
    private int lastSessionContextTokens = 0;
    private int lastMemoriesTokens = 0;
    private int lastConversationTokens = 0;
    private int lastExtendedProfileTokens = 0;
    private int lastTotalTokens = 0;

    public ContextBudgetManager(
            PromptBudgetPolicy promptBudgetPolicy,
            List<PromptSectionBuilder> sectionBuilders) {
        this.promptBudgetPolicy = promptBudgetPolicy;
        this.sectionBuilders = sectionBuilders.stream()
                .sorted(Comparator.comparingInt(PromptSectionBuilder::getOrder))
                .toList();
    }

    public String buildBudgetedSystemPrompt(
            UserProfileDTO profile,
            SessionContext sessionContext,
            List<MemoryItemDTO> memories,
            String customerId,
            String conversationId,
            String userQuestion) {
        return buildPrompt(PromptBuildInput.builder()
                .profile(profile)
                .sessionContext(sessionContext)
                .memories(memories)
                .customerId(customerId)
                .conversationId(conversationId)
                .userQuestion(userQuestion)
                .build());
    }

    public String buildBudgetedSystemPrompt(
            UserProfileDTO profile,
            SessionContext sessionContext,
            LayeredMemories layeredMemories,
            String customerId,
            String conversationId,
            String userQuestion) {
        return buildPrompt(PromptBuildInput.builder()
                .profile(profile)
                .sessionContext(sessionContext)
                .layeredMemories(layeredMemories)
                .customerId(customerId)
                .conversationId(conversationId)
                .userQuestion(userQuestion)
                .build());
    }

    private String buildPrompt(PromptBuildInput input) {
        long startTime = System.currentTimeMillis();
        resetTokenCounters();

        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个专业的健康顾问助手。请根据以下用户信息提供个性化的健康建议。\n\n");

        String complexity = input.getSessionContext() != null && input.getSessionContext().getComplexity() != null
                ? input.getSessionContext().getComplexity()
                : "medium";

        BudgetAllocation budgetAllocation = promptBudgetPolicy.resolve(complexity);
        int remainingBudget = budgetAllocation.getSystemPromptBudget();
        StringBuilder sectionTrace = new StringBuilder();

        for (PromptSectionBuilder sectionBuilder : sectionBuilders) {
            if (remainingBudget <= 0) {
                break;
            }

            PromptSectionResult result = sectionBuilder.build(PromptSectionContext.builder()
                    .input(input)
                    .budgetAllocation(budgetAllocation)
                    .remainingBudget(remainingBudget)
                    .build());

            if (result.isEmpty()) {
                continue;
            }

            prompt.append(result.getTitle()).append("\n")
                    .append(result.getContent()).append("\n");
            remainingBudget -= result.getUsedTokens();
            recordSectionUsage(result);
            appendSectionTrace(sectionTrace, result);
            log.debug("Prompt section built - type: {}, tokens: {}, remaining: {}",
                    result.getType(), result.getUsedTokens(), remainingBudget);
        }

        prompt.append("\n请基于以上信息，提供专业、个性化的健康建议。");

        lastTotalTokens = budgetAllocation.getSystemPromptBudget() - remainingBudget;
        long duration = System.currentTimeMillis() - startTime;
        log.info(
                "上下文构建完成 - usedTokens: {}/{}, totalBudget: {}, userMessageReserve: {}, remainingBudget: {}, sections: [{}], 耗时: {}ms",
                lastTotalTokens,
                budgetAllocation.getSystemPromptBudget(),
                budgetAllocation.getTotalBudget(),
                budgetAllocation.getUserMessageReserve(),
                remainingBudget,
                sectionTrace,
                duration);

        return prompt.toString();
    }

    private void recordSectionUsage(PromptSectionResult result) {
        switch (result.getType()) {
            case CORE_PROFILE:
                lastCoreProfileTokens = result.getUsedTokens();
                break;
            case SESSION_CONTEXT:
                lastSessionContextTokens = result.getUsedTokens();
                break;
            case SHORT_TERM_MEMORY:
            case LONG_TERM_MEMORY:
            case RELEVANT_MEMORY:
                lastMemoriesTokens += result.getUsedTokens();
                break;
            case CONVERSATION_HISTORY:
                lastConversationTokens = result.getUsedTokens();
                break;
            case EXTENDED_PROFILE:
                lastExtendedProfileTokens = result.getUsedTokens();
                break;
            default:
                break;
        }
    }

    private void resetTokenCounters() {
        lastCoreProfileTokens = 0;
        lastSessionContextTokens = 0;
        lastMemoriesTokens = 0;
        lastConversationTokens = 0;
        lastExtendedProfileTokens = 0;
        lastTotalTokens = 0;
    }

    private void appendSectionTrace(StringBuilder sectionTrace, PromptSectionResult result) {
        if (sectionTrace.length() > 0) {
            sectionTrace.append("; ");
        }
        sectionTrace.append(result.getType())
                .append("{alloc=").append(result.getAllocatedTokens())
                .append(",raw=").append(result.getOriginalTokens())
                .append(",used=").append(result.getUsedTokens())
                .append(",chars=").append(result.getContent() != null ? result.getContent().length() : 0)
                .append(",compressed=").append(result.isCompressed())
                .append("}");
    }

    public int getLastCoreProfileTokens() {
        return lastCoreProfileTokens;
    }

    public int getLastSessionContextTokens() {
        return lastSessionContextTokens;
    }

    public int getLastMemoriesTokens() {
        return lastMemoriesTokens;
    }

    public int getLastConversationTokens() {
        return lastConversationTokens;
    }

    public int getLastExtendedProfileTokens() {
        return lastExtendedProfileTokens;
    }

    public int getLastTotalTokens() {
        return lastTotalTokens;
    }
}
