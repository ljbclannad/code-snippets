package com.lx.governance.aichat.context;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@Slf4j
public class ContentCompressor {

    private static final int CHARS_PER_TOKEN = 2;

    /**
     * 压缩文本到目标token数
     */
    public String compressToTokens(String text, int targetTokens) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        int currentTokens = estimateTokens(text);

        if (currentTokens <= targetTokens) {
            return text;
        }

        // 简单截断策略
        int targetChars = targetTokens * CHARS_PER_TOKEN;
        if (text.length() <= targetChars) {
            return text;
        }

        return text.substring(0, targetChars) + "...";
    }

    /**
     * 压缩列表内容
     */
    public List<String> compressListToTokens(List<String> items, int targetTokens) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }

        int tokensPerItem = targetTokens / items.size();

        return items.stream()
            .map(item -> compressToTokens(item, tokensPerItem))
            .collect(Collectors.toList());
    }

    /**
     * 估算token数量
     */
    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        // 简化估算：中文1字符≈1token，英文1字符≈0.5token
        int chineseCount = 0;
        int otherCount = 0;

        for (char c : text.toCharArray()) {
            if (isChinese(c)) {
                chineseCount++;
            } else {
                otherCount++;
            }
        }

        return chineseCount + (otherCount / 2);
    }

    /**
     * 判断是否为中文字符
     */
    private boolean isChinese(char c) {
        return c >= 0x4E00 && c <= 0x9FA5;
    }

    /**
     * 智能摘要（保留关键信息）
     */
    public String smartSummarize(String text, int targetTokens) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        int currentTokens = estimateTokens(text);

        if (currentTokens <= targetTokens) {
            return text;
        }

        // 按句子分割
        String[] sentences = text.split("[。！？\\n]");

        if (sentences.length <= 1) {
            return compressToTokens(text, targetTokens);
        }

        // 保留前几句和最后一句
        StringBuilder result = new StringBuilder();
        int tokensUsed = 0;
        int tokensPerSentence = targetTokens / Math.min(3, sentences.length);

        // 添加前两句
        for (int i = 0; i < Math.min(2, sentences.length) && tokensUsed < targetTokens; i++) {
            String sentence = sentences[i].trim();
            if (!sentence.isEmpty()) {
                int sentenceTokens = estimateTokens(sentence);
                if (tokensUsed + sentenceTokens <= targetTokens) {
                    result.append(sentence).append("。");
                    tokensUsed += sentenceTokens;
                }
            }
        }

        // 添加最后一句
        if (sentences.length > 2 && tokensUsed < targetTokens) {
            String lastSentence = sentences[sentences.length - 1].trim();
            if (!lastSentence.isEmpty()) {
                int sentenceTokens = estimateTokens(lastSentence);
                if (tokensUsed + sentenceTokens <= targetTokens) {
                    result.append("...").append(lastSentence).append("。");
                }
            }
        }

        return result.toString();
    }
}
