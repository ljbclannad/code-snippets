package com.health.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.health.agent.model.AgentExecutionLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * Agent 执行日志 Mapper
 */
@Mapper
public interface AgentExecutionLogMapper extends BaseMapper<AgentExecutionLog> {
}
