package com.lx.governance.appointment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.appointment.entity.Department;
import com.lx.governance.appointment.entity.Schedule;
import com.lx.governance.appointment.mapper.DepartmentMapper;
import com.lx.governance.appointment.mapper.ScheduleMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 库存预热服务
 * 负责启动时预热排班库存、排班信息、热门科室标识到 Redis，以及定时刷新
 */
@Slf4j
@Service
public class StockPreloadService {

    private final ScheduleMapper scheduleMapper;
    private final DepartmentMapper departmentMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public StockPreloadService(
            ScheduleMapper scheduleMapper,
            DepartmentMapper departmentMapper,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper) {
        this.scheduleMapper = scheduleMapper;
        this.departmentMapper = departmentMapper;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 启动时预热数据
     * 预热未来 7 天的排班库存、排班信息、热门科室标识到 Redis
     * 使用分布式锁避免多实例重复预热
     */
    @PostConstruct
    public void preloadStockOnStartup() {
        String lockKey = "appointment:preload:lock";
        Boolean acquired = redisTemplate.opsForValue()
            .setIfAbsent(lockKey, "1", Duration.ofMinutes(5));

        if (Boolean.FALSE.equals(acquired)) {
            log.info("[APPOINTMENT] [PRELOAD] 其他实例正在预热，跳过");
            return;
        }

        try {
            log.info("[APPOINTMENT] [PRELOAD] 开始预热数据...");

            // 1. 预热排班库存
            int stockCount = preloadScheduleStock();

            // 2. 预热排班信息（新增）
            int scheduleCount = preloadScheduleInfo();

            // 3. 预热热门科室标识（新增）
            int hotDeptCount = preloadHotDeptFlags();

            log.info("[APPOINTMENT] [PRELOAD] 数据预热完成 - 库存: {}, 排班信息: {}, 热门科室: {}",
                stockCount, scheduleCount, hotDeptCount);
        } catch (Exception e) {
            log.error("[APPOINTMENT] [PRELOAD] 数据预热失败", e);
        } finally {
            redisTemplate.delete(lockKey);
        }
    }

    /**
     * 预热排班库存到 Redis
     * @return 预热的排班数量
     */
    private int preloadScheduleStock() {
        // 查询未来 7 天的所有排班
        LocalDate startDate = LocalDate.now();
        LocalDate endDate = startDate.plusDays(7);

        LambdaQueryWrapper<Schedule> wrapper = new LambdaQueryWrapper<>();
        wrapper.ge(Schedule::getScheduleDate, startDate)
               .le(Schedule::getScheduleDate, endDate);

        List<Schedule> schedules = scheduleMapper.selectList(wrapper);

        if (schedules.isEmpty()) {
            log.warn("[APPOINTMENT] [PRELOAD] 未找到未来 7 天的排班数据");
            return 0;
        }

        // 批量写入 Redis
        for (Schedule schedule : schedules) {
            String key = "appointment:stock:" + schedule.getId();
            long ttlDays = Math.max(1, ChronoUnit.DAYS.between(startDate, schedule.getScheduleDate()) + 1);

            redisTemplate.opsForValue().set(
                key,
                String.valueOf(schedule.getRemainQuota()),
                Duration.ofDays(ttlDays)
            );
        }

        return schedules.size();
    }

    /**
     * 预热排班信息到 Redis
     * Key: appointment:schedule:{scheduleId}
     * Value: {"id": 123, "deptId": 10, "doctorId": 5, "scheduleDate": "2026-05-29", ...}
     *
     * @return 预热的排班数量
     */
    private int preloadScheduleInfo() {
        // 查询未来 7 天的所有排班
        LocalDate startDate = LocalDate.now();
        LocalDate endDate = startDate.plusDays(7);

        LambdaQueryWrapper<Schedule> wrapper = new LambdaQueryWrapper<>();
        wrapper.ge(Schedule::getScheduleDate, startDate)
               .le(Schedule::getScheduleDate, endDate);

        List<Schedule> schedules = scheduleMapper.selectList(wrapper);

        if (schedules.isEmpty()) {
            log.warn("[APPOINTMENT] [PRELOAD] 未找到未来 7 天的排班数据");
            return 0;
        }

        // 批量写入 Redis
        for (Schedule schedule : schedules) {
            String key = "appointment:schedule:" + schedule.getId();
            long ttlDays = Math.max(1, ChronoUnit.DAYS.between(startDate, schedule.getScheduleDate()) + 1);

            try {
                // 构造排班信息 JSON
                Map<String, Object> scheduleInfo = new HashMap<>();
                scheduleInfo.put("id", schedule.getId());
                scheduleInfo.put("deptId", schedule.getDeptId());
                scheduleInfo.put("doctorId", schedule.getDoctorId());
                scheduleInfo.put("scheduleDate", schedule.getScheduleDate().toString());
                scheduleInfo.put("timeSlot", schedule.getTimeSlot());
                scheduleInfo.put("totalQuota", schedule.getTotalQuota());
                scheduleInfo.put("availableQuota", schedule.getRemainQuota());

                String json = objectMapper.writeValueAsString(scheduleInfo);

                redisTemplate.opsForValue().set(
                    key,
                    json,
                    Duration.ofDays(ttlDays)
                );
            } catch (Exception e) {
                log.error("[APPOINTMENT] [PRELOAD] 预热排班信息失败, scheduleId={}", schedule.getId(), e);
            }
        }

        log.info("[APPOINTMENT] [PRELOAD] 排班信息预热完成，数量: {}", schedules.size());
        return schedules.size();
    }

    /**
     * 预热热门科室标识到 Redis
     * Key: appointment:dept:hot:{deptId}
     * Value: "1" (存在即为热门)
     *
     * @return 预热的热门科室数量
     */
    private int preloadHotDeptFlags() {
        // 查询所有热门科室
        LambdaQueryWrapper<Department> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Department::getIsHot, 1);

        List<Department> hotDepts = departmentMapper.selectList(wrapper);

        if (hotDepts.isEmpty()) {
            log.warn("[APPOINTMENT] [PRELOAD] 未找到热门科室");
            return 0;
        }

        // 批量写入 Redis
        for (Department dept : hotDepts) {
            String key = "appointment:dept:hot:" + dept.getId();

            // 热门标识长期有效（30 天）
            redisTemplate.opsForValue().set(
                key,
                "1",
                Duration.ofDays(30)
            );
        }

        String hotDeptNames = hotDepts.stream()
            .map(Department::getDeptName)
            .collect(Collectors.joining(", "));

        log.info("[APPOINTMENT] [PRELOAD] 热门科室标识预热完成，数量: {}, 科室: {}",
            hotDepts.size(), hotDeptNames);

        return hotDepts.size();
    }

