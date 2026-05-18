# 用户画像缓存详细设计

**父文档**: [AI对话流程重构设计方案](./2026-05-18-ai-chat-flow-refactor-design.md)

---

## 1. 概述

用户画像缓存是性能优化的关键组件，通过Redis Hash结构缓存用户画像，避免每次对话都查询MySQL。

### 1.1 设计目标

- **性能提升**：画像获取时间从200ms降到50ms以内
- **缓存命中率**：目标 > 80%
- **数据一致性**：支持主动刷新机制
- **混合模式**：优先读Redis，不存在则从MySQL构建并回写

### 1.2 核心特性

- **Redis Hash存储**：扁平化存储，支持部分字段更新
- **24小时TTL**：自动过期，避免脏数据
- **主动刷新**：问卷/报告解析后主动刷新缓存
- **降级策略**：Redis故障时直接查询MySQL

---

## 2. Redis数据结构

### 2.1 Key设计

```
Key: ai:profile:{customerId}
Type: Hash
TTL: 24小时
```

### 2.2 Hash字段设计

```
# 基本信息
basic:customerId    → "user-001"
basic:name          → "张三"
basic:age           → 35
basic:gender        → "男"
basic:phone         → "13800138000"
basic:email         → "zhangsan@example.com"

# 健康档案
health:bmi          → 24.5
health:bloodPressure → "120/80"
health:bloodSugar   → "5.6"
health:heartRate    → 72
health:weight       → 70.5
health:height       → 175
health:goals        → ["控制血糖", "减重"]  # JSON数组

# 行为偏好
behavior:dietPreference     → ["低糖", "高蛋白", "少油"]  # JSON数组
behavior:exerciseFrequency  → "每周3次"
behavior:sleepHours         → 7.5
behavior:smokingStatus      → "不吸烟"
behavior:drinkingStatus     → "偶尔饮酒"

# 全局记忆
memories  → JSON数组（GlobalMemoryDTO列表）

# 元数据
meta:cachedAt       → 1715990400000  # 缓存时间戳
meta:version        → "v1"           # 版本号
```

**设计说明**：
- 使用冒号分隔命名空间（basic:、health:、behavior:）
- 复杂对象（数组、嵌套对象）使用JSON序列化
- 元数据字段用于缓存管理

---

## 3. RedisProfileService

### 3.1 核心服务实现

