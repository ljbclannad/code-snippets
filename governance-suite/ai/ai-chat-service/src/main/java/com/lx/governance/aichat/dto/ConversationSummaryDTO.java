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
public class ConversationSummaryDTO {

    private String conversationId;
    private String customerId;
    private String latestUserMessage;
    private String latestAiResponse;
    private LocalDateTime latestMessageTime;
    private int rounds;
}
