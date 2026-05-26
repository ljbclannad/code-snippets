package com.lx.governance.appointment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lx.governance.appointment.entity.AppointmentOrder;
import org.apache.ibatis.annotations.Mapper;

/**
 * 预约订单 Mapper 接口
 */
@Mapper
public interface AppointmentOrderMapper extends BaseMapper<AppointmentOrder> {
}
