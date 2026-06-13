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
public class EvidenceRecord {
    private String source;
    private String toolName;
    private String summary;
    private Object payload;
    private LocalDateTime recordedAt;
}
