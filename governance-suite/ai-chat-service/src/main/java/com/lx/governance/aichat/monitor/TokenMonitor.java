package com.lx.governance.aichat.monitor;

import com.lx.governance.aichat.config.ContextBudgetProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Token 使用监控器
 */
@Slf4j
@Component
public class TokenMonitor {

    @Autowired
    private ContextBudgetProperties properties;

    /**
     * 记录 token 使用
     */
    public void recordTokenUsage(TokenUsageMetrics metrics) {
        // 检查是否超预算
        int budget = properties.getTotalBudget() - properties.getUserMessageReserve();
        metrics.setOverBudget(metrics.getSystemPromptTokens() > budget);

        // 检查是否需要告警
        if (properties.getAlert().isEnabled()) {
            int threshold = properties.getAlert().getPromptTokensThreshold();
            metrics.setShouldAlert(metrics.getPromptTokens() > threshold);
        }

        // 记录详细日志
        logTokenUsage(metrics);

        // 如果需要告警，记录警告日志
        if (metrics.isShouldAlert()) {
            logAlert(metrics);
        }
    }

    /**
     * 记录详细日志
     */
    private void logTokenUsage(TokenUsageMetrics metrics) {
        log.info("Token 使用详情 - customerId: {}, conversationId: {}, " +
                "核心画像: {}, 记忆: {}, 对话: {}, 扩展: {}, " +
                "系统提示词总计: {}, API返回: prompt={}, completion={}, total={}, " +
                "上下文构建: {}ms, API响应: {}ms",
                metrics.getCustomerId(),
                metrics.getConversationId(),
                metrics.getCoreProfileTokens(),
                metrics.getMemoriesTokens(),
                metrics.getConversationTokens(),
                metrics.getExtendedProfileTokens(),
                metrics.getSystemPromptTokens(),
                metrics.getPromptTokens(),
                metrics.getCompletionTokens(),
                metrics.getTotalTokens(),
                metrics.getContextBuildTime(),
                metrics.getApiResponseTime());
    }

    /**
     * 记录告警日志
     */
    private void logAlert(TokenUsageMetrics metrics) {
        int threshold = properties.getAlert().getPromptTokensThreshold();
        log.warn("⚠️ Token 使用超过告警阈值 - customerId: {}, " +
                "prompt_tokens: {} (阈值: {}), " +
                "建议检查上下文配置或优化内容",
                metrics.getCustomerId(),
                metrics.getPromptTokens(),
                threshold);
    }
}
