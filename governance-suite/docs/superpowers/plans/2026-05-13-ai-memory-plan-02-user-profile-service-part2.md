# AI 记忆功能实施计划 - Plan 2: user-profile-service (Part 2/2)

> 继续 Part 1

## Task 4: 创建 Mapper 接口和 XML

**Files:**
- Create: `user-profile-service/src/main/java/com/lx/governance/profile/mapper/*.java` (3个)
- Create: `user-profile-service/src/main/resources/mapper/*.xml` (3个)

- [ ] **Step 1: 创建 mapper 目录**

```bash
mkdir -p user-profile-service/src/main/java/com/lx/governance/profile/mapper
```

- [ ] **Step 2: 创建 CustomerMapper 接口**

```bash
cat > user-profile-service/src/main/java/com/lx/governance/profile/mapper/CustomerMapper.java << 'JAVAEOF'
package com.lx.governance.profile.mapper;

import com.lx.governance.profile.entity.CustomerEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CustomerMapper {
    CustomerEntity selectByCustomerId(@Param("customerId") String customerId);
}
JAVAEOF
```

- [ ] **Step 3: 创建 CustomerMapper.xml**

```bash
cat > user-profile-service/src/main/resources/mapper/CustomerMapper.xml << 'XMLEOF'
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
XMLEOF
```

- [ ] **Step 4: 创建 HealthAssessmentMapper 接口**

```bash
cat > user-profile-service/src/main/java/com/lx/governance/profile/mapper/HealthAssessmentMapper.java << 'JAVAEOF'
package com.lx.governance.profile.mapper;

import com.lx.governance.profile.entity.HealthAssessmentEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface HealthAssessmentMapper {
    HealthAssessmentEntity selectLatestByCustomerId(@Param("customerId") String customerId);
}
JAVAEOF
```

- [ ] **Step 5: 创建 HealthAssessmentMapper.xml**

```bash
cat > user-profile-service/src/main/resources/mapper/HealthAssessmentMapper.xml << 'XMLEOF'
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
XMLEOF
```

- [ ] **Step 6: 创建 AiUserMemoryMapper 接口**

```bash
cat > user-profile-service/src/main/java/com/lx/governance/profile/mapper/AiUserMemoryMapper.java << 'JAVAEOF'
package com.lx.governance.profile.mapper;

import com.lx.governance.profile.entity.AiUserMemoryEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface AiUserMemoryMapper {
    List<AiUserMemoryEntity> selectByCustomerId(@Param("customerId") String customerId);
}
JAVAEOF
```

- [ ] **Step 7: 创建 AiUserMemoryMapper.xml**

```bash
cat > user-profile-service/src/main/resources/mapper/AiUserMemoryMapper.xml << 'XMLEOF'
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
XMLEOF
```

- [ ] **Step 8: 验证编译**

```bash
mvn -pl user-profile-service compile
```

Expected: BUILD SUCCESS

- [ ] **Step 9: 提交更改**

```bash
git add user-profile-service/src/main/java/com/lx/governance/profile/mapper/
git add user-profile-service/src/main/resources/mapper/
git commit -m "feat(user-profile): add mapper interfaces and XML"
```

---

## Task 5: 实现 UserProfileService

**Files:**
- Create: `user-profile-service/src/main/java/com/lx/governance/profile/service/UserProfileService.java`

- [ ] **Step 1: 创建 service 目录**

```bash
mkdir -p user-profile-service/src/main/java/com/lx/governance/profile/service
```

- [ ] **Step 2: 创建 UserProfileService（完整实现）**

由于文件较大，使用 cat 命令创建：

