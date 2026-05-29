package com.lx.governance.gateway.controller;

import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.gateway.service.HotDeptCacheService;
import com.lx.governance.common.trace.Trace;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 限流管理接口
 * 用于查看缓存统计和手动刷新缓存
 */
@Slf4j
@RestController
@RequestMapping("/admin/rate-limit")
public class RateLimitAdminController {

    private final HotDeptCacheService hotDeptCacheService;

    public RateLimitAdminController(HotDeptCacheService hotDeptCacheService) {
        this.hotDeptCacheService = hotDeptCacheService;
    }

    /**
     * 获取热门科室缓存统计信息
     */
    @GetMapping("/cache/stats")
    public ApiResponse<Map<String, Object>> getCacheStats() {
        String stats = hotDeptCacheService.getCacheStats();

        Map<String, Object> result = new HashMap<>();
        result.put("cacheStats", stats);
        result.put("description", "Caffeine cache statistics for hot department");

        return ApiResponse.ok(result, Trace.getTraceId());
    }

    /**
     * 刷新指定排班的热门科室缓存
     */
    @DeleteMapping("/cache/schedule/{scheduleId}")
    public ApiResponse<Void> invalidateScheduleCache(@PathVariable Long scheduleId) {
        hotDeptCacheService.invalidate(scheduleId);
        log.info("Schedule cache invalidated via admin API, scheduleId={}", scheduleId);
        return ApiResponse.ok(null, Trace.getTraceId());
    }

    /**
     * 清空所有热门科室缓存
     */
    @DeleteMapping("/cache/all")
    public ApiResponse<Void> invalidateAllCache() {
        hotDeptCacheService.invalidateAll();
        log.warn("All hot dept cache invalidated via admin API");
        return ApiResponse.ok(null, Trace.getTraceId());
    }
}
