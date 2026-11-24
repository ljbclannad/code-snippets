package com.health.agent.engine;

import java.util.List;

import com.lx.governance.agentplatform.model.AgentAccessContext;
import com.lx.governance.agentplatform.model.ToolDescriptor;

/**
 * 场景级工具目录抽象，供运行时只读取描述信息。
 */
public interface AgentToolCatalog {

    List<ToolDescriptor> listToolDescriptors(AgentAccessContext accessContext);
}
