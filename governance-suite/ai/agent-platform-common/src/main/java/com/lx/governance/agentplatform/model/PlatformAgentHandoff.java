package com.lx.governance.agentplatform.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent 切换时的统一交接包。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformAgentHandoff {
    private String schemaVersion;
    private String handoffId;
    private String runId;
    private String fromAgentId;
    private String fromAgentName;
    private String toAgentId;
    private String toAgentName;
    private String goal;
    private String handoffSummary;
    private LocalDateTime createdAt;

    @Builder.Default
    private List<PlatformAgentHandoffItem> items = new ArrayList<>();
}
