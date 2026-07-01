package com.health.agent.dto.patient;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工作台对外暴露的证据明细对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvidenceDetail {
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
