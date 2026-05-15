package com.lx.governance.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 对话历史同步消息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatHistorySyncMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String customerId;
    private String conversationId;
    private String userMessage;
    private String aiResponse;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private LocalDateTime createTime;
}
