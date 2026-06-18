package com.lx.governance.agentplatform.model;

import lombok.Builder;
import lombok.Value;

/**
 * Agent 可见性判断所需的访问上下文。
 */
@Value
@Builder
public class AgentAccessContext {

    String groupId;

    String userId;

    String tenantId;
}
