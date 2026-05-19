package com.health.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.health.agent.model.WeightRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 体重记录 Mapper
 */
@Mapper
public interface WeightRecordMapper extends BaseMapper<WeightRecord> {
}
