package com.lx.governance.aichat.util;

import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 消息验证工具类
 * 用于判断用户消息是否需要进行记忆检索
 */
@Slf4j
public class MessageValidator {

    /**
     * 常见寒暄语列表（不需要记忆检索）
     */
    private static final Set<String> GREETINGS = new HashSet<>(Arrays.asList(
        "你好", "您好", "hi", "hello", "hey",
        "在吗", "在不在", "在么",
        "哈哈", "呵呵", "嘿嘿",
        "嗯", "哦", "啊", "哎",
        "好的", "好", "行", "可以",
        "谢谢", "多谢", "感谢",
        "再见", "拜拜", "bye",
        "早", "早上好", "晚安",
        "？", "?", "！", "!"
    ));

    /**
     * 最小消息长度阈值
     */
    private static final int MIN_MESSAGE_LENGTH = 3;

    /**
     * 基于规则判断是否需要记忆检索
     *
     * @param message 用户消息
     * @return true 需要检索，false 不需要检索
     */
    public static boolean shouldRetrieveMemoriesByRule(String message) {
        if (message == null || message.trim().isEmpty()) {
            log.debug("消息为空，跳过记忆检索");
            return false;
        }

        String trimmed = message.trim();

        // 规则 1：长度过短（<= 2 个字符）
        if (trimmed.length() <= MIN_MESSAGE_LENGTH - 1) {
            log.debug("消息长度过短（{}），跳过记忆检索: {}", trimmed.length(), trimmed);
            return false;
        }

        // 规则 2：纯数字或纯符号
        if (trimmed.matches("^[0-9\\s\\p{Punct}]+$")) {
            log.debug("消息为纯数字或符号，跳过记忆检索: {}", trimmed);
            return false;
        }

        // 规则 3：常见寒暄语
        String lowerCase = trimmed.toLowerCase();
        if (GREETINGS.contains(lowerCase)) {
            log.debug("消息为常见寒暄语，跳过记忆检索: {}", trimmed);
            return false;
        }

        // 规则 4：纯表情符号（Unicode 表情范围）
        if (trimmed.matches("^[\\p{So}\\p{Cn}\\s]+$")) {
            log.debug("消息为纯表情符号，跳过记忆检索: {}", trimmed);
            return false;
        }

        // 规则 5：重复字符（如：哈哈哈哈哈、。。。。。）
        if (isRepeatingPattern(trimmed)) {
            log.debug("消息为重复字符，跳过记忆检索: {}", trimmed);
            return false;
        }

        return true;
    }

    /**
     * 判断是否为重复字符模式
     * 例如：哈哈哈哈哈、。。。。。、aaaa
     */
    private static boolean isRepeatingPattern(String message) {
        if (message.length() < 4) {
            return false;
        }

        // 检查是否由单个字符重复组成
        char firstChar = message.charAt(0);
        boolean allSame = true;
        for (int i = 1; i < message.length(); i++) {
            if (message.charAt(i) != firstChar) {
                allSame = false;
                break;
            }
        }

        if (allSame) {
            return true;
        }

        // 检查是否由两个字符交替重复（如：哈哈哈哈）
        if (message.length() >= 4) {
            String pattern = message.substring(0, 2);
            boolean isAlternating = true;
            for (int i = 2; i < message.length(); i += 2) {
                if (i + 1 < message.length()) {
                    String current = message.substring(i, i + 2);
                    if (!current.equals(pattern)) {
                        isAlternating = false;
                        break;
                    }
                }
            }
            if (isAlternating) {
                return true;
            }
        }

        return false;
    }

    /**
     * 混合判断：规则 + 意图分析
     *
     * @param message 用户消息
     * @param complexity 对话复杂度（来自意图分析）
     * @return true 需要检索，false 不需要检索
     */
    public static boolean shouldRetrieveMemories(String message, String complexity) {
        // 第一层：规则快速过滤
        if (!shouldRetrieveMemoriesByRule(message)) {
            return false;
        }

        // 第二层：基于意图分析的复杂度判断
        if ("simple".equalsIgnoreCase(complexity)) {
            log.debug("消息复杂度为 simple，跳过记忆检索: {}", message);
            return false;
        }

        // 复杂问题或中等复杂度：需要记忆检索
        return true;
    }
}
