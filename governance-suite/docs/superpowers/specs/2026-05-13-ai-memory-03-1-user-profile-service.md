# AI 记忆功能设计文档 - 第三部分之一：user-profile-service 组件设计

## 文档信息

- **创建日期**: 2026-05-13
- **项目**: governance-suite
- **服务**: user-profile-service
- **端口**: 8101

## 一、服务职责

user-profile-service 负责构建用户的全局画像，包括：

1. 查询用户基础信息（Customer 表）
2. 查询健康评估数据（HealthAssessment 表）
3. 查询行为偏好（打卡、饮食、运动统计）
4. 查询用户全局记忆（b_ai_user_memory 表）
5. 聚合数据并序列化为 JSON
6. 通过 RabbitMQ 响应画像请求

## 二、核心组件

### 2.1 UserProfileService

**职责**：构建用户全局画像

**代码设计**：

```java
package com.lx.governance.profile.service;

import com.lx.governance.profile.dto.UserProfileDTO;
import com.lx.governance.profile.dto.BasicInfoDTO;
import com.lx.governance.profile.dto.HealthProfileDTO;
import com.lx.governance.profile.dto.BehaviorPreferencesDTO;
import com.lx.governance.profile.dto.GlobalMemoryDTO;
import com.lx.governance.profile.mapper.CustomerMapper;
import com.lx.governance.profile.mapper.HealthAssessmentMapper;
import com.lx.governance.profile.mapper.AiUserMemoryMapper;
import com.lx.governance.profile.entity.CustomerEntity;
import com.lx.governance.profile.entity.HealthAssessmentEntity;
import com.lx.governance.profile.entity.AiUserMemoryEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserProfileService {
    
    private final CustomerMapper customerMapper;
    private final HealthAssessmentMapper healthAssessmentMapper;
    private final AiUserMemoryMapper aiUserMemoryMapper;
    
    /**
     * 构建用户全局画像
     * 
     * @param customerId 用户ID
     * @return 用户画像DTO
     */
    @Cacheable(value = "user:profile", key = "#customerId", unless = "#result == null")
    public UserProfileDTO buildUserProfile(String customerId) {
        long startTime = System.currentTimeMillis();
        
        try {
            log.info("开始构建用户画像: customerId={}", customerId);
            
            // 1. 查询基础信息
            BasicInfoDTO basicInfo = buildBasicInfo(customerId);
            if (basicInfo == null) {
                log.warn("用户不存在: customerId={}", customerId);
                return null;
            }
            
            // 2. 查询健康档案
            HealthProfileDTO healthProfile = buildHealthProfile(customerId);
            
            // 3. 查询行为偏好
            BehaviorPreferencesDTO behaviorPreferences = buildBehaviorPreferences(customerId);
            
            // 4. 查询全局记忆
            List<GlobalMemoryDTO> globalMemories = buildGlobalMemories(customerId);
            
            // 5. 组装画像
            UserProfileDTO profile = UserProfileDTO.builder()
                .basicInfo(basicInfo)
                .healthProfile(healthProfile)
                .behaviorPreferences(behaviorPreferences)
                .globalMemories(globalMemories)
                .build();
            
            long cost = System.currentTimeMillis() - startTime;
            log.info("用户画像构建完成: customerId={}, cost={}ms", customerId, cost);
            
            return profile;
            
        } catch (Exception e) {
            log.error("构建用户画像失败: customerId={}", customerId, e);
            throw new RuntimeException("构建用户画像失败", e);
        }
    }
    
    /**
     * 构建基础信息
     */
    private BasicInfoDTO buildBasicInfo(String customerId) {
        CustomerEntity customer = customerMapper.selectByCustomerId(customerId);
        if (customer == null) {
            return null;
        }
        
        return BasicInfoDTO.builder()
            .customerId(customer.getCustomerId())
            .name(customer.getName())
            .age(customer.getAge())
            .gender(customer.getGender())
            .phone(customer.getPhone())
            .email(customer.getEmail())
            .build();
    }
    
    /**
     * 构建健康档案
     */
    private HealthProfileDTO buildHealthProfile(String customerId) {
        // 查询最近的健康评估
        HealthAssessmentEntity assessment = healthAssessmentMapper.selectLatestByCustomerId(customerId);
        
        if (assessment == null) {
            return HealthProfileDTO.empty();
        }
        
        return HealthProfileDTO.builder()
            .bmi(assessment.getBmi())
            .bloodPressure(assessment.getBloodPressure())
            .bloodSugar(assessment.getBloodSugar())
            .latestAssessmentDate(assessment.getAssessmentDate())
            .healthGoals(parseHealthGoals(assessment.getHealthGoals()))
            .build();
    }
    
    /**
     * 构建行为偏好
     */
    private BehaviorPreferencesDTO buildBehaviorPreferences(String customerId) {
        // 这里可以查询打卡记录、饮食记录等
        // 简化实现，返回默认值
        return BehaviorPreferencesDTO.builder()
            .dietPreference("均衡饮食")
            .exerciseFrequency("每周3次")
            .checkInStreak(0)
            .build();
    }
    
    /**
     * 构建全局记忆
     */
    private List<GlobalMemoryDTO> buildGlobalMemories(String customerId) {
        List<AiUserMemoryEntity> memories = aiUserMemoryMapper.selectByCustomerId(customerId);
        
        return memories.stream()
            .filter(m -> m.getIsDeleted() == 0)
            .filter(m -> m.getVerified() == 1)  // 只返回已验证的记忆
            .map(m -> GlobalMemoryDTO.builder()
                .type(m.getMemoryType())
                .key(m.getMemoryKey())
                .content(m.getMemoryContent())
                .verified(m.getVerified() == 1)
                .build())
            .collect(Collectors.toList());
    }
    
    /**
     * 解析健康目标
     */
    private List<String> parseHealthGoals(String healthGoalsJson) {
        // 简化实现
        if (healthGoalsJson == null || healthGoalsJson.isEmpty()) {
            return List.of();
        }
        // 实际应该用 JSON 解析
        return List.of(healthGoalsJson.split(","));
    }
}
```

