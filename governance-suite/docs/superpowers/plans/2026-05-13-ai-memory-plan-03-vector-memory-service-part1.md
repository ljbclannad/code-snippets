# AI 记忆功能实施计划 - Plan 3: vector-memory-service (Part 1/4)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 vector-memory-service 微服务的基础结构、Entity 和 DTO

**Architecture:** Spring Boot 3.2.5 + Spring AI 1.0.0-M3 + Milvus + 通义千问 + DeepSeek

**Tech Stack:** Spring Boot 3.2.5, Spring AI, MyBatis, RabbitMQ, Milvus

---

## 文件结构

```
vector-memory-service/
├── pom.xml
├── src/main/java/com/lx/governance/vectormemory/
│   ├── VectorMemoryServiceApplication.java
│   ├── config/
│   │   ├── MilvusVectorStoreConfig.java
│   │   ├── RabbitMQConfig.java
│   │   ├── AsyncConfig.java
│   │   └── RetryConfig.java
│   ├── service/
│   │   ├── EmbeddingService.java
│   │   ├── VectorStoreService.java
│   │   ├── DeepSeekService.java
│   │   ├── MemoryRetrievalService.java
│   │   └── MemoryExtractionService.java
│   ├── listener/
│   │   └── VectorMemoryMessageListener.java
│   ├── mapper/
│   │   └── AiGroupMemoryMapper.java
│   ├── entity/
│   │   └── AiGroupMemoryEntity.java
│   └── dto/
│       ├── MemoryItemDTO.java
│       ├── MemoryExtractionResult.java
│       ├── MemoryRequestMessage.java
│       ├── MemoryResponseMessage.java
│       └── ExtractionRequestMessage.java
└── src/main/resources/
    ├── application.yml
    ├── mapper/
    │   └── AiGroupMemoryMapper.xml
    └── logback-spring.xml
```

---

## Task 1: 创建模块基础结构

**Files:**
- Create: `vector-memory-service/pom.xml`
- Create: `vector-memory-service/src/main/java/com/lx/governance/vectormemory/VectorMemoryServiceApplication.java`
- Create: `vector-memory-service/src/main/resources/application.yml`

- [ ] **Step 1: 创建模块目录**

```bash
mkdir -p vector-memory-service/src/main/java/com/lx/governance/vectormemory
mkdir -p vector-memory-service/src/main/resources/mapper
mkdir -p vector-memory-service/src/test/java/com/lx/governance/vectormemory
```

- [ ] **Step 2: 创建 pom.xml**

```bash
cat > vector-memory-service/pom.xml << 'POMEOF'
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

    <artifactId>vector-memory-service</artifactId>
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
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>

        <!-- Spring AI Core -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-core</artifactId>
        </dependency>

        <!-- Spring AI Milvus -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-milvus-store-spring-boot-starter</artifactId>
        </dependency>

        <!-- Spring AI Alibaba DashScope (通义千问) -->
        <dependency>
            <groupId>com.alibaba.cloud.ai</groupId>
            <artifactId>spring-ai-alibaba-starter</artifactId>
        </dependency>

        <!-- Spring AI OpenAI (用于 DeepSeek 兼容) -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-openai-spring-boot-starter</artifactId>
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

        <!-- Retry -->
        <dependency>
            <groupId>org.springframework.retry</groupId>
            <artifactId>spring-retry</artifactId>
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
cat > vector-memory-service/src/main/java/com/lx/governance/vectormemory/VectorMemoryServiceApplication.java << 'JAVAEOF'
package com.lx.governance.vectormemory;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
@EnableRetry
@MapperScan("com.lx.governance.vectormemory.mapper")
public class VectorMemoryServiceApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(VectorMemoryServiceApplication.class, args);
    }
}
JAVAEOF
```

- [ ] **Step 4: 创建 application.yml**

```bash
cat > vector-memory-service/src/main/resources/application.yml << 'YMLEOF'
server:
  port: 8102

spring:
  application:
    name: vector-memory-service
  
  cloud:
    nacos:
      discovery:
        server-addr: localhost:8848
  
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest
  
  datasource:
    url: jdbc:mysql://localhost:3306/governance?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: root
    password: root
    driver-class-name: com.mysql.cj.jdbc.Driver
  
  # Spring AI - Milvus 配置
  ai:
    vectorstore:
      milvus:
        client:
          host: localhost
          port: 19530
        database-name: default
        embedding-dimension: 1024
        index-type: IVF_FLAT
        metric-type: COSINE
    
    # 通义千问 Embedding
    alibaba:
      dashscope:
        api-key: ${DASHSCOPE_API_KEY:your-dashscope-api-key}
        embedding:
          options:
            model: text-embedding-v2
    
    # DeepSeek (OpenAI 兼容)
    openai:
      api-key: ${DEEPSEEK_API_KEY:your-deepseek-api-key}
      base-url: https://api.deepseek.com/v1
      chat:
        options:
          model: deepseek-chat
          temperature: 0.7
          max-tokens: 2000

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
    com.lx.governance.vectormemory: INFO
    org.springframework.ai: DEBUG
YMLEOF
```

