package com.lx.governance.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ai-chat-service 侧消费的结构化证据项。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StructuredEvidenceItem {
    private String summary;
    private String source;
    private String evidenceType;
    private Boolean upstream;
    private String title;
    private String snippet;
    private String trustLevel;
    private Double score;
    private String hitReason;
}