```bash
cat > user-profile-service/src/main/java/com/lx/governance/profile/service/UserProfileService.java << 'JAVAEOF'
package com.lx.governance.profile.service;

import com.lx.governance.profile.dto.*;
import com.lx.governance.profile.entity.*;
import com.lx.governance.profile.mapper.*;
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
    
    @Cacheable(value = "user:profile", key = "#customerId", unless = "#result == null")
    public UserProfileDTO buildUserProfile(String customerId) {
        long startTime = System.currentTimeMillis();
        
        try {
            log.info("开始构建用户画像: customerId={}", customerId);
            
            BasicInfoDTO basicInfo = buildBasicInfo(customerId);
            if (basicInfo == null) {
                log.warn("用户不存在: customerId={}", customerId);
                return null;
            }
            
            HealthProfileDTO healthProfile = buildHealthProfile(customerId);
            BehaviorPreferencesDTO behaviorPreferences = buildBehaviorPreferences(customerId);
            List<GlobalMemoryDTO> globalMemories = buildGlobalMemories(customerId);
            
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
    
    private HealthProfileDTO buildHealthProfile(String customerId) {
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
    
    private BehaviorPreferencesDTO buildBehaviorPreferences(String customerId) {
        return BehaviorPreferencesDTO.builder()
            .dietPreference("均衡饮食")
            .exerciseFrequency("每周3次")
            .checkInStreak(0)
            .build();
    }
    
    private List<GlobalMemoryDTO> buildGlobalMemories(String customerId) {
        List<AiUserMemoryEntity> memories = aiUserMemoryMapper.selectByCustomerId(customerId);
        
        return memories.stream()
            .filter(m -> m.getIsDeleted() == 0)
            .filter(m -> m.getVerified() == 1)
            .map(m -> GlobalMemoryDTO.builder()
                .type(m.getMemoryType())
                .key(m.getMemoryKey())
                .content(m.getMemoryContent())
                .verified(m.getVerified() == 1)
                .build())
            .collect(Collectors.toList());
    }
    
    private List<String> parseHealthGoals(String healthGoalsJson) {
        if (healthGoalsJson == null || healthGoalsJson.isEmpty()) {
            return List.of();
        }
        return List.of(healthGoalsJson.split(","));
    }
}
JAVAEOF
```

- [ ] **Step 3: 验证编译**

```bash
mvn -pl user-profile-service compile
```

Expected: BUILD SUCCESS

- [ ] **Step 4: 提交更改**

```bash
git add user-profile-service/src/main/java/com/lx/governance/profile/service/
git commit -m "feat(user-profile): implement UserProfileService"
```

---

## Task 6: 配置 RabbitMQ 和 Cache

**Files:**
- Create: `user-profile-service/src/main/java/com/lx/governance/profile/config/RabbitMQConfig.java`
- Create: `user-profile-service/src/main/java/com/lx/governance/profile/config/CacheConfig.java`

- [ ] **Step 1: 创建 config 目录**

```bash
mkdir -p user-profile-service/src/main/java/com/lx/governance/profile/config
```

- [ ] **Step 2: 创建 RabbitMQConfig**

```bash
cat > user-profile-service/src/main/java/com/lx/governance/profile/config/RabbitMQConfig.java << 'JAVAEOF'
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
    
    @Bean
    public DirectExchange userProfileExchange() {
        return new DirectExchange(USER_PROFILE_EXCHANGE, true, false);
    }
    
    @Bean
    public Queue userProfileRequestQueue() {
        return QueueBuilder.durable(USER_PROFILE_REQUEST_QUEUE)
            .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
            .withArgument("x-message-ttl", 60000)
            .build();
    }
    
    @Bean
    public Binding userProfileRequestBinding() {
        return BindingBuilder
            .bind(userProfileRequestQueue())
            .to(userProfileExchange())
            .with("user.profile.request");
    }
    
    @Bean
    public FanoutExchange dlxExchange() {
        return new FanoutExchange(DLX_EXCHANGE, true, false);
    }
    
    @Bean
    public Queue dlxQueue() {
        return QueueBuilder.durable(DLX_QUEUE).build();
    }
    
    @Bean
    public Binding dlxBinding() {
        return BindingBuilder
            .bind(dlxQueue())
            .to(dlxExchange());
    }
    
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
JAVAEOF
```

- [ ] **Step 3: 创建 CacheConfig**

```bash
cat > user-profile-service/src/main/java/com/lx/governance/profile/config/CacheConfig.java << 'JAVAEOF'
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
            .entryTtl(Duration.ofMinutes(5))
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
JAVAEOF
```

- [ ] **Step 4: 验证编译**

```bash
mvn -pl user-profile-service compile
```

Expected: BUILD SUCCESS

- [ ] **Step 5: 提交更改**

```bash
git add user-profile-service/src/main/java/com/lx/governance/profile/config/
git commit -m "feat(user-profile): add RabbitMQ and Cache config"
```

---

## Task 7: 实现消息监听器

**Files:**
- Create: `user-profile-service/src/main/java/com/lx/governance/profile/listener/UserProfileMessageListener.java`