---

### 2.2 UserProfileMessageListener

**职责**：监听 RabbitMQ 请求队列，处理用户画像请求

**代码设计**：

```java
package com.lx.governance.profile.listener;

import com.lx.governance.common.trace.Trace;
import com.lx.governance.profile.dto.UserProfileDTO;
import com.lx.governance.profile.dto.UserProfileRequestMessage;
import com.lx.governance.profile.dto.UserProfileResponseMessage;
import com.lx.governance.profile.service.UserProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserProfileMessageListener {
    
    private final UserProfileService userProfileService;
    private final RabbitTemplate rabbitTemplate;
    
    /**
     * 监听用户画像请求
     */
    @RabbitListener(queues = "user.profile.request")
    public void handleProfileRequest(UserProfileRequestMessage request) {
        String correlationId = request.getCorrelationId();
        String customerId = request.getCustomerId();
        
        // 设置 traceId
        Trace.setTraceId(correlationId);
        
        log.info("[{}] 收到用户画像请求: customerId={}", correlationId, customerId);
        
        UserProfileResponseMessage response = new UserProfileResponseMessage();
        response.setCorrelationId(correlationId);
        response.setTimestamp(System.currentTimeMillis());
        
        try {
            // 构建用户画像
            UserProfileDTO profile = userProfileService.buildUserProfile(customerId);
            
            if (profile == null) {
                response.setSuccess(false);
                response.setErrorMessage("用户不存在");
            } else {
                response.setSuccess(true);
                response.setData(profile);
            }
            
        } catch (Exception e) {
            log.error("[{}] 构建用户画像失败: customerId={}", correlationId, customerId, e);
            response.setSuccess(false);
            response.setErrorMessage("构建用户画像失败: " + e.getMessage());
        }
        
        // 发送响应
        String responseRoutingKey = "user.profile.response." + correlationId;
        rabbitTemplate.convertAndSend(
            "user.profile.exchange",
            responseRoutingKey,
            response
        );
        
        log.info("[{}] 用户画像响应已发送: success={}", correlationId, response.getSuccess());
    }
}
```

