package com.lx.governance.appointment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lx.governance.appointment.entity.Schedule;
import com.lx.governance.appointment.mapper.ScheduleMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 库存预热服务
 * 负责启动时预热排班库存到 Redis，以及定时刷新
 */
@Slf4j
@Service
public class StockPreloadService {

    private final ScheduleMapper scheduleMapper;
    private final StringRedisTemplate redisTemplate;

    public StockPreloadService(
            ScheduleMapper scheduleMapper,
            StringRedisTemplate redisTemplate) {
        this.scheduleMapper = scheduleMapper;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 启动时预热库存
     * 预热未来 7 天的排班库存到 Redis
     */
    @PostConstruct
    public void preloadStockOnStartup() {
        log.info("[APPOINTMENT] [PRELOAD] 开始预热库存...");

        try {
            // 预热排班库存
            int stockCount = preloadScheduleStock();

            log.info("[APPOINTMENT] [PRELOAD] 库存预热完成，排班: {}", stockCount);
        } catch (Exception e) {
            log.error("[APPOINTMENT] [PRELOAD] 库存预热失败", e);
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

        // 批量写入 Redis
        for (Schedule schedule : schedules) {
            String key = "appointment:stock:" + schedule.getId();
            long ttlDays = ChronoUnit.DAYS.between(startDate, schedule.getScheduleDate()) + 1;

            redisTemplate.opsForValue().set(
                key,
                String.valueOf(schedule.getRemainQuota()),
                Duration.ofDays(ttlDays)
            );
        }

        return schedules.size();
    }

    /**
     * 每小时刷新库存
     * 刷新当天的排班库存
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void refreshStockHourly() {
        log.info("[APPOINTMENT] [REFRESH] 开始刷新库存...");

        try {
            // 刷新当天的排班库存
            LocalDate today = LocalDate.now();

            LambdaQueryWrapper<Schedule> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(Schedule::getScheduleDate, today);

            List<Schedule> schedules = scheduleMapper.selectList(wrapper);

            for (Schedule schedule : schedules) {
                String key = "appointment:stock:" + schedule.getId();
                // 从 DB 重新加载库存
                redisTemplate.opsForValue().set(
                    key,
                    String.valueOf(schedule.getRemainQuota()),
                    Duration.ofHours(24)
                );
            }

            log.info("[APPOINTMENT] [REFRESH] 库存刷新完成，刷新 {} 个排班", schedules.size());
        } catch (Exception e) {
            log.error("[APPOINTMENT] [REFRESH] 库存刷新失败", e);
        }
    }
}
