package com.lx.governance.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageSummary {

    /**
     * 用户消息摘要（最多50字）
     */
    private String userMessage;

    /**
     * 消息时间戳
     */
    private Long timestamp;
}
