package com.lx.governance.aichat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 上下文预算配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.context")
public class ContextBudgetProperties {

    /** 总预算（tokens） */
    private int totalBudget = 4000;

    /** 为用户消息预留的 tokens */
    private int userMessageReserve = 500;

    /** 核心画像预算 */
    private int coreProfileBudget = 800;

    /** 相关记忆预算 */
    private int relevantMemoriesBudget = 1200;

    /** 对话历史预算 */
    private int conversationHistoryBudget = 600;

    /** 扩展画像预算 */
    private int extendedProfileBudget = 400;

    /** 记忆配置 */
    private MemoryConfig memory = new MemoryConfig();

    /** 对话配置 */
    private ConversationConfig conversation = new ConversationConfig();

    /** 告警配置 */
    private AlertConfig alert = new AlertConfig();

    /**
     * 记忆检索配置
     */
    @Data
    public static class MemoryConfig {
        /** 最多检索数量 */
        private int maxCount = 10;

        /** 相似度阈值 */
        private double similarityThreshold = 0.7;

        /** 短期记忆天数 */
        private int shortTermDays = 3;

        /** 中期记忆天数 */
        private int midTermDays = 30;

        /** 启用去重 */
        private boolean enableDeduplication = true;

        /** 检索 Query Rewrite 配置 */
        private RewriteConfig rewrite = new RewriteConfig();
    }

    @Data
    public static class RewriteConfig {
        /** 是否启用 LLM Query Rewrite */
        private boolean enabled = true;

        /** 是否将复杂度作为独立的模型改写触发条件 */
        private boolean complexOnly = false;

        /** 是否启用基于上下文依赖信号的 LLM Rewrite */
        private boolean contextualSignalsEnabled = true;

        /** 触发上下文依赖判断的短消息阈值 */
        private int shortMessageThreshold = 18;

        /** 模型温度 */
        private double temperature = 0.0;

        /** 最大输出 tokens */
        private int maxTokens = 64;

        /** 改写结果最大长度 */
        private int maxQueryLength = 80;
    }

    /**
     * 对话历史配置
     */
    @Data
    public static class ConversationConfig {
        /** 最多保留轮数 */
        private int maxRounds = 5;

        /** 是否启用对话历史 */
        private boolean enabled = true;
    }

    /**
     * 告警配置
     */
    @Data
    public static class AlertConfig {
        /** 是否启用告警 */
        private boolean enabled = true;

        /** Prompt tokens 告警阈值 */
        private int promptTokensThreshold = 6000;

        /** Total tokens 告警阈值 */
        private int totalTokensThreshold = 28000;
    }
}
