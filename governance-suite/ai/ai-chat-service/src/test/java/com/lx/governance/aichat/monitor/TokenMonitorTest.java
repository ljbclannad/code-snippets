package com.lx.governance.aichat.monitor;

import com.lx.governance.aichat.config.ContextBudgetProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenMonitorTest {

    @Mock
    private ContextBudgetProperties properties;

    @InjectMocks
    private TokenMonitor tokenMonitor;

    @BeforeEach
    void setUp() {
        ContextBudgetProperties.AlertConfig alertConfig = new ContextBudgetProperties.AlertConfig();
        alertConfig.setEnabled(true);
        alertConfig.setPromptTokensThreshold(6000);
        alertConfig.setTotalTokensThreshold(28000);

        when(properties.getTotalBudget()).thenReturn(4000);
        when(properties.getUserMessageReserve()).thenReturn(500);
        when(properties.getAlert()).thenReturn(alertConfig);
    }

    @Test
    void shouldDetectOverBudget() {
        TokenUsageMetrics metrics = TokenUsageMetrics.builder()
                .customerId("user123")
                .conversationId("conv-abc")
                .timestamp(LocalDateTime.now())
                .systemPromptTokens(4000)  // 超过预算 (4000 - 500 = 3500)
                .promptTokens(4500)
                .completionTokens(300)
                .totalTokens(4800)
                .build();

        tokenMonitor.recordTokenUsage(metrics);

        assertThat(metrics.isOverBudget()).isTrue();
    }

    @Test
    void shouldNotDetectOverBudgetWhenWithinLimit() {
        TokenUsageMetrics metrics = TokenUsageMetrics.builder()
                .customerId("user123")
                .conversationId("conv-abc")
                .timestamp(LocalDateTime.now())
                .systemPromptTokens(3000)  // 未超预算
                .promptTokens(3500)
                .completionTokens(300)
                .totalTokens(3800)
                .build();

        tokenMonitor.recordTokenUsage(metrics);

        assertThat(metrics.isOverBudget()).isFalse();
    }

    @Test
    void shouldTriggerAlertWhenExceedingThreshold() {
        TokenUsageMetrics metrics = TokenUsageMetrics.builder()
                .customerId("user123")
                .conversationId("conv-abc")
                .timestamp(LocalDateTime.now())
                .systemPromptTokens(3000)
                .promptTokens(6500)  // 超过告警阈值 6000
                .completionTokens(300)
                .totalTokens(6800)
                .build();

        tokenMonitor.recordTokenUsage(metrics);

        assertThat(metrics.isShouldAlert()).isTrue();
    }

    @Test
    void shouldNotTriggerAlertWhenBelowThreshold() {
        TokenUsageMetrics metrics = TokenUsageMetrics.builder()
                .customerId("user123")
                .conversationId("conv-abc")
                .timestamp(LocalDateTime.now())
                .systemPromptTokens(3000)
                .promptTokens(5500)  // 未超过告警阈值
                .completionTokens(300)
                .totalTokens(5800)
                .build();

        tokenMonitor.recordTokenUsage(metrics);

        assertThat(metrics.isShouldAlert()).isFalse();
    }

    @Test
    void shouldNotTriggerAlertWhenDisabled() {
        ContextBudgetProperties.AlertConfig alertConfig = new ContextBudgetProperties.AlertConfig();
        alertConfig.setEnabled(false);
        when(properties.getAlert()).thenReturn(alertConfig);

        TokenUsageMetrics metrics = TokenUsageMetrics.builder()
                .customerId("user123")
                .conversationId("conv-abc")
                .timestamp(LocalDateTime.now())
                .systemPromptTokens(3000)
                .promptTokens(7000)  // 超过阈值但告警已禁用
                .completionTokens(300)
                .totalTokens(7300)
                .build();

        tokenMonitor.recordTokenUsage(metrics);

        assertThat(metrics.isShouldAlert()).isFalse();
    }
}