```java
package com.lx.governance.profile.service;

import com.alibaba.fastjson.JSON;
import com.lx.governance.profile.dto.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Redis画像服务
 * 负责用户画像的Redis缓存管理
 */
@Service
@Slf4j
public class RedisProfileService {
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    @Autowired
    private UserProfileService userProfileService;
    
    private static final String PROFILE_KEY_PREFIX = "ai:profile:";
    private static final int CACHE_TTL_HOURS = 24;
    
    /**
     * 获取用户画像（优先Redis，不存在则从MySQL构建）
     * 
     * @param customerId 用户ID
     * @return 用户画像
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
            
            // 2. Redis未命中，从MySQL构建
            log.info("Redis未命中，从MySQL构建画像: customerId={}", customerId);
            UserProfileDTO profile = userProfileService.buildUserProfile(customerId);
            
            if (profile != null) {
                // 3. 回写Redis
                saveToRedis(key, profile);
            }
            
            return profile;
            
        } catch (Exception e) {
            log.error("从Redis获取画像失败，降级到MySQL: customerId={}", customerId, e);
            // 降级：直接查询MySQL
            return userProfileService.buildUserProfile(customerId);
        }
    }
    
    /**
     * 主动刷新画像缓存（问卷/报告解析后调用）
     * 
     * @param customerId 用户ID
     */
    public void refreshProfile(String customerId) {
        log.info("主动刷新用户画像缓存: customerId={}", customerId);
        
        try {
            // 1. 从MySQL重新构建
            UserProfileDTO profile = userProfileService.buildUserProfile(customerId);
            
            if (profile != null) {
                // 2. 更新Redis
                String key = PROFILE_KEY_PREFIX + customerId;
                saveToRedis(key, profile);
                
                log.info("画像缓存刷新成功: customerId={}", customerId);
            } else {
                log.warn("用户不存在，无法刷新画像: customerId={}", customerId);
            }
            
        } catch (Exception e) {
            log.error("刷新画像缓存失败: customerId={}", customerId, e);
            throw new RuntimeException("刷新画像缓存失败", e);
        }
    }
    
    /**
     * 删除画像缓存
     * 
     * @param customerId 用户ID
     */
    public void evictProfile(String customerId) {
        String key = PROFILE_KEY_PREFIX + customerId;
        redisTemplate.delete(key);
        log.info("删除用户画像缓存: customerId={}", customerId);
    }
    
    /**
     * 批量预热缓存
     * 
     * @param customerIds 用户ID列表
     */
    public void warmUpCache(List<String> customerIds) {
        log.info("开始批量预热画像缓存: count={}", customerIds.size());
        
        int success = 0;
        int failed = 0;
        
        for (String customerId : customerIds) {
            try {
                refreshProfile(customerId);
                success++;
            } catch (Exception e) {
                log.error("预热失败: customerId={}", customerId, e);
                failed++;
            }
        }
        
        log.info("画像缓存预热完成: success={}, failed={}", success, failed);
    }
    
    // ========== 私有方法 ==========
    
    /**
     * 保存到Redis
     */
    private void saveToRedis(String key, UserProfileDTO profile) {
        Map<String, Object> hash = serializeProfile(profile);
        
        // 添加元数据
        hash.put("meta:cachedAt", System.currentTimeMillis());
        hash.put("meta:version", "v1");
        
        // 批量写入Hash
        redisTemplate.opsForHash().putAll(key, hash);
        
        // 设置TTL
        redisTemplate.expire(key, CACHE_TTL_HOURS, TimeUnit.HOURS);
        
        log.debug("画像已保存到Redis: key={}, fields={}", key, hash.size());
    }
    
    /**
     * 序列化画像为Hash
     */
    private Map<String, Object> serializeProfile(UserProfileDTO profile) {
        Map<String, Object> hash = new HashMap<>();
        
        // 基本信息
        if (profile.getBasicInfo() != null) {
            BasicInfoDTO basic = profile.getBasicInfo();
            hash.put("basic:customerId", basic.getCustomerId());
            hash.put("basic:name", basic.getName());
            hash.put("basic:age", basic.getAge());
            hash.put("basic:gender", basic.getGender());
            
            if (basic.getPhone() != null) {
                hash.put("basic:phone", basic.getPhone());
            }
            if (basic.getEmail() != null) {
                hash.put("basic:email", basic.getEmail());
            }
        }
        
        // 健康档案
        if (profile.getHealthProfile() != null) {
            HealthProfileDTO health = profile.getHealthProfile();
            
            if (health.getBmi() != null) {
                hash.put("health:bmi", health.getBmi());
            }
            if (health.getBloodPressure() != null) {
                hash.put("health:bloodPressure", health.getBloodPressure());
            }
            if (health.getBloodSugar() != null) {
                hash.put("health:bloodSugar", health.getBloodSugar());
            }
            if (health.getHeartRate() != null) {
                hash.put("health:heartRate", health.getHeartRate());
            }
            if (health.getWeight() != null) {
                hash.put("health:weight", health.getWeight());
            }
            if (health.getHeight() != null) {
                hash.put("health:height", health.getHeight());
            }
            if (health.getHealthGoals() != null && !health.getHealthGoals().isEmpty()) {
                hash.put("health:goals", JSON.toJSONString(health.getHealthGoals()));
            }
        }
        
        // 行为偏好
        if (profile.getBehaviorPreferences() != null) {
            BehaviorPreferencesDTO behavior = profile.getBehaviorPreferences();
            
            if (behavior.getDietPreference() != null && !behavior.getDietPreference().isEmpty()) {
                hash.put("behavior:dietPreference", JSON.toJSONString(behavior.getDietPreference()));
            }
            if (behavior.getExerciseFrequency() != null) {
                hash.put("behavior:exerciseFrequency", behavior.getExerciseFrequency());
            }
            if (behavior.getSleepHours() != null) {
                hash.put("behavior:sleepHours", behavior.getSleepHours());
            }
            if (behavior.getSmokingStatus() != null) {
                hash.put("behavior:smokingStatus", behavior.getSmokingStatus());
            }
            if (behavior.getDrinkingStatus() != null) {
                hash.put("behavior:drinkingStatus", behavior.getDrinkingStatus());
            }
        }
        
        // 全局记忆
        if (profile.getGlobalMemories() != null && !profile.getGlobalMemories().isEmpty()) {
            hash.put("memories", JSON.toJSONString(profile.getGlobalMemories()));
        }
        
        return hash;
    }
    
    /**
     * 反序列化Hash为画像
     */
    private UserProfileDTO deserializeProfile(Map<Object, Object> hash) {
        // 基本信息
        BasicInfoDTO basicInfo = BasicInfoDTO.builder()
            .customerId(getStringValue(hash, "basic:customerId"))
            .name(getStringValue(hash, "basic:name"))
            .age(getIntegerValue(hash, "basic:age"))
            .gender(getStringValue(hash, "basic:gender"))
            .phone(getStringValue(hash, "basic:phone"))
            .email(getStringValue(hash, "basic:email"))
            .build();
        
        // 健康档案
        HealthProfileDTO healthProfile = HealthProfileDTO.builder()
            .bmi(getDoubleValue(hash, "health:bmi"))
            .bloodPressure(getStringValue(hash, "health:bloodPressure"))
            .bloodSugar(getDoubleValue(hash, "health:bloodSugar"))
            .heartRate(getIntegerValue(hash, "health:heartRate"))
            .weight(getDoubleValue(hash, "health:weight"))
            .height(getIntegerValue(hash, "health:height"))
            .healthGoals(getListValue(hash, "health:goals", String.class))
            .build();
        
        // 行为偏好
        BehaviorPreferencesDTO behaviorPreferences = BehaviorPreferencesDTO.builder()
            .dietPreference(getListValue(hash, "behavior:dietPreference", String.class))
            .exerciseFrequency(getStringValue(hash, "behavior:exerciseFrequency"))
            .sleepHours(getDoubleValue(hash, "behavior:sleepHours"))
            .smokingStatus(getStringValue(hash, "behavior:smokingStatus"))
            .drinkingStatus(getStringValue(hash, "behavior:drinkingStatus"))
            .build();
        
        // 全局记忆
        List<GlobalMemoryDTO> globalMemories = getListValue(hash, "memories", GlobalMemoryDTO.class);
        
        return UserProfileDTO.builder()
            .basicInfo(basicInfo)
            .healthProfile(healthProfile)
            .behaviorPreferences(behaviorPreferences)
            .globalMemories(globalMemories)
            .build();
    }
    
    // ========== 辅助方法 ==========
    
    private String getStringValue(Map<Object, Object> hash, String key) {
        Object value = hash.get(key);
        return value != null ? value.toString() : null;
    }
    
    private Integer getIntegerValue(Map<Object, Object> hash, String key) {
        Object value = hash.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Integer) {
            return (Integer) value;
        }
        return Integer.parseInt(value.toString());
    }
    
    private Double getDoubleValue(Map<Object, Object> hash, String key) {
        Object value = hash.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Double) {
            return (Double) value;
        }
        return Double.parseDouble(value.toString());
    }
    
    private <T> List<T> getListValue(Map<Object, Object> hash, String key, Class<T> clazz) {
        Object value = hash.get(key);
        if (value == null) {
            return null;
        }
        return JSON.parseArray(value.toString(), clazz);
    }
}
```

