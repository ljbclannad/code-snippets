package com.health.agent.dto.patient;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 面向患者展示的轻量证据卡片。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PatientVisibleEvidence {
    private String title;
    private String summary;
    private String source;
    private String trustLevel;
}
