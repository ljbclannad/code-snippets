package com.lx.governance.aichat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.regex.Pattern;

/**
 * Unified chat generation result DTO.
 */
@Data
public class ChatGenerationResult {

    private String answer;

    @JsonProperty("conversation_id")
    private String conversationId;

    @JsonProperty("message_id")
    private String messageId;

    private Metadata metadata;

    /**
     * Metadata inner class
     */
    @Data
    public static class Metadata {
        /** 保留旧格式以兼容缓存和现有配额读取。新结果同时携带结构化用量。 */
        private String usage;
        private TokenUsage tokenUsage;
        private Integer retrieverResources;

        public void recordTokenUsage(TokenUsage tokenUsage) {
            this.tokenUsage = tokenUsage;
            this.usage = tokenUsage == null ? null : String.format(
                    "prompt_tokens=%s, completion_tokens=%s, total_tokens=%s",
                    tokenUsage.promptTokens(), tokenUsage.completionTokens(), tokenUsage.totalTokens());
        }

        public TokenUsage resolveTokenUsage() {
            return tokenUsage != null ? tokenUsage : new TokenUsage(
                    readLegacyCount("prompt_tokens"), readLegacyCount("completion_tokens"), readLegacyCount("total_tokens"));
        }

        private Integer readLegacyCount(String key) {
            if (usage == null) {
                return null;
            }
            var match = Pattern.compile("(?:^|,)\\s*" + key + "\\s*=\\s*(\\d+)\\s*(?=,|$)").matcher(usage);
            if (!match.find()) {
                return null;
            }
            try {
                return Integer.valueOf(match.group(1));
            } catch (NumberFormatException ex) {
                return null;
            }
        }
    }

    /** null 表示模型未提供或数据无效；不以 0 替代未知用量。 */
    public record TokenUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
        public static TokenUsage fromCounts(Number prompt, Number completion, Number total) {
            return new TokenUsage(validCount(prompt), validCount(completion), validCount(total));
        }

        public TokenUsage {
            promptTokens = validCount(promptTokens);
            completionTokens = validCount(completionTokens);
            totalTokens = validCount(totalTokens);
        }

        private static Integer validCount(Number value) {
            return value != null && value.longValue() >= 0 && value.longValue() <= Integer.MAX_VALUE
                    ? value.intValue() : null;
        }
    }
}
