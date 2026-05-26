# AI 记忆功能实施计划 - Plan 2: user-profile-service (Part 1/2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 user-profile-service 微服务，负责构建用户全局画像并通过 RabbitMQ 响应请求

**Architecture:** 查询 MySQL 多个数据源（Customer、健康评估、全局记忆），聚合为用户画像 JSON，使用 Redis 缓存（5分钟），通过 RabbitMQ 消息队列响应请求

**Tech Stack:** Spring Boot 3.2.5, MyBatis, Redis Cache, RabbitMQ, Nacos

---

## 文件结构

本计划将创建以下文件：

**模块结构**:
```
user-profile-service/
├── pom.xml
├── src/main/java/com/lx/governance/profile/
│   ├── UserProfileServiceApplication.java
│   ├── config/
│   │   ├── RabbitMQConfig.java
│   │   └── CacheConfig.java
│   ├── service/
│   │   └── UserProfileService.java
│   ├── listener/
│   │   └── UserProfileMessageListener.java
│   ├── mapper/
│   │   ├── CustomerMapper.java
│   │   ├── HealthAssessmentMapper.java
│   │   └── AiUserMemoryMapper.java
│   ├── entity/
│   │   ├── CustomerEntity.java
│   │   ├── HealthAssessmentEntity.java
│   │   └── AiUserMemoryEntity.java
│   └── dto/
│       ├── UserProfileDTO.java
│       ├── BasicInfoDTO.java
│       ├── HealthProfileDTO.java
│       ├── BehaviorPreferencesDTO.java
│       ├── GlobalMemoryDTO.java
│       ├── UserProfileRequestMessage.java
│       └── UserProfileResponseMessage.java
└── src/main/resources/
    ├── application.yml
    ├── mapper/
    │   ├── CustomerMapper.xml
    │   ├── HealthAssessmentMapper.xml
    │   └── AiUserMemoryMapper.xml
    └── logback-spring.xml
```

---

## Task 1: 创建模块基础结构

**Files:**
- Create: `user-profile-service/pom.xml`
- Create: `user-profile-service/src/main/java/com/lx/governance/profile/UserProfileServiceApplication.java`
- Create: `user-profile-service/src/main/resources/application.yml`

- [ ] **Step 1: 创建模块目录**

```bash
mkdir -p user-profile-service/src/main/java/com/lx/governance/profile
mkdir -p user-profile-service/src/main/resources/mapper
mkdir -p user-profile-service/src/test/java/com/lx/governance/profile
```

- [ ] **Step 2: 创建 pom.xml**

```bash
cat > user-profile-service/pom.xml << 'POMEOF'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.lx.governance</groupId>
        <artifactId>governance-suite</artifactId>
        <version>1.0.0</version>
    </parent>

    <artifactId>user-profile-service</artifactId>
    <packaging>jar</packaging>

    <dependencies>
        <dependency>
            <groupId>com.lx.governance</groupId>
            <artifactId>common</artifactId>
            <version>${project.version}</version>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-amqp</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-cache</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>

        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
        </dependency>

        <dependency>
            <groupId>org.mybatis.spring.boot</groupId>
            <artifactId>mybatis-spring-boot-starter</artifactId>
        </dependency>

        <dependency>
            <groupId>mysql</groupId>
            <artifactId>mysql-connector-java</artifactId>
            <version>${mysql.version}</version>
            <scope>runtime</scope>
        </dependency>

        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
POMEOF
```

- [ ] **Step 3: 创建 Application 主类**

```bash
cat > user-profile-service/src/main/java/com/lx/governance/profile/UserProfileServiceApplication.java << 'JAVAEOF'
package com.lx.governance.profile;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
@MapperScan("com.lx.governance.profile.mapper")
public class UserProfileServiceApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(UserProfileServiceApplication.class, args);
    }
}
JAVAEOF
```

- [ ] **Step 4: 创建 application.yml**

```bash
cat > user-profile-service/src/main/resources/application.yml << 'YMLEOF'
server:
  port: 8101

spring:
  application:
    name: user-profile-service
  
  cloud:
    nacos:
      discovery:
        server-addr: localhost:8848
  
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest
  
  redis:
    host: localhost
    port: 36379
  
  cache:
    type: redis
    redis:
      time-to-live: 300000  # 5分钟
  
  datasource:
    url: jdbc:mysql://localhost:3306/governance?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: root
    password: root
    driver-class-name: com.mysql.cj.jdbc.Driver

mybatis:
  mapper-locations: classpath:mapper/*.xml
  configuration:
    map-underscore-to-camel-case: true

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: always

logging:
  level:
    com.lx.governance.profile: INFO
YMLEOF
```

- [ ] **Step 5: 验证模块可以编译**

