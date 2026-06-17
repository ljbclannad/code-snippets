package com.health.agent.engine.subagent;

import java.util.List;

public interface AgentSubAgent {
    String name();

    String responsibility();

    default List<String> preferredToolNames() {
        return List.of();
    }
}
