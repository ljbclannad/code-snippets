package com.lx.governance.aichat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 聊天历史记录实体
 */
@Data
@TableName("t_ai_chat_history")
public class AiChatHistory {
    private String followUpKey;
    private Integer followUpConfirmed;

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 客户ID
     */
    private String customerId;

    /**
     * 对话ID
     */
    private String conversationId;

    /**
     * 用户消息
     */
    private String userMessage;

    /**
     * AI 回复
     */
    private String aiResponse;

    /**
     * Prompt tokens
     */
    private Integer promptTokens;

    /**
     * Completion tokens
     */
    private Integer completionTokens;

    /**
     * Total tokens
     */
    private Integer totalTokens;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 是否删除（0-未删除，1-已删除）
     */
    private Integer isDeleted;
}
