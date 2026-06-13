package com.lx.governance.appointment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lx.governance.appointment.entity.AppointmentIdempotency;
import org.apache.ibatis.annotations.Mapper;

/**
 * 预约幂等性 Mapper 接口
 */
@Mapper
public interface AppointmentIdempotencyMapper extends BaseMapper<AppointmentIdempotency> {
}
