package com.health.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.health.agent.model.PendingTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * 待确认任务 Mapper
 */
@Mapper
public interface PendingTaskMapper extends BaseMapper<PendingTask> {
}
