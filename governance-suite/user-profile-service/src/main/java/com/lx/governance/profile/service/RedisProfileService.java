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

    /**
     * 将UserProfileDTO序列化到Redis Hash
     */
    private void saveToRedis(String key, UserProfileDTO profile) {
        Map<String, Object> hash = new HashMap<>();

        // 基本信息
        if (profile.getBasicInfo() != null) {
            BasicInfoDTO basic = profile.getBasicInfo();
            if (basic.getCustomerId() != null) hash.put("basic:customerId", basic.getCustomerId());
            if (basic.getName() != null) hash.put("basic:name", basic.getName());
            if (basic.getAge() != null) hash.put("basic:age", basic.getAge());
            if (basic.getGender() != null) hash.put("basic:gender", basic.getGender());
            if (basic.getPhone() != null) hash.put("basic:phone", basic.getPhone());
            if (basic.getEmail() != null) hash.put("basic:email", basic.getEmail());
        }

        // 健康档案
        if (profile.getHealthProfile() != null) {
            HealthProfileDTO health = profile.getHealthProfile();
            if (health.getBmi() != null) hash.put("health:bmi", health.getBmi());
            if (health.getBloodPressure() != null) hash.put("health:bloodPressure", health.getBloodPressure());
            if (health.getBloodSugar() != null) hash.put("health:bloodSugar", health.getBloodSugar());
            if (health.getLatestAssessmentDate() != null) {
                hash.put("health:latestAssessmentDate", health.getLatestAssessmentDate().toString());
            }
            if (health.getHealthGoals() != null) {
                hash.put("health:healthGoals", JSON.toJSONString(health.getHealthGoals()));
            }
        }

        // 行为偏好
        if (profile.getBehaviorPreferences() != null) {
            BehaviorPreferencesDTO behavior = profile.getBehaviorPreferences();
            if (behavior.getDietPreference() != null) {
                hash.put("behavior:dietPreference", behavior.getDietPreference());
            }
            if (behavior.getExerciseFrequency() != null) {
                hash.put("behavior:exerciseFrequency", behavior.getExerciseFrequency());
            }
            if (behavior.getCheckInStreak() != null) {
                hash.put("behavior:checkInStreak", behavior.getCheckInStreak());
            }
        }

        // 全局记忆
        if (profile.getGlobalMemories() != null && !profile.getGlobalMemories().isEmpty()) {
            hash.put("memories", JSON.toJSONString(profile.getGlobalMemories()));
        }

        // 元数据
        hash.put("meta:cachedAt", System.currentTimeMillis());
        hash.put("meta:version", "v1");

        // 批量写入Redis
        redisTemplate.opsForHash().putAll(key, hash);

        // 设置TTL
        redisTemplate.expire(key, PROFILE_TTL_HOURS, TimeUnit.HOURS);

        log.debug("保存画像到Redis: key={}, fields={}", key, hash.size());
    }

    /**
     * 从Redis Hash反序列化为UserProfileDTO
     */
    private UserProfileDTO deserializeProfile(Map<Object, Object> cached) {
        if (cached == null || cached.isEmpty()) {
            return null;
        }

        UserProfileDTO profile = new UserProfileDTO();

        // 基本信息
        BasicInfoDTO basic = new BasicInfoDTO();
        basic.setCustomerId((String) cached.get("basic:customerId"));
        basic.setName((String) cached.get("basic:name"));
        basic.setAge((Integer) cached.get("basic:age"));
        basic.setGender((String) cached.get("basic:gender"));
        basic.setPhone((String) cached.get("basic:phone"));
        basic.setEmail((String) cached.get("basic:email"));
        profile.setBasicInfo(basic);

        // 健康档案
        HealthProfileDTO health = new HealthProfileDTO();
        health.setBmi((Double) cached.get("health:bmi"));
        health.setBloodPressure((String) cached.get("health:bloodPressure"));
        health.setBloodSugar((Double) cached.get("health:bloodSugar"));

        String assessmentDateStr = (String) cached.get("health:latestAssessmentDate");
        if (assessmentDateStr != null) {
            health.setLatestAssessmentDate(java.time.LocalDate.parse(assessmentDateStr));
        }

        String healthGoalsJson = (String) cached.get("health:healthGoals");
        if (healthGoalsJson != null) {
            health.setHealthGoals(JSON.parseArray(healthGoalsJson, String.class));
        }
        profile.setHealthProfile(health);

        // 行为偏好
        BehaviorPreferencesDTO behavior = new BehaviorPreferencesDTO();
        behavior.setDietPreference((String) cached.get("behavior:dietPreference"));
        behavior.setExerciseFrequency((String) cached.get("behavior:exerciseFrequency"));
        behavior.setCheckInStreak((Integer) cached.get("behavior:checkInStreak"));
        profile.setBehaviorPreferences(behavior);

        // 全局记忆
        String memoriesJson = (String) cached.get("memories");
        if (memoriesJson != null) {
            profile.setGlobalMemories(JSON.parseArray(memoriesJson, GlobalMemoryDTO.class));
        }

        return profile;
    }
}
