package com.health.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 面向工作台和审计链路的结构化证据项。
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