---

## 三、数据模型

### 3.1 DTO 定义

**UserProfileDTO**

```java
package com.lx.governance.profile.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileDTO {
    private BasicInfoDTO basicInfo;
    private HealthProfileDTO healthProfile;
    private BehaviorPreferencesDTO behaviorPreferences;
    private List<GlobalMemoryDTO> globalMemories;
}
```

**BasicInfoDTO**

```java
package com.lx.governance.profile.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BasicInfoDTO {
    private String customerId;
    private String name;
    private Integer age;
    private String gender;
    private String phone;
    private String email;
}
```

**HealthProfileDTO**

```java
package com.lx.governance.profile.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HealthProfileDTO {
    private Double bmi;
    private String bloodPressure;
    private Double bloodSugar;
    private LocalDate latestAssessmentDate;
    private List<String> healthGoals;
    
    public static HealthProfileDTO empty() {
        return HealthProfileDTO.builder()
            .healthGoals(List.of())
            .build();
    }
}
```

**BehaviorPreferencesDTO**

```java
package com.lx.governance.profile.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BehaviorPreferencesDTO {
    private String dietPreference;
    private String exerciseFrequency;
    private Integer checkInStreak;
}
```

**GlobalMemoryDTO**

```java
package com.lx.governance.profile.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GlobalMemoryDTO {
    private String type;
    private String key;
    private String content;
    private Boolean verified;
}
```

---

### 3.2 消息模型

**UserProfileRequestMessage**

```java
package com.lx.governance.profile.dto;

import lombok.Data;

@Data
public class UserProfileRequestMessage {
    private String correlationId;
    private String customerId;
    private Long timestamp;
}
```

**UserProfileResponseMessage**

```java
package com.lx.governance.profile.dto;

import lombok.Data;

@Data
public class UserProfileResponseMessage {
    private String correlationId;
    private Boolean success;
    private String errorMessage;
    private UserProfileDTO data;
    private Long timestamp;
}
```

---

## 四、Mapper 接口

### 4.1 CustomerMapper

```java
package com.lx.governance.profile.mapper;

import com.lx.governance.profile.entity.CustomerEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CustomerMapper {
    
    /**
     * 根据用户ID查询用户信息
     */
    CustomerEntity selectByCustomerId(@Param("customerId") String customerId);
}
```

**CustomerMapper.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" 
    "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.lx.governance.profile.mapper.CustomerMapper">
    
    <select id="selectByCustomerId" resultType="com.lx.governance.profile.entity.CustomerEntity">
        SELECT 
            customer_id,
            name,
            age,
            gender,
            phone,
            email,
            create_time,
            update_time
        FROM t_customer
        WHERE customer_id = #{customerId}
          AND is_deleted = 0
    </select>
    
</mapper>
```

---

### 4.2 HealthAssessmentMapper

```java
package com.lx.governance.profile.mapper;

import com.lx.governance.profile.entity.HealthAssessmentEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface HealthAssessmentMapper {
    
    /**
     * 查询用户最近的健康评估
     */
    HealthAssessmentEntity selectLatestByCustomerId(@Param("customerId") String customerId);
}
```

**HealthAssessmentMapper.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" 
    "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.lx.governance.profile.mapper.HealthAssessmentMapper">
    
    <select id="selectLatestByCustomerId" resultType="com.lx.governance.profile.entity.HealthAssessmentEntity">
        SELECT 
            id,
            customer_id,
            bmi,
            blood_pressure,
            blood_sugar,
            assessment_date,
            health_goals,
            create_time
        FROM t_health_assessment
        WHERE customer_id = #{customerId}
          AND is_deleted = 0
        ORDER BY assessment_date DESC
        LIMIT 1
    </select>
    
</mapper>
```

