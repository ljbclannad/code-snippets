package com.lx.governance.aichat.monitor;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Token 使用指标
 */
@Data
@Builder
public class TokenUsageMetrics {

    // 基本信息
    private String customerId;
    private String conversationId;
    private LocalDateTime timestamp;

    // 各部分 token 使用
    private int coreProfileTokens;      // 核心画像
    private int memoriesTokens;         // 记忆
    private int conversationTokens;     // 对话历史
    private int extendedProfileTokens;  // 扩展画像
    private int systemPromptTokens;     // 系统提示词总计

    // API 返回的 token 使用
    private int promptTokens;           // Prompt tokens（API 返回）
    private int completionTokens;       // Completion tokens
    private int totalTokens;            // Total tokens

    // 性能指标
    private long contextBuildTime;      // 上下文构建耗时（毫秒）
    private long apiResponseTime;       // API 响应时间（毫秒）

    // 告警标记
    private boolean overBudget;         // 是否超预算
    private boolean shouldAlert;        // 是否需要告警
}
