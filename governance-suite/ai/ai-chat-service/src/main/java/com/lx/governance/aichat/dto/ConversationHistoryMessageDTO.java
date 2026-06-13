package com.lx.governance.aichat.dto;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationHistoryMessageDTO {

    private ConversationMessage.Role role;
    private String content;
    private LocalDateTime timestamp;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
}
