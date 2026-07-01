package com.health.agent.model;

import com.lx.governance.agentplatform.model.PlatformContextVisibility;

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
    @Builder.Default
    private PlatformContextVisibility visibility = PlatformContextVisibility.AGENT_INTERNAL;
    private LocalDateTime recordedAt;
}
