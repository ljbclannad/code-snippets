package com.lx.governance.aichat.context;

import com.lx.governance.aichat.exception.ConversationException;
import com.lx.governance.aichat.exception.ErrorCode;
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
        String opening = "你是一个专业的健康顾问助手。请根据以下用户信息提供个性化的健康建议。\n\n";
        String closing = "\n请基于以上信息，提供专业、个性化的健康建议。";
        prompt.append(opening);

        String complexity = input.getSessionContext() != null && input.getSessionContext().getComplexity() != null
                ? input.getSessionContext().getComplexity()
                : "medium";

        BudgetAllocation budgetAllocation = promptBudgetPolicy.resolve(complexity, PromptTokenBudget.estimate(input.getUserQuestion()));
        int fixedTokens = PromptTokenBudget.estimate(opening) + PromptTokenBudget.estimate(closing);
        int remainingBudget = budgetAllocation.getSystemPromptBudget() - fixedTokens;
        if (remainingBudget < 0) {
            throw new ConversationException(ErrorCode.MESSAGE_TOO_LONG, "消息内容超过本轮预算，请分段发送");
        }
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

            String heading = result.getTitle() + "\n";
            int overhead = PromptTokenBudget.estimate(heading) + PromptTokenBudget.estimate("\n");
            String content = PromptTokenBudget.fit(result.getContent(), remainingBudget - overhead);
            if (content.isBlank()) {
                continue;
            }
            prompt.append(heading).append(content).append("\n");
            remainingBudget -= overhead + PromptTokenBudget.estimate(content);
            recordSectionUsage(result, PromptTokenBudget.estimate(content));
            appendSectionTrace(sectionTrace, result);
            log.debug("Prompt section built - type: {}, tokens: {}, remaining: {}",
                    result.getType(), result.getUsedTokens(), remainingBudget);
        }

        prompt.append(closing);

        lastTotalTokens = PromptTokenBudget.estimate(prompt.toString());
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

    private void recordSectionUsage(PromptSectionResult result, int usedTokens) {
        switch (result.getType()) {
            case CORE_PROFILE:
                lastCoreProfileTokens = usedTokens;
                break;
            case SESSION_CONTEXT:
                lastSessionContextTokens = usedTokens;
                break;
            case SHORT_TERM_MEMORY:
            case LONG_TERM_MEMORY:
            case RELEVANT_MEMORY:
                lastMemoriesTokens += usedTokens;
                break;
            case CONVERSATION_HISTORY:
                lastConversationTokens = usedTokens;
                break;
            case EXTENDED_PROFILE:
                lastExtendedProfileTokens = usedTokens;
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
