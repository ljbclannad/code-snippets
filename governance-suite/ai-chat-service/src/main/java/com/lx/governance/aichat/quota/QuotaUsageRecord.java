package com.lx.governance.aichat.quota;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 配额使用记录
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuotaUsageRecord {

    /**
     * 记录ID
     */
    private Long id;

    /**
     * 用户ID
     */
    private String customerId;

    /**
     * 会话ID
     */
    private String conversationId;

    /**
     * 消息ID
     */
    private String messageId;

    /**
     * Prompt tokens
     */
    private Integer promptTokens;

    /**
     * Completion tokens
     */
    private Integer completionTokens;

    /**
     * 总 tokens
     */
    private Integer totalTokens;

    /**
     * 使用时间
     */
    private LocalDateTime usageTime;

    /**
     * 配额类型（记录时的类型）
     */
    private String quotaType;

    /**
     * 备注
     */
    private String remark;
}
