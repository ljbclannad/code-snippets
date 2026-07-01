package com.health.agent.engine.subagent;

import java.util.List;
import java.util.Set;

import com.lx.governance.agentplatform.model.PlatformContextVisibility;

public interface AgentSubAgent {
    default String agentId() {
        return "doctor." + name();
    }

    String name();

    String responsibility();

    default List<String> preferredToolNames() {
        return List.of();
    }

    default Set<String> visibleSlotKeys() {
        return Set.of();
    }

    default Set<PlatformContextVisibility> visibleVisibilities() {
        return Set.of(
                PlatformContextVisibility.AGENT_INTERNAL,
                PlatformContextVisibility.DOCTOR_VISIBLE);
    }
}
