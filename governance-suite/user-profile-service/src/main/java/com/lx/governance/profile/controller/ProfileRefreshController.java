package com.lx.governance.profile.controller;

import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.common.trace.Trace;
import com.lx.governance.profile.service.RedisProfileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/profile")
@Slf4j
public class ProfileRefreshController {

    private final RedisProfileService redisProfileService;

    public ProfileRefreshController(RedisProfileService redisProfileService) {
        this.redisProfileService = redisProfileService;
    }

    @PostMapping("/refresh/{customerId}")
    public ApiResponse<Void> refreshProfile(@PathVariable String customerId) {
        log.info("刷新用户画像缓存: customerId={}", customerId);
        redisProfileService.refreshProfile(customerId);
        return ApiResponse.ok(null, Trace.getOrCreate());
    }
}
