package com.lx.governance.aichat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
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
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String followUpTurnKey;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean followUpConfirmed;
}
