package com.lx.governance.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.lx.governance.gateway.config.AppointmentRateLimitProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * 热门科室缓存服务
 * 使用 Caffeine 本地缓存减少 Redis 查询
 */
@Slf4j
@Service
public class HotDeptCacheService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Cache<Long, Boolean> hotDeptCache;

    public HotDeptCacheService(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            AppointmentRateLimitProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;

        // 初始化本地缓存
        this.hotDeptCache = Caffeine.newBuilder()
                .maximumSize(properties.getHotDeptCache().getMaxSize())
                .expireAfterWrite(
                        properties.getHotDeptCache().getExpireAfterWriteSeconds(),
                        TimeUnit.SECONDS
                )
                .recordStats() // 启用统计
                .build();

        log.info("HotDeptCacheService initialized",
                kv("maxSize", properties.getHotDeptCache().getMaxSize()),
                kv("expireSeconds", properties.getHotDeptCache().getExpireAfterWriteSeconds())
        );
    }

    /**
     * 检查科室是否为热门科室（带本地缓存）
     *
     * @param scheduleId 排班ID
     * @return true 热门科室，false 普通科室
     */
    public boolean isHotDept(Long scheduleId) {
        try {
            // 先查本地缓存
            Boolean cached = hotDeptCache.getIfPresent(scheduleId);
            if (cached != null) {
                log.debug("Hot dept cache hit",
                        kv("scheduleId", scheduleId),
                        kv("isHot", cached)
                );
                return cached;
            }

            // 缓存未命中，查询 Redis
            boolean isHot = queryFromRedis(scheduleId);

            // 写入本地缓存
            hotDeptCache.put(scheduleId, isHot);

            log.debug("Hot dept cache miss, loaded from Redis",
                    kv("scheduleId", scheduleId),
                    kv("isHot", isHot)
            );

            return isHot;

        } catch (Exception e) {
            log.error("Failed to check hot dept, treating as normal",
                    kv("event", "hot_dept_check_error"),
                    kv("scheduleId", scheduleId),
                    e
            );
            return false; // 降级：异常时默认为普通科室
        }
    }

    /**
     * 从 Redis 查询科室热门标识
     */
    private boolean queryFromRedis(Long scheduleId) {
        try {
            // 1. 从 Redis 查询排班信息获取 deptId
            String scheduleKey = "appointment:schedule:" + scheduleId;
            String scheduleJson = redisTemplate.opsForValue().get(scheduleKey);

            if (scheduleJson == null) {
                log.warn("Schedule not found in cache, treating as normal",
                        kv("event", "schedule_cache_miss"),
                        kv("scheduleId", scheduleId)
                );
                return false; // 降级：查询失败默认为普通科室
            }

            JsonNode scheduleNode = objectMapper.readTree(scheduleJson);
            if (!scheduleNode.has("deptId")) {
                log.warn("Schedule JSON missing deptId field",
                        kv("event", "schedule_invalid_format"),
                        kv("scheduleId", scheduleId)
                );
                return false;
            }

            Long deptId = scheduleNode.get("deptId").asLong();

            // 2. 查询科室热门标识
            String hotKey = "appointment:dept:hot:" + deptId;
            Boolean isHot = redisTemplate.hasKey(hotKey);

            log.debug("Hot dept check completed",
                    kv("scheduleId", scheduleId),
                    kv("deptId", deptId),
                    kv("isHot", isHot)
            );

            return Boolean.TRUE.equals(isHot);

        } catch (Exception e) {
            log.error("Failed to query hot dept from Redis",
                    kv("scheduleId", scheduleId),
                    e
            );
            throw new RuntimeException("Failed to query hot dept", e);
        }
    }

    /**
     * 手动刷新缓存（用于管理接口）
     */
    public void invalidate(Long scheduleId) {
        hotDeptCache.invalidate(scheduleId);
        log.info("Hot dept cache invalidated",
                kv("scheduleId", scheduleId)
        );
    }

    /**
     * 清空所有缓存
     */
    public void invalidateAll() {
        hotDeptCache.invalidateAll();
        log.info("All hot dept cache invalidated");
    }

    /**
     * 获取缓存统计信息
     */
    public String getCacheStats() {
        return hotDeptCache.stats().toString();
    }
}
