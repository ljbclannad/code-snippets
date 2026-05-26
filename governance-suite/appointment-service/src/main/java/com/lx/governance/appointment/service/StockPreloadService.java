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
            log.info("[APPOINTMENT] [PRELOAD] 开始预热库存...");

            // 预热排班库存
            int stockCount = preloadScheduleStock();

            log.info("[APPOINTMENT] [PRELOAD] 库存预热完成，排班: {}", stockCount);
        } catch (Exception e) {
            log.error("[APPOINTMENT] [PRELOAD] 库存预热失败", e);
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
     * 每小时刷新库存
     * 只刷新不存在的 key（新增的排班），避免覆盖实时扣减值
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
            log.info("[APPOINTMENT] [REFRESH] 开始刷新库存...");

            // 刷新未来 7 天的排班（预热新增的排班）
            LocalDate startDate = LocalDate.now();
            LocalDate endDate = startDate.plusDays(7);

            LambdaQueryWrapper<Schedule> wrapper = new LambdaQueryWrapper<>();
            wrapper.ge(Schedule::getScheduleDate, startDate)
                   .le(Schedule::getScheduleDate, endDate);

            List<Schedule> schedules = scheduleMapper.selectList(wrapper);

            if (schedules.isEmpty()) {
                log.warn("[APPOINTMENT] [REFRESH] 未找到未来 7 天的排班数据");
                return;
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

            log.info("[APPOINTMENT] [REFRESH] 库存刷新完成，新增 {} 个排班", refreshed);
        } catch (Exception e) {
            log.error("[APPOINTMENT] [REFRESH] 库存刷新失败", e);
        } finally {
            redisTemplate.delete(lockKey);
        }
    }
}
