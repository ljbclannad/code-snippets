package com.lx.governance.appointment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lx.governance.appointment.entity.Schedule;
import org.apache.ibatis.annotations.Mapper;

/**
 * 排班 Mapper 接口
 */
@Mapper
public interface ScheduleMapper extends BaseMapper<Schedule> {
}
