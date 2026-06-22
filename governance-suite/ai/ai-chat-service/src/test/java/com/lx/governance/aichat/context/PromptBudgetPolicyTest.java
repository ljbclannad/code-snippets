package com.lx.governance.aichat.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.lx.governance.aichat.config.ContextBudgetProperties;

class PromptBudgetPolicyTest {

    @Test
    void shouldSplitPromptBudgetIntoSectionLevelBudgets() {
        ContextBudgetProperties properties = new ContextBudgetProperties();
        properties.setTotalBudget(4000);
        properties.setUserMessageReserve(500);
        properties.setCoreProfileBudget(800);
        properties.setRelevantMemoriesBudget(1200);
        properties.setConversationHistoryBudget(600);
        properties.setExtendedProfileBudget(400);

        PromptBudgetPolicy policy = new PromptBudgetPolicy(properties, new DynamicBudgetStrategy());

        BudgetAllocation allocation = policy.resolve("medium");

        assertEquals(3500, allocation.getSystemPromptBudget());
        assertEquals(500, allocation.getUserMessageReserve());
        assertEquals(240, allocation.getSessionContextBudget());
        assertEquals(360, allocation.getConversationHistoryBudget());
        assertEquals(420, allocation.getShortTermMemoryBudget());
        assertEquals(780, allocation.getLongTermMemoryBudget());
        assertEquals(1200, allocation.getMemoryBudget());
        assertEquals(1200, allocation.getProfileBudget());
        assertTrue(allocation.getToolResultBudget() >= 0);
    }
}
