package com.lx.governance.profile.mapper;

import com.lx.governance.profile.entity.HealthAssessmentEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface HealthAssessmentMapper {
    HealthAssessmentEntity selectLatestByCustomerId(@Param("customerId") String customerId);
}
