package com.lx.governance.appointment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lx.governance.appointment.entity.Doctor;
import org.apache.ibatis.annotations.Mapper;

/**
 * 医生 Mapper 接口
 */
@Mapper
public interface DoctorMapper extends BaseMapper<Doctor> {
}
