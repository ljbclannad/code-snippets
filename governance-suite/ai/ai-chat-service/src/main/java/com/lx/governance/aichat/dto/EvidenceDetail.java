package com.lx.governance.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工作台展示的证据明细。
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