    /**
     * 每小时刷新数据
     * 刷新库存、排班信息、热门科室标识
     * 使用分布式锁避免多实例重复刷新
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void refreshStockHourly() {
        String lockKey = "appointment:refresh:lock";
        Boolean acquired = redisTemplate.opsForValue()
            .setIfAbsent(lockKey, "1", Duration.ofMinutes(5));

        if (Boolean.FALSE.equals(acquired)) {
            log.info("[APPOINTMENT] [REFRESH] 其他实例正在刷新，跳过");
            return;
        }

        try {
            log.info("[APPOINTMENT] [REFRESH] 开始刷新数据...");

            // 1. 刷新库存（只刷新新增的）
            int stockRefreshed = refreshStockIfAbsent();

            // 2. 刷新排班信息（只刷新新增的）
            int scheduleRefreshed = refreshScheduleInfoIfAbsent();

            // 3. 刷新热门科室标识（全量刷新，因为可能有变化）
            int hotDeptRefreshed = refreshHotDeptFlags();

            log.info("[APPOINTMENT] [REFRESH] 数据刷新完成 - 库存: {}, 排班信息: {}, 热门科室: {}",
                stockRefreshed, scheduleRefreshed, hotDeptRefreshed);
        } catch (Exception e) {
            log.error("[APPOINTMENT] [REFRESH] 数据刷新失败", e);
        } finally {
            redisTemplate.delete(lockKey);
        }
    }

    /**
     * 刷新库存（只刷新不存在的 key）
     */
    private int refreshStockIfAbsent() {
        LocalDate startDate = LocalDate.now();
        LocalDate endDate = startDate.plusDays(7);

        LambdaQueryWrapper<Schedule> wrapper = new LambdaQueryWrapper<>();
        wrapper.ge(Schedule::getScheduleDate, startDate)
               .le(Schedule::getScheduleDate, endDate);

        List<Schedule> schedules = scheduleMapper.selectList(wrapper);

        if (schedules.isEmpty()) {
            log.warn("[APPOINTMENT] [REFRESH] 未找到未来 7 天的排班数据");
            return 0;
        }

        int refreshed = 0;
        for (Schedule schedule : schedules) {
            String key = "appointment:stock:" + schedule.getId();

            // 只刷新不存在的 key（新增的排班）
            Boolean exists = redisTemplate.hasKey(key);
            if (Boolean.FALSE.equals(exists)) {
                long ttlDays = Math.max(1, ChronoUnit.DAYS.between(startDate, schedule.getScheduleDate()) + 1);
                redisTemplate.opsForValue().set(
                    key,
                    String.valueOf(schedule.getRemainQuota()),
                    Duration.ofDays(ttlDays)
                );
                refreshed++;
            }
        }

        return refreshed;
    }

