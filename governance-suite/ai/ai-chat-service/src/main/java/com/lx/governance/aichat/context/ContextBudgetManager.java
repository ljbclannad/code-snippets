package com.lx.governance.aichat.context;

import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

import com.lx.governance.aichat.config.ContextBudgetProperties;
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

    private final ContextBudgetProperties properties;
    private final DynamicBudgetStrategy dynamicBudgetStrategy;
    private final List<PromptSectionBuilder> sectionBuilders;

    private int lastCoreProfileTokens = 0;
    private int lastSessionContextTokens = 0;
    private int lastMemoriesTokens = 0;
    private int lastConversationTokens = 0;
    private int lastExtendedProfileTokens = 0;
    private int lastTotalTokens = 0;

    public ContextBudgetManager(
            ContextBudgetProperties properties,
            DynamicBudgetStrategy dynamicBudgetStrategy,
            List<PromptSectionBuilder> sectionBuilders) {
        this.properties = properties;
        this.dynamicBudgetStrategy = dynamicBudgetStrategy;
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

        BudgetAllocation budgetAllocation = dynamicBudgetStrategy.calculateBudget(complexity, buildBaseBudget());
        int remainingBudget = budgetAllocation.getTotalBudget();

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
            log.debug("Prompt section built - type: {}, tokens: {}, remaining: {}",
                    result.getType(), result.getUsedTokens(), remainingBudget);
        }

        prompt.append("\n请基于以上信息，提供专业、个性化的健康建议。");

        lastTotalTokens = budgetAllocation.getTotalBudget() - remainingBudget;
        long duration = System.currentTimeMillis() - startTime;
        log.info("上下文构建完成 - 总 tokens: {}/{}, 剩余预算: {}, 耗时: {}ms",
                lastTotalTokens, budgetAllocation.getTotalBudget(), remainingBudget, duration);

        return prompt.toString();
    }

    private DynamicBudgetStrategy.BaseBudgetConfig buildBaseBudget() {
        DynamicBudgetStrategy.BaseBudgetConfig baseBudget = new DynamicBudgetStrategy.BaseBudgetConfig();
        baseBudget.setCoreProfileBudget(properties.getCoreProfileBudget());
        baseBudget.setRelevantMemoriesBudget(properties.getRelevantMemoriesBudget());
        baseBudget.setConversationHistoryBudget(properties.getConversationHistoryBudget());
        baseBudget.setExtendedProfileBudget(properties.getExtendedProfileBudget());
        return baseBudget;
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