- [ ] **Step 1: 创建 listener 目录**

```bash
mkdir -p user-profile-service/src/main/java/com/lx/governance/profile/listener
```

- [ ] **Step 2: 创建 UserProfileMessageListener**

```bash
cat > user-profile-service/src/main/java/com/lx/governance/profile/listener/UserProfileMessageListener.java << 'JAVAEOF'
package com.lx.governance.profile.listener;

import com.lx.governance.common.trace.Trace;
import com.lx.governance.profile.dto.*;
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
    
    @RabbitListener(queues = "user.profile.request")
    public void handleProfileRequest(UserProfileRequestMessage request) {
        String correlationId = request.getCorrelationId();
        String customerId = request.getCustomerId();
        
        Trace.setTraceId(correlationId);
        
        log.info("[{}] 收到用户画像请求: customerId={}", correlationId, customerId);
        
        UserProfileResponseMessage response = new UserProfileResponseMessage();
        response.setCorrelationId(correlationId);
        response.setTimestamp(System.currentTimeMillis());
        
        try {
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
        
        String responseRoutingKey = "user.profile.response." + correlationId;
        rabbitTemplate.convertAndSend(
            "user.profile.exchange",
            responseRoutingKey,
            response
        );
        
        log.info("[{}] 用户画像响应已发送: success={}", correlationId, response.getSuccess());
    }
}
JAVAEOF
```

- [ ] **Step 3: 验证编译**

```bash
mvn -pl user-profile-service compile
```

Expected: BUILD SUCCESS

- [ ] **Step 4: 提交更改**

```bash
git add user-profile-service/src/main/java/com/lx/governance/profile/listener/
git commit -m "feat(user-profile): implement message listener"
```

---

## Task 8: 添加日志配置

**Files:**
- Create: `user-profile-service/src/main/resources/logback-spring.xml`

- [ ] **Step 1: 创建 logback-spring.xml**

```bash
cat > user-profile-service/src/main/resources/logback-spring.xml << 'XMLEOF'
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <property name="LOG_PATH" value="logs"/>
    <property name="APP_NAME" value="user-profile-service"/>
    
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [%X{traceId}] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    
    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>${LOG_PATH}/${APP_NAME}.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>${LOG_PATH}/${APP_NAME}.%d{yyyy-MM-dd}.log</fileNamePattern>
            <maxHistory>30</maxHistory>
        </rollingPolicy>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [%X{traceId}] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    
    <logger name="com.lx.governance.profile" level="INFO"/>
    
    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="FILE"/>
    </root>
</configuration>
XMLEOF
```

- [ ] **Step 2: 提交更改**

```bash
git add user-profile-service/src/main/resources/logback-spring.xml
git commit -m "feat(user-profile): add logback configuration"
```

---

## Task 9: 构建和启动测试

**Files:**
- None (verification only)

- [ ] **Step 1: 完整构建模块**

```bash
mvn -pl user-profile-service clean package -DskipTests
```

Expected: BUILD SUCCESS

- [ ] **Step 2: 启动服务**

```bash
mvn -pl user-profile-service spring-boot:run &
```

- [ ] **Step 3: 等待服务启动**

```bash
sleep 30
```

- [ ] **Step 4: 检查服务注册到 Nacos**

```bash
curl -s http://localhost:8848/nacos/v1/ns/instance/list?serviceName=user-profile-service | grep -q "user-profile-service"
```

Expected: 命令成功（退出码 0）

- [ ] **Step 5: 检查健康状态**

```bash
curl -s http://localhost:8101/actuator/health | grep -q "UP"
```

Expected: 输出包含 "UP"

- [ ] **Step 6: 停止服务**

```bash
pkill -f user-profile-service
```

---

## 验证清单

完成所有任务后，验证以下内容：

- [ ] user-profile-service 模块结构完整
- [ ] 所有 Entity、DTO、Mapper 类已创建
- [ ] UserProfileService 实现完整
- [ ] RabbitMQ 和 Cache 配置正确
- [ ] 消息监听器实现完整
- [ ] 服务可以成功启动
- [ ] 服务注册到 Nacos
- [ ] 健康检查通过
- [ ] 所有更改已提交到 Git

---

## 下一步

user-profile-service 完成后，继续执行：

**Plan 3: vector-memory-service** - 实现向量记忆服务（最复杂）