    /**
     * 刷新排班信息（只刷新不存在的 key）
     */
    private int refreshScheduleInfoIfAbsent() {
        LocalDate startDate = LocalDate.now();
        LocalDate endDate = startDate.plusDays(7);

        LambdaQueryWrapper<Schedule> wrapper = new LambdaQueryWrapper<>();
        wrapper.ge(Schedule::getScheduleDate, startDate)
               .le(Schedule::getScheduleDate, endDate);

        List<Schedule> schedules = scheduleMapper.selectList(wrapper);

        if (schedules.isEmpty()) {
            return 0;
        }

        int refreshed = 0;
        for (Schedule schedule : schedules) {
            String key = "appointment:schedule:" + schedule.getId();

            // 只刷新不存在的 key（新增的排班）
            Boolean exists = redisTemplate.hasKey(key);
            if (Boolean.FALSE.equals(exists)) {
                long ttlDays = Math.max(1, ChronoUnit.DAYS.between(startDate, schedule.getScheduleDate()) + 1);

                try {
                    Map<String, Object> scheduleInfo = new HashMap<>();
                    scheduleInfo.put("id", schedule.getId());
                    scheduleInfo.put("deptId", schedule.getDeptId());
                    scheduleInfo.put("doctorId", schedule.getDoctorId());
                    scheduleInfo.put("scheduleDate", schedule.getScheduleDate().toString());
                    scheduleInfo.put("timeSlot", schedule.getTimeSlot());
                    scheduleInfo.put("totalQuota", schedule.getTotalQuota());
                    scheduleInfo.put("availableQuota", schedule.getRemainQuota());

                    String json = objectMapper.writeValueAsString(scheduleInfo);

                    redisTemplate.opsForValue().set(
                        key,
                        json,
                        Duration.ofDays(ttlDays)
                    );
                    refreshed++;
                } catch (Exception e) {
                    log.error("[APPOINTMENT] [REFRESH] 刷新排班信息失败, scheduleId={}", schedule.getId(), e);
                }
            }
        }

        return refreshed;
    }

    /**
     * 刷新热门科室标识（全量刷新）
     */
    private int refreshHotDeptFlags() {
        // 先删除所有旧的热门标识
        Set<String> oldKeys = redisTemplate.keys("appointment:dept:hot:*");
        if (oldKeys != null && !oldKeys.isEmpty()) {
            redisTemplate.delete(oldKeys);
            log.info("[APPOINTMENT] [REFRESH] 清除旧的热门科室标识，数量: {}", oldKeys.size());
        }

        // 重新预热
        return preloadHotDeptFlags();
    }
}
