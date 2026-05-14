package com.lx.governance.aichat.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
    "ai.context.total-budget=4000",
    "ai.context.user-message-reserve=500",
    "ai.context.core-profile-budget=800",
    "ai.context.relevant-memories-budget=1200",
    "ai.context.conversation-history-budget=600",
    "ai.context.extended-profile-budget=400",
    "ai.context.memory.max-count=10",
    "ai.context.memory.similarity-threshold=0.7",
    "ai.context.memory.short-term-days=3",
    "ai.context.memory.mid-term-days=30",
    "ai.context.memory.enable-deduplication=true",
    "ai.context.conversation.max-rounds=5",
    "ai.context.conversation.enabled=true",
    "ai.context.alert.enabled=true",
    "ai.context.alert.prompt-tokens-threshold=6000",
    "ai.context.alert.total-tokens-threshold=28000"
})
class ContextBudgetPropertiesTest {

    @Autowired
    private ContextBudgetProperties properties;

    @Test
    void shouldLoadBudgetConfiguration() {
        assertThat(properties.getTotalBudget()).isEqualTo(4000);
        assertThat(properties.getUserMessageReserve()).isEqualTo(500);
        assertThat(properties.getCoreProfileBudget()).isEqualTo(800);
        assertThat(properties.getRelevantMemoriesBudget()).isEqualTo(1200);
        assertThat(properties.getConversationHistoryBudget()).isEqualTo(600);
        assertThat(properties.getExtendedProfileBudget()).isEqualTo(400);
    }

    @Test
    void shouldLoadMemoryConfiguration() {
        ContextBudgetProperties.MemoryConfig memory = properties.getMemory();
        assertThat(memory.getMaxCount()).isEqualTo(10);
        assertThat(memory.getSimilarityThreshold()).isEqualTo(0.7);
        assertThat(memory.getShortTermDays()).isEqualTo(3);
        assertThat(memory.getMidTermDays()).isEqualTo(30);
        assertThat(memory.isEnableDeduplication()).isTrue();
    }

    @Test
    void shouldLoadConversationConfiguration() {
        ContextBudgetProperties.ConversationConfig conversation = properties.getConversation();
        assertThat(conversation.getMaxRounds()).isEqualTo(5);
        assertThat(conversation.isEnabled()).isTrue();
    }

    @Test
    void shouldLoadAlertConfiguration() {
        ContextBudgetProperties.AlertConfig alert = properties.getAlert();
        assertThat(alert.isEnabled()).isTrue();
        assertThat(alert.getPromptTokensThreshold()).isEqualTo(6000);
        assertThat(alert.getTotalTokensThreshold()).isEqualTo(28000);
    }

    @Test
    void shouldUseDefaultValues() {
        ContextBudgetProperties defaultProps = new ContextBudgetProperties();
        assertThat(defaultProps.getTotalBudget()).isEqualTo(4000);
        assertThat(defaultProps.getMemory().getMaxCount()).isEqualTo(10);
        assertThat(defaultProps.getConversation().getMaxRounds()).isEqualTo(5);
        assertThat(defaultProps.getAlert().isEnabled()).isTrue();
    }
}
