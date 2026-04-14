package com.medical.report.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenUsageInfo {
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer totalTokens;
    private Double estimatedCost;
    private String model;
    private Long responseTimeMs;

    public static TokenUsageInfo fromMetadata(org.springframework.ai.chat.metadata.ChatResponseMetadata metadata, long responseTimeMs) {
        if (metadata == null || metadata.getUsage() == null) {
            return TokenUsageInfo.builder()
                    .inputTokens(0)
                    .outputTokens(0)
                    .totalTokens(0)
                    .estimatedCost(0.0)
                    .responseTimeMs(responseTimeMs)
                    .build();
        }

        var usage = metadata.getUsage();
        int inputTokens = usage.getPromptTokens() != null ? usage.getPromptTokens().intValue() : 0;
        int outputTokens = usage.getGenerationTokens() != null ? usage.getGenerationTokens().intValue() : 0;
        int totalTokens = usage.getTotalTokens() != null ? usage.getTotalTokens().intValue() : 0;

        // DeepSeek pricing: $0.14/1M input tokens, $0.28/1M output tokens
        double cost = (inputTokens * 0.14 / 1_000_000) + (outputTokens * 0.28 / 1_000_000);

        return TokenUsageInfo.builder()
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .totalTokens(totalTokens)
                .estimatedCost(cost)
                .model(metadata.getModel() != null ? metadata.getModel() : "deepseek-chat")
                .responseTimeMs(responseTimeMs)
                .build();
    }
}