---

### 4.3 AiUserMemoryMapper

```java
package com.lx.governance.profile.mapper;

import com.lx.governance.profile.entity.AiUserMemoryEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AiUserMemoryMapper {
    
    /**
     * 查询用户的全局记忆
     */
    List<AiUserMemoryEntity> selectByCustomerId(@Param("customerId") String customerId);
}
```

**AiUserMemoryMapper.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" 
    "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.lx.governance.profile.mapper.AiUserMemoryMapper">
    
    <select id="selectByCustomerId" resultType="com.lx.governance.profile.entity.AiUserMemoryEntity">
        SELECT 
            id,
            customer_id,
            memory_type,
            memory_key,
            memory_content,
            embedding_id,
            confidence_score,
            source,
            verified,
            verified_by,
            verified_at,
            is_deleted,
            create_time,
            update_time
        FROM b_ai_user_memory
        WHERE customer_id = #{customerId}
          AND is_deleted = 0
        ORDER BY verified DESC, confidence_score DESC, create_time DESC
    </select>
    
</mapper>
```

---

## 五、配置类

### 5.1 RabbitMQConfig

```java
package com.lx.governance.profile.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {
    
    public static final String USER_PROFILE_EXCHANGE = "user.profile.exchange";
    public static final String USER_PROFILE_REQUEST_QUEUE = "user.profile.request";
    public static final String DLX_EXCHANGE = "dlx.exchange";
    public static final String DLX_QUEUE = "dlx.queue";
    
    /**
     * 用户画像交换机
     */
    @Bean
    public DirectExchange userProfileExchange() {
        return new DirectExchange(USER_PROFILE_EXCHANGE, true, false);
    }
    
    /**
     * 用户画像请求队列
     */
    @Bean
    public Queue userProfileRequestQueue() {
        return QueueBuilder.durable(USER_PROFILE_REQUEST_QUEUE)
            .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
            .withArgument("x-message-ttl", 60000)  // 消息TTL 60秒
            .build();
    }
    
    /**
     * 绑定请求队列到交换机
     */
    @Bean
    public Binding userProfileRequestBinding() {
        return BindingBuilder
            .bind(userProfileRequestQueue())
            .to(userProfileExchange())
            .with("user.profile.request");
    }
    
    /**
     * 死信交换机
     */
    @Bean
    public FanoutExchange dlxExchange() {
        return new FanoutExchange(DLX_EXCHANGE, true, false);
    }
    
    /**
     * 死信队列
     */
    @Bean
    public Queue dlxQueue() {
        return QueueBuilder.durable(DLX_QUEUE).build();
    }
    
    /**
     * 绑定死信队列
     */
    @Bean
    public Binding dlxBinding() {
        return BindingBuilder
            .bind(dlxQueue())
            .to(dlxExchange());
    }
    
    /**
     * JSON 消息转换器
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
```

---

### 5.2 CacheConfig

```java
package com.lx.governance.profile.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

@Configuration
@EnableCaching
public class CacheConfig {
    
    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(5))  // 默认5分钟过期
            .serializeKeysWith(
                RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer())
            )
            .serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer())
            )
            .disableCachingNullValues();
        
        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(config)
            .build();
    }
}
```

---

## 六、总结

本文档详细设计了 user-profile-service 的核心组件：

1. **UserProfileService**：负责聚合多个数据源构建用户画像
2. **UserProfileMessageListener**：监听 MQ 请求并响应
3. **DTO 模型**：定义了完整的数据传输对象
4. **Mapper 接口**：定义了数据访问层
5. **配置类**：RabbitMQ 和 Redis 缓存配置

下一步将设计 vector-memory-service 的组件。