- [ ] **Step 5: 验证模块可以编译**

```bash
mvn -pl vector-memory-service clean compile
```

Expected: BUILD SUCCESS

---

## Task 2: 创建 Entity 实体类

**Files:**
- Create: `vector-memory-service/src/main/java/com/lx/governance/vectormemory/entity/AiGroupMemoryEntity.java`

- [ ] **Step 1: 创建 entity 目录**

```bash
mkdir -p vector-memory-service/src/main/java/com/lx/governance/vectormemory/entity
```

- [ ] **Step 2: 创建 AiGroupMemoryEntity**

```bash
cat > vector-memory-service/src/main/java/com/lx/governance/vectormemory/entity/AiGroupMemoryEntity.java << 'JAVAEOF'
package com.lx.governance.vectormemory.entity;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class AiGroupMemoryEntity {
    private Long id;
    private String groupId;
    private String customerId;
    private String memoryContent;
    private String embeddingId;
    private BigDecimal confidenceScore;
    private String source;
    private String extractedFrom;
    private Integer verified;
    private String verifiedBy;
    private LocalDateTime verifiedAt;
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
JAVAEOF
```

- [ ] **Step 3: 验证编译**

```bash
mvn -pl vector-memory-service compile
```

Expected: BUILD SUCCESS

---

## Task 3: 创建 DTO 数据传输对象

**Files:**
- Create: `vector-memory-service/src/main/java/com/lx/governance/vectormemory/dto/*.java` (5个文件)

- [ ] **Step 1: 创建 dto 目录**

```bash
mkdir -p vector-memory-service/src/main/java/com/lx/governance/vectormemory/dto
```

- [ ] **Step 2: 创建 MemoryItemDTO**

```bash
cat > vector-memory-service/src/main/java/com/lx/governance/vectormemory/dto/MemoryItemDTO.java << 'JAVAEOF'
package com.lx.governance.vectormemory.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryItemDTO {
    private Long id;
    private String content;
    private Double similarity;
    private Double confidenceScore;
    private Boolean verified;
    private String source;
}
JAVAEOF
```

- [ ] **Step 3: 创建 MemoryExtractionResult**

```bash
cat > vector-memory-service/src/main/java/com/lx/governance/vectormemory/dto/MemoryExtractionResult.java << 'JAVAEOF'
package com.lx.governance.vectormemory.dto;

import lombok.Data;
import java.util.List;

@Data
public class MemoryExtractionResult {
    private List<ExtractedMemory> memories;
    
    @Data
    public static class ExtractedMemory {
        private String content;
        private Double confidence;
        private String category;
    }
}
JAVAEOF
```

- [ ] **Step 4: 创建 MemoryRequestMessage**

```bash
cat > vector-memory-service/src/main/java/com/lx/governance/vectormemory/dto/MemoryRequestMessage.java << 'JAVAEOF'
package com.lx.governance.vectormemory.dto;

import lombok.Data;

@Data
public class MemoryRequestMessage {
    private String correlationId;
    private String groupId;
    private String customerId;
    private String query;
    private Integer topK;
    private Long timestamp;
}
JAVAEOF
```

- [ ] **Step 5: 创建 MemoryResponseMessage**

```bash
cat > vector-memory-service/src/main/java/com/lx/governance/vectormemory/dto/MemoryResponseMessage.java << 'JAVAEOF'
package com.lx.governance.vectormemory.dto;

import lombok.Data;
import java.util.List;

@Data
public class MemoryResponseMessage {
    private String correlationId;
    private Boolean success;
    private String errorMessage;
    private List<MemoryItemDTO> data;
    private Long timestamp;
}
JAVAEOF
```

- [ ] **Step 6: 创建 ExtractionRequestMessage**

```bash
cat > vector-memory-service/src/main/java/com/lx/governance/vectormemory/dto/ExtractionRequestMessage.java << 'JAVAEOF'
package com.lx.governance.vectormemory.dto;

import lombok.Data;

@Data
public class ExtractionRequestMessage {
    private String correlationId;
    private String groupId;
    private String customerId;
    private String userMessage;
    private String aiResponse;
    private Long timestamp;
}
JAVAEOF
```

- [ ] **Step 7: 验证编译**

```bash
mvn -pl vector-memory-service compile
```

Expected: BUILD SUCCESS

---

## Task 4: 创建 Mapper 接口和 XML

**Files:**
- Create: `vector-memory-service/src/main/java/com/lx/governance/vectormemory/mapper/AiGroupMemoryMapper.java`
- Create: `vector-memory-service/src/main/resources/mapper/AiGroupMemoryMapper.xml`

