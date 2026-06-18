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
public class ContextEntry {
    private String key;
    private Object value;
    private String source;
    @Builder.Default
    private PlatformContextVisibility visibility = PlatformContextVisibility.AGENT_INTERNAL;
    private LocalDateTime updatedAt;
}