---

## 4. RabbitMQ监听器改造

### 4.1 UserProfileMessageListener

```java
package com.lx.governance.profile.listener;

import com.alibaba.fastjson.JSON;
import com.lx.governance.profile.dto.UserProfileDTO;
import com.lx.governance.profile.dto.UserProfileRequestMessage;
import com.lx.governance.profile.dto.UserProfileResponseMessage;
import com.lx.governance.profile.service.RedisProfileService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 用户画像消息监听器（改造版）
 * 使用RedisProfileService获取画像
 */
@Component
@Slf4j
public class UserProfileMessageListener {
    
    @Autowired
    private RedisProfileService redisProfileService;
    
    @Autowired
    private RabbitTemplate rabbitTemplate;
    
    @RabbitListener(queues = "user.profile.request")
    public void handleProfileRequest(Message message, Channel channel) {
        long startTime = System.currentTimeMillis();
        String correlationId = message.getMessageProperties().getCorrelationId();
        String replyTo = message.getMessageProperties().getReplyTo();
        
        try {
            // 1. 解析请求
            String body = new String(message.getBody());
            UserProfileRequestMessage request = JSON.parseObject(body, UserProfileRequestMessage.class);
            
            log.info("收到画像请求: customerId={}, correlationId={}", 
                request.getCustomerId(), correlationId);
            
            // 2. 从Redis获取画像（内部会处理缓存未命中）
            UserProfileDTO profile = redisProfileService.getProfile(request.getCustomerId());
            
            // 3. 构建响应
            UserProfileResponseMessage response = new UserProfileResponseMessage();
            response.setSuccess(profile != null);
            response.setProfile(profile);
            response.setCorrelationId(correlationId);
            
            // 4. 发送响应
            if (replyTo != null) {
                rabbitTemplate.convertAndSend(
                    replyTo,
                    JSON.toJSONString(response),
                    msg -> {
                        msg.getMessageProperties().setCorrelationId(correlationId);
                        return msg;
                    }
                );
            }
            
            // 5. 手动ACK
            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
            
            long cost = System.currentTimeMillis() - startTime;
            log.info("画像请求处理完成: customerId={}, cost={}ms, correlationId={}", 
                request.getCustomerId(), cost, correlationId);
            
        } catch (Exception e) {
            log.error("处理画像请求失败: correlationId={}", correlationId, e);
            
            try {
                // NACK并重新入队
                channel.basicNack(message.getMessageProperties().getDeliveryTag(), false, true);
            } catch (Exception ex) {
                log.error("NACK失败", ex);
            }
        }
    }
}
```