- [ ] **Step 1: 创建 mapper 目录**

```bash
mkdir -p vector-memory-service/src/main/java/com/lx/governance/vectormemory/mapper
```

- [ ] **Step 2: 创建 AiGroupMemoryMapper 接口**

```bash
cat > vector-memory-service/src/main/java/com/lx/governance/vectormemory/mapper/AiGroupMemoryMapper.java << 'JAVAEOF'
package com.lx.governance.vectormemory.mapper;

import com.lx.governance.vectormemory.entity.AiGroupMemoryEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface AiGroupMemoryMapper {
    
    void insert(AiGroupMemoryEntity entity);
    
    void updateEmbeddingId(@Param("id") Long id, @Param("embeddingId") String embeddingId);
    
    List<AiGroupMemoryEntity> selectByGroupIdAndCustomerId(
        @Param("groupId") String groupId,
        @Param("customerId") String customerId
    );
    
    AiGroupMemoryEntity selectById(@Param("id") Long id);
    
    List<AiGroupMemoryEntity> selectByEmbeddingIds(@Param("embeddingIds") List<String> embeddingIds);
    
    int countSimilarMemory(
        @Param("groupId") String groupId,
        @Param("customerId") String customerId,
        @Param("content") String content
    );
    
    void deleteById(@Param("id") Long id);
}
JAVAEOF
```

- [ ] **Step 3: 创建 AiGroupMemoryMapper.xml**

```bash
cat > vector-memory-service/src/main/resources/mapper/AiGroupMemoryMapper.xml << 'XMLEOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" 
    "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.lx.governance.vectormemory.mapper.AiGroupMemoryMapper">
    
    <insert id="insert" parameterType="com.lx.governance.vectormemory.entity.AiGroupMemoryEntity"
            useGeneratedKeys="true" keyProperty="id">
        INSERT INTO b_ai_group_memory (
            group_id,
            customer_id,
            memory_content,
            embedding_id,
            confidence_score,
            source,
            extracted_from,
            verified,
            is_deleted,
            create_time,
            update_time
        ) VALUES (
            #{groupId},
            #{customerId},
            #{memoryContent},
            #{embeddingId},
            #{confidenceScore},
            #{source},
            #{extractedFrom},
            #{verified},
            0,
            NOW(),
            NOW()
        )
    </insert>
    
    <update id="updateEmbeddingId">
        UPDATE b_ai_group_memory
        SET embedding_id = #{embeddingId},
            update_time = NOW()
        WHERE id = #{id}
    </update>
    
    <select id="selectByGroupIdAndCustomerId" 
            resultType="com.lx.governance.vectormemory.entity.AiGroupMemoryEntity">
        SELECT 
            id,
            group_id,
            customer_id,
            memory_content,
            embedding_id,
            confidence_score,
            source,
            extracted_from,
            verified,
            verified_by,
            verified_at,
            is_deleted,
            create_time,
            update_time
        FROM b_ai_group_memory
        WHERE group_id = #{groupId}
          AND customer_id = #{customerId}
          AND is_deleted = 0
        ORDER BY create_time DESC
    </select>
    
    <select id="selectById" 
            resultType="com.lx.governance.vectormemory.entity.AiGroupMemoryEntity">
        SELECT 
            id,
            group_id,
            customer_id,
            memory_content,
            embedding_id,
            confidence_score,
            source,
            extracted_from,
            verified,
            verified_by,
            verified_at,
            is_deleted,
            create_time,
            update_time
        FROM b_ai_group_memory
        WHERE id = #{id}
    </select>
    
    <select id="selectByEmbeddingIds" 
            resultType="com.lx.governance.vectormemory.entity.AiGroupMemoryEntity">
        SELECT 
            id,
            group_id,
            customer_id,
            memory_content,
            embedding_id,
            confidence_score,
            source,
            extracted_from,
            verified,
            verified_by,
            verified_at,
            is_deleted,
            create_time,
            update_time
        FROM b_ai_group_memory
        WHERE embedding_id IN
        <foreach collection="embeddingIds" item="embeddingId" open="(" separator="," close=")">
            #{embeddingId}
        </foreach>
        AND is_deleted = 0
    </select>
    
    <select id="countSimilarMemory" resultType="int">
        SELECT COUNT(*)
        FROM b_ai_group_memory
        WHERE group_id = #{groupId}
          AND customer_id = #{customerId}
          AND memory_content = #{content}
          AND is_deleted = 0
    </select>
    
    <update id="deleteById">
        UPDATE b_ai_group_memory
        SET is_deleted = 1,
            update_time = NOW()
        WHERE id = #{id}
    </update>
    
</mapper>
XMLEOF
```

- [ ] **Step 4: 验证编译**

```bash
mvn -pl vector-memory-service compile
```

Expected: BUILD SUCCESS

---

继续 Part 2...
