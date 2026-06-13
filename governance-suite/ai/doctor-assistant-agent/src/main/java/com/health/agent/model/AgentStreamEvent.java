package com.health.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentStreamEvent {
    private String sessionId;
    private Integer step;
    private AgentStreamEventType type;
    private Object data;
    private LocalDateTime timestamp;
}
