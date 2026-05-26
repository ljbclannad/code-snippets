package com.lx.governance.appointment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lx.governance.appointment.entity.Department;
import com.lx.governance.appointment.entity.Doctor;
import com.lx.governance.appointment.entity.Schedule;
import com.lx.governance.appointment.dto.ScheduleVO;
import com.lx.governance.appointment.mapper.DepartmentMapper;
import com.lx.governance.appointment.mapper.DoctorMapper;
import com.lx.governance.appointment.mapper.ScheduleMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ScheduleService {

    private final ScheduleMapper scheduleMapper;
    private final DoctorMapper doctorMapper;
    private final DepartmentMapper departmentMapper;
    private final StringRedisTemplate redisTemplate;

    public ScheduleService(
            ScheduleMapper scheduleMapper,
            DoctorMapper doctorMapper,
            DepartmentMapper departmentMapper,
            StringRedisTemplate redisTemplate) {
        this.scheduleMapper = scheduleMapper;
        this.doctorMapper = doctorMapper;
        this.departmentMapper = departmentMapper;
        this.redisTemplate = redisTemplate;
    }

    public List<ScheduleVO> querySchedules(Long deptId, Long doctorId, LocalDate startDate, LocalDate endDate) {
        // 1. 构建查询条件
        LambdaQueryWrapper<Schedule> wrapper = new LambdaQueryWrapper<>();
        wrapper.ge(Schedule::getScheduleDate, startDate)
               .le(Schedule::getScheduleDate, endDate)
               .eq(Schedule::getStatus, "AVAILABLE");

        if (deptId != null) {
            wrapper.eq(Schedule::getDeptId, deptId);
        }

        if (doctorId != null) {
            wrapper.eq(Schedule::getDoctorId, doctorId);
        }

        wrapper.orderByAsc(Schedule::getScheduleDate, Schedule::getTimeSlot);

        // 2. 查询排班
        List<Schedule> schedules = scheduleMapper.selectList(wrapper);

        if (schedules.isEmpty()) {
            return new ArrayList<>();
        }

        // 3. 批量查询医生和科室信息
        List<Long> doctorIds = schedules.stream()
            .map(Schedule::getDoctorId)
            .distinct()
            .collect(Collectors.toList());

        Map<Long, Doctor> doctorMap = doctorMapper.selectBatchIds(doctorIds)
            .stream()
            .collect(Collectors.toMap(Doctor::getId, d -> d));

        List<Long> deptIds = schedules.stream()
            .map(Schedule::getDeptId)
            .distinct()
            .collect(Collectors.toList());

        Map<Long, Department> deptMap = departmentMapper.selectBatchIds(deptIds)
            .stream()
            .collect(Collectors.toMap(Department::getId, d -> d));

        // 4. 组装 VO
        List<ScheduleVO> result = new ArrayList<>();
        for (Schedule schedule : schedules) {
            ScheduleVO vo = new ScheduleVO();
            vo.setScheduleId(schedule.getId());
            vo.setScheduleDate(schedule.getScheduleDate());
            vo.setTimeSlot(schedule.getTimeSlot());
            vo.setFeeCents(schedule.getFeeCents());

            // 从 Redis 获取实时库存
            String stockKey = "appointment:stock:" + schedule.getId();
            String stock = redisTemplate.opsForValue().get(stockKey);

            if (stock != null) {
                try {
                    vo.setAvailableQuota(Integer.parseInt(stock));
                } catch (NumberFormatException e) {
                    vo.setAvailableQuota(schedule.getRemainQuota());
                    log.warn("[APPOINTMENT] [QUERY] Redis 库存格式错误: scheduleId={}, value={}", schedule.getId(), stock);
                }
            } else {
                // Redis 无数据，回退到 DB
                vo.setAvailableQuota(schedule.getRemainQuota());
                log.warn("[APPOINTMENT] [QUERY] Redis 无库存数据，使用 DB 兜底: scheduleId={}", schedule.getId());
            }

            // 设置医生信息
            Doctor doctor = doctorMap.get(schedule.getDoctorId());
            if (doctor != null) {
                vo.setDoctorName(doctor.getDoctorName());
                vo.setTitle(doctor.getTitle());

                // 设置科室信息
                Department dept = deptMap.get(doctor.getDeptId());
                if (dept != null) {
                    vo.setDeptName(dept.getDeptName());
                } else {
                    log.warn("[APPOINTMENT] [QUERY] 科室信息缺失: deptId={}", doctor.getDeptId());
                    vo.setDeptName("未知科室");
                }
            } else {
                log.warn("[APPOINTMENT] [QUERY] 医生信息缺失: doctorId={}", schedule.getDoctorId());
                vo.setDoctorName("未知医生");
                vo.setTitle("未知");
                vo.setDeptName("未知科室");
            }

            result.add(vo);
        }

        log.info("[APPOINTMENT] [QUERY] 查询排班成功: deptId={}, doctorId={}, startDate={}, endDate={}, count={}",
            deptId, doctorId, startDate, endDate, result.size());

        return result;
    }
}