```bash
mvn -pl user-profile-service clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 6: 提交更改**

```bash
git add user-profile-service/
git commit -m "feat(user-profile): create module structure"
```

---

## Task 2: 创建 Entity 实体类

**Files:**
- Create: `user-profile-service/src/main/java/com/lx/governance/profile/entity/CustomerEntity.java`
- Create: `user-profile-service/src/main/java/com/lx/governance/profile/entity/HealthAssessmentEntity.java`
- Create: `user-profile-service/src/main/java/com/lx/governance/profile/entity/AiUserMemoryEntity.java`

- [ ] **Step 1: 创建 entity 目录**

```bash
mkdir -p user-profile-service/src/main/java/com/lx/governance/profile/entity
```

- [ ] **Step 2: 创建 CustomerEntity**

```bash
cat > user-profile-service/src/main/java/com/lx/governance/profile/entity/CustomerEntity.java << 'JAVAEOF'
package com.lx.governance.profile.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class CustomerEntity {
    private String customerId;
    private String name;
    private Integer age;
    private String gender;
    private String phone;
    private String email;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
JAVAEOF
```

- [ ] **Step 3: 创建 HealthAssessmentEntity**

```bash
cat > user-profile-service/src/main/java/com/lx/governance/profile/entity/HealthAssessmentEntity.java << 'JAVAEOF'
package com.lx.governance.profile.entity;

import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class HealthAssessmentEntity {
    private Long id;
    private String customerId;
    private Double bmi;
    private String bloodPressure;
    private Double bloodSugar;
    private LocalDate assessmentDate;
    private String healthGoals;
    private LocalDateTime createTime;
}
JAVAEOF
```

- [ ] **Step 4: 创建 AiUserMemoryEntity**

```bash
cat > user-profile-service/src/main/java/com/lx/governance/profile/entity/AiUserMemoryEntity.java << 'JAVAEOF'
package com.lx.governance.profile.entity;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class AiUserMemoryEntity {
    private Long id;
    private String customerId;
    private String memoryType;
    private String memoryKey;
    private String memoryContent;
    private String embeddingId;
    private BigDecimal confidenceScore;
    private String source;
    private Integer verified;
    private String verifiedBy;
    private LocalDateTime verifiedAt;
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
JAVAEOF
```

- [ ] **Step 5: 验证编译**

```bash
mvn -pl user-profile-service compile
```

Expected: BUILD SUCCESS

- [ ] **Step 6: 提交更改**

```bash
git add user-profile-service/src/main/java/com/lx/governance/profile/entity/
git commit -m "feat(user-profile): add entity classes"
```

---

## Task 3: 创建 DTO 数据传输对象

**Files:**
- Create: `user-profile-service/src/main/java/com/lx/governance/profile/dto/*.java` (7个文件)

- [ ] **Step 1: 创建 dto 目录**

```bash
mkdir -p user-profile-service/src/main/java/com/lx/governance/profile/dto
```

- [ ] **Step 2: 创建 BasicInfoDTO**

```bash
cat > user-profile-service/src/main/java/com/lx/governance/profile/dto/BasicInfoDTO.java << 'JAVAEOF'
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
JAVAEOF
```

- [ ] **Step 3: 创建 HealthProfileDTO**

```bash
cat > user-profile-service/src/main/java/com/lx/governance/profile/dto/HealthProfileDTO.java << 'JAVAEOF'
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
JAVAEOF
```

- [ ] **Step 4: 创建 BehaviorPreferencesDTO**

```bash
cat > user-profile-service/src/main/java/com/lx/governance/profile/dto/BehaviorPreferencesDTO.java << 'JAVAEOF'
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
JAVAEOF
```

- [ ] **Step 5: 创建 GlobalMemoryDTO**

```bash
cat > user-profile-service/src/main/java/com/lx/governance/profile/dto/GlobalMemoryDTO.java << 'JAVAEOF'
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
JAVAEOF
```

- [ ] **Step 6: 创建 UserProfileDTO**

```bash
cat > user-profile-service/src/main/java/com/lx/governance/profile/dto/UserProfileDTO.java << 'JAVAEOF'
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
    
    public static UserProfileDTO empty(String customerId) {
        return UserProfileDTO.builder()
            .basicInfo(BasicInfoDTO.builder().customerId(customerId).build())
            .healthProfile(HealthProfileDTO.empty())
            .behaviorPreferences(BehaviorPreferencesDTO.builder().build())
            .globalMemories(List.of())
            .build();
    }
}
JAVAEOF
```

- [ ] **Step 7: 创建 UserProfileRequestMessage**

```bash
cat > user-profile-service/src/main/java/com/lx/governance/profile/dto/UserProfileRequestMessage.java << 'JAVAEOF'
package com.lx.governance.profile.dto;

import lombok.Data;

@Data
public class UserProfileRequestMessage {
    private String correlationId;
    private String customerId;
    private Long timestamp;
}
JAVAEOF
```

- [ ] **Step 8: 创建 UserProfileResponseMessage**

```bash
cat > user-profile-service/src/main/java/com/lx/governance/profile/dto/UserProfileResponseMessage.java << 'JAVAEOF'
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
JAVAEOF
```

- [ ] **Step 9: 验证编译**

```bash
mvn -pl user-profile-service compile
```

Expected: BUILD SUCCESS

- [ ] **Step 10: 提交更改**

```bash
git add user-profile-service/src/main/java/com/lx/governance/profile/dto/
git commit -m "feat(user-profile): add DTO classes"
```

---

继续 Part 2...
