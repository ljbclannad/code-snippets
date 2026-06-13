package com.lx.governance.appointment.controller;

import com.lx.governance.appointment.dto.ScheduleVO;
import com.lx.governance.appointment.service.ScheduleService;
import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.common.trace.Trace;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/appointment")
public class ScheduleController {

    private final ScheduleService scheduleService;

    public ScheduleController(ScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @GetMapping("/schedules")
    public ApiResponse<List<ScheduleVO>> querySchedules(
            @RequestParam(required = false) Long deptId,
            @RequestParam(required = false) Long doctorId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate startDate,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate endDate) {

        // 参数校验
        if (startDate == null || endDate == null) {
            return ApiResponse.fail("INVALID_PARAM", "开始日期和结束日期不能为空", Trace.getOrCreate());
        }

        if (endDate.isBefore(startDate)) {
            return ApiResponse.fail("INVALID_DATE_RANGE", "结束日期不能早于开始日期", Trace.getOrCreate());
        }

        long days = ChronoUnit.DAYS.between(startDate, endDate);
        if (days > 30) {
            return ApiResponse.fail("DATE_RANGE_TOO_LARGE", "日期范围不能超过30天", Trace.getOrCreate());
        }

        // 查询排班
        List<ScheduleVO> schedules = scheduleService.querySchedules(deptId, doctorId, startDate, endDate);

        return ApiResponse.ok(schedules, Trace.getOrCreate());
    }
}
