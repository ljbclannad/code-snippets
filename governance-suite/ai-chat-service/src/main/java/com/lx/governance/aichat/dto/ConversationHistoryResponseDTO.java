package com.lx.governance.aichat.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationHistoryResponseDTO {

    private String customerId;
    private String conversationId;
    private int rounds;
    private List<ConversationHistoryMessageDTO> messages;
}
