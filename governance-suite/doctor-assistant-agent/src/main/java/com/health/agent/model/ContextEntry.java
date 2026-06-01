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
public class ContextEntry {
    private String key;
    private Object value;
    private String source;
    private LocalDateTime updatedAt;
}
