package com.lx.governance.profile.service;

import com.alibaba.fastjson.JSON;
import com.lx.governance.profile.dto.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@Slf4j
public class RedisProfileService {

    private static final String PROFILE_KEY_PREFIX = "ai:profile:";
    private static final long PROFILE_TTL_HOURS = 24;
    private static final String NULL_MARKER_SUFFIX = ":null";

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private UserProfileService userProfileService;

    /**
     * 获取用户画像（优先Redis，未命中则从MySQL构建）
     */
    public UserProfileDTO getProfile(String customerId) {
        String key = PROFILE_KEY_PREFIX + customerId;

        try {
            // 1. 尝试从Redis获取
            Map<Object, Object> cached = redisTemplate.opsForHash().entries(key);

            if (!cached.isEmpty()) {
                log.debug("从Redis获取用户画像: customerId={}", customerId);
                return deserializeProfile(cached);
            }

            // 检查是否已缓存空结果
            if (Boolean.TRUE.equals(redisTemplate.hasKey(key + NULL_MARKER_SUFFIX))) {
                log.debug("命中空结果缓存: customerId={}", customerId);
                return null;
            }

            // 2. Redis未命中，从MySQL构建
            log.info("Redis未命中，从MySQL构建画像: customerId={}", customerId);
            UserProfileDTO profile = userProfileService.buildUserProfile(customerId);

            if (profile != null) {
                // 3. 回写Redis
                saveToRedis(key, profile);
            } else {
                // 缓存空结果，防止缓存穿透（5分钟TTL）
                redisTemplate.opsForValue().set(key + NULL_MARKER_SUFFIX, "1", 5, TimeUnit.MINUTES);
            }

            return profile;

        } catch (Exception e) {
            log.error("从Redis获取画像失败，降级到MySQL: customerId={}", customerId, e);
            // 降级：直接查询MySQL
            return userProfileService.buildUserProfile(customerId);
        }
    }

    /**
     * 主动刷新画像缓存
     */
    public void refreshProfile(String customerId) {
        String key = PROFILE_KEY_PREFIX + customerId;

        try {
            // 1. 从MySQL重新构建画像
            UserProfileDTO profile = userProfileService.buildUserProfile(customerId);

            if (profile != null) {
                // 2. 删除旧缓存（包括空标记）
                redisTemplate.delete(key);
                redisTemplate.delete(key + NULL_MARKER_SUFFIX);

                // 3. 写入新缓存
                saveToRedis(key, profile);

                log.info("刷新画像缓存成功: customerId={}", customerId);
            }

        } catch (Exception e) {
            log.error("刷新画像缓存失败: customerId={}", customerId, e);
            throw new RuntimeException("刷新画像缓存失败", e);
        }
    }

    // 序列化和反序列化方法将在下一个任务中实现
    private void saveToRedis(String key, UserProfileDTO profile) {
        // TODO: 将在Task 5实现
    }

    private UserProfileDTO deserializeProfile(Map<Object, Object> cached) {
        // TODO: 将在Task 5实现
        throw new UnsupportedOperationException("deserializeProfile will be implemented in Task 5");
    }
}
