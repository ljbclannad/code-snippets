package com.health.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.health.agent.model.PrescriptionRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 处方记录 Mapper
 */
@Mapper
public interface PrescriptionRecordMapper extends BaseMapper<PrescriptionRecord> {
}
