package com.medical.report.manager;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Slf4j
@Component
public class ContextWindowManager {

    private static final int MAX_TOKENS = 128000; // Claude 3.5 Sonnet context window
    private static final int SAFE_TOKEN_LIMIT = 100000; // Leave buffer for response
    private static final double CHARS_PER_TOKEN = 4.0; // Approximate ratio
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    public String optimizeContent(String content, int maxTokens) {
        if (content == null || content.isEmpty()) {
            return content;
        }

        int estimatedTokens = estimateTokens(content);

        if (estimatedTokens <= maxTokens) {
            log.debug("Content within limit: {} tokens (max: {})", estimatedTokens, maxTokens);
            return content;
        }

        log.info("Content exceeds limit: {} tokens (max: {}), truncating", estimatedTokens, maxTokens);
        return truncateToTokenLimit(content, maxTokens);
    }

    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
    }

    public String truncateToTokenLimit(String content, int maxTokens) {
        int maxChars = (int) (maxTokens * CHARS_PER_TOKEN);

        if (content.length() <= maxChars) {
            return content;
        }

        String truncated = content.substring(0, maxChars);
        int lastNewline = truncated.lastIndexOf('\n');

        if (lastNewline > maxChars * 0.9) {
            truncated = truncated.substring(0, lastNewline);
        }

        log.debug("Truncated content from {} to {} chars (~{} tokens)",
                content.length(), truncated.length(), estimateTokens(truncated));

        return truncated + "\n\n[内容已截断以适应上下文窗口限制]";
    }

    public String compressWhitespace(String content) {
        if (content == null) {
            return null;
        }
        return WHITESPACE.matcher(content).replaceAll(" ").trim();
    }

    public String summarizeIfNeeded(String content, int targetTokens) {
        int currentTokens = estimateTokens(content);

        if (currentTokens <= targetTokens) {
            return content;
        }

        String[] sections = content.split("\n\n");
        StringBuilder result = new StringBuilder();
        int accumulatedTokens = 0;

        for (String section : sections) {
            int sectionTokens = estimateTokens(section);

            if (accumulatedTokens + sectionTokens <= targetTokens) {
                result.append(section).append("\n\n");
                accumulatedTokens += sectionTokens;
            } else {
                int remainingTokens = targetTokens - accumulatedTokens;
                if (remainingTokens > 50) {
                    result.append(truncateToTokenLimit(section, remainingTokens));
                }
                break;
            }
        }

        log.info("Summarized content from {} to {} tokens", currentTokens, accumulatedTokens);
        return result.toString();
    }

    public boolean isWithinLimit(String content) {
        return estimateTokens(content) <= SAFE_TOKEN_LIMIT;
    }

    public int getMaxTokens() {
        return MAX_TOKENS;
    }

    public int getSafeTokenLimit() {
        return SAFE_TOKEN_LIMIT;
    }
}
