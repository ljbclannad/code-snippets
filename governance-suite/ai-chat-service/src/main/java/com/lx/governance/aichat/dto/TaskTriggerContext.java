package com.lx.governance.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * TaskTriggerRouter 的输入上下文。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskTriggerContext {

    private String customerId;
    private String groupId;
    private String conversationId;
    private String message;
    private SessionContext sessionContext;
}