---

## 5. REST接口

### 5.1 ProfileCacheController

```java
package com.lx.governance.profile.controller;

import com.lx.governance.common.dto.ApiResponse;
import com.lx.governance.profile.service.RedisProfileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 画像缓存管理接口
 */
@RestController
@RequestMapping("/api/profile")
@Slf4j
public class ProfileCacheController {
    
    @Autowired
    private RedisProfileService redisProfileService;
    
    /**
     * 刷新画像缓存
     * 问卷/报告解析后调用此接口
     * 
     * @param customerId 用户ID
     * @return 响应
     */
    @PostMapping("/refresh/{customerId}")
    public ApiResponse<Void> refreshProfile(@PathVariable String customerId) {
        log.info("收到画像刷新请求: customerId={}", customerId);
        
        try {
            redisProfileService.refreshProfile(customerId);
            return ApiResponse.success();
        } catch (Exception e) {
            log.error("刷新画像失败: customerId={}", customerId, e);
            return ApiResponse.error("刷新画像失败: " + e.getMessage());
        }
    }
    
    /**
     * 删除画像缓存
     * 
     * @param customerId 用户ID
     * @return 响应
     */
    @DeleteMapping("/cache/{customerId}")
    public ApiResponse<Void> evictProfile(@PathVariable String customerId) {
        log.info("收到画像删除请求: customerId={}", customerId);
        
        try {
            redisProfileService.evictProfile(customerId);
            return ApiResponse.success();
        } catch (Exception e) {
            log.error("删除画像失败: customerId={}", customerId, e);
            return ApiResponse.error("删除画像失败: " + e.getMessage());
        }
    }
    
    /**
     * 批量预热缓存
     * 
     * @param customerIds 用户ID列表
     * @return 响应
     */
    @PostMapping("/warmup")
    public ApiResponse<Void> warmUpCache(@RequestBody List<String> customerIds) {
        log.info("收到批量预热请求: count={}", customerIds.size());
        
        try {
            redisProfileService.warmUpCache(customerIds);
            return ApiResponse.success();
        } catch (Exception e) {
            log.error("批量预热失败", e);
            return ApiResponse.error("批量预热失败: " + e.getMessage());
        }
    }
}
```

---

## 6. 监控指标

### 6.1 指标定义

