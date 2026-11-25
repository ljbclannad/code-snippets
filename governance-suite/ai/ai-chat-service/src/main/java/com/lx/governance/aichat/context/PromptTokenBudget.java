package com.lx.governance.aichat.context;

/** 提示词各部分共用的字符估算及裁剪规则，实际计费使用模型返回的用量。 */
public final class PromptTokenBudget {
    private PromptTokenBudget() {}

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        double tokens = 0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            tokens += codePoint >= 0x4E00 && codePoint <= 0x9FA5 ? 1.5 : 0.25;
            offset += Character.charCount(codePoint);
        }
        return (int) Math.ceil(tokens);
    }

    public static String fit(String text, int budget) {
        if (budget <= 0 || text == null) {
            return "";
        }
        if (estimate(text) <= budget) {
            return text;
        }
        String suffix = "…";
        int remaining = budget - estimate(suffix);
        double used = 0;
        int end = 0;
        while (end < text.length()) {
            int codePoint = text.codePointAt(end);
            double cost = codePoint >= 0x4E00 && codePoint <= 0x9FA5 ? 1.5 : 0.25;
            if (used + cost > remaining) {
                break;
            }
            used += cost;
            end += Character.charCount(codePoint);
        }
        return text.substring(0, end) + suffix;
    }
}
