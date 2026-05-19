package com.health.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.health.agent.model.AgentSession;
import org.apache.ibatis.annotations.Mapper;

/**
 * Agent 会话 Mapper
 */
@Mapper
public interface AgentSessionMapper extends BaseMapper<AgentSession> {
}