```java
@Component
public class ProfileCacheMetrics {
    
    private final Counter cacheHitCounter;
    private final Counter cacheMissCounter;
    private final Timer cacheGetTimer;
    private final Timer cacheRefreshTimer;
    
    public ProfileCacheMetrics(MeterRegistry meterRegistry) {
        this.cacheHitCounter = Counter.builder("ai.profile.cache.hit")
            .description("Profile cache hits")
            .register(meterRegistry);
        
        this.cacheMissCounter = Counter.builder("ai.profile.cache.miss")
            .description("Profile cache misses")
            .register(meterRegistry);
        
        this.cacheGetTimer = Timer.builder("ai.profile.cache.get.time")
            .description("Profile cache get time")
            .register(meterRegistry);
        
        this.cacheRefreshTimer = Timer.builder("ai.profile.cache.refresh.time")
            .description("Profile cache refresh time")
            .register(meterRegistry);
    }
    
    public void recordCacheHit() {
        cacheHitCounter.increment();
    }
    
    public void recordCacheMiss() {
        cacheMissCounter.increment();
    }
    
    public <T> T recordCacheGet(Supplier<T> operation) {
        return cacheGetTimer.record(operation);
    }
    
    public <T> T recordCacheRefresh(Supplier<T> operation) {
        return cacheRefreshTimer.record(operation);
    }
}
```

### 6.2 监控大盘

```
# 缓存命中率
ai.profile.cache.hit / (ai.profile.cache.hit + ai.profile.cache.miss)

# 缓存获取时间
ai.profile.cache.get.time (P50/P95/P99)

# 缓存刷新时间
ai.profile.cache.refresh.time (P50/P95/P99)
```

---

## 7. 测试用例

### 7.1 单元测试

```java
@SpringBootTest
class RedisProfileServiceTest {
    
    @Autowired
    private RedisProfileService redisProfileService;
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    @Test
    void testGetProfile_CacheHit() {
        // 预先写入缓存
        String customerId = "test-user-001";
        UserProfileDTO profile = buildTestProfile(customerId);
        redisProfileService.refreshProfile(customerId);
        
        // 获取画像（应该命中缓存）
        UserProfileDTO cached = redisProfileService.getProfile(customerId);
        
        assertNotNull(cached);
        assertEquals(customerId, cached.getBasicInfo().getCustomerId());
    }
    
    @Test
    void testGetProfile_CacheMiss() {
        // 清空缓存
        String customerId = "test-user-002";
        redisProfileService.evictProfile(customerId);
        
        // 获取画像（应该未命中，从MySQL构建）
        UserProfileDTO profile = redisProfileService.getProfile(customerId);
        
        assertNotNull(profile);
        
        // 验证已写入缓存
        String key = "ai:profile:" + customerId;
        Boolean exists = redisTemplate.hasKey(key);
        assertTrue(exists);
    }
    
    @Test
    void testRefreshProfile() {
        String customerId = "test-user-003";
        
        // 刷新画像
        redisProfileService.refreshProfile(customerId);
        
        // 验证缓存存在
        String key = "ai:profile:" + customerId;
        Map<Object, Object> cached = redisTemplate.opsForHash().entries(key);
        
        assertFalse(cached.isEmpty());
        assertEquals(customerId, cached.get("basic:customerId"));
    }
}
```

---

## 8. 性能对比

| 场景 | 旧方案（MySQL） | 新方案（Redis） | 提升 |
|------|----------------|----------------|------|
| 画像获取（缓存命中） | 200ms | 50ms | 75% |
| 画像获取（缓存未命中） | 200ms | 220ms | -10% |
| 平均响应时间（80%命中率） | 200ms | 84ms | 58% |

---

## 9. 常见问题

**Q1: Redis故障时如何保证服务可用？**  
A: 捕获Redis异常，降级到直接查询MySQL，不影响主流程。

**Q2: 如何保证缓存数据的一致性？**  
A: 通过主动刷新机制，问卷/报告解析后调用刷新接口更新缓存。

**Q3: 为什么使用Hash而不是String？**  
A: Hash支持部分字段更新，节省网络传输和序列化开销。

**Q4: TTL设置为24小时是否合理？**  
A: 基于用户画像更新频率（通常每天一次），24小时TTL可以平衡数据新鲜度和缓存命中率。
