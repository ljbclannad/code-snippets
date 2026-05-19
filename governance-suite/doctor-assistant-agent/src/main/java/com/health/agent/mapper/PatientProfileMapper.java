package com.health.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.health.agent.model.PatientProfile;
import org.apache.ibatis.annotations.Mapper;

/**
 * 患者档案 Mapper
 */
@Mapper
public interface PatientProfileMapper extends BaseMapper<PatientProfile> {
}
