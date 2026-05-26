# AI 记忆功能实施计划 - Plan 4: ai-chat-service (Part 1/2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 ai-chat-service 微服务的基础结构、DTO、配置和 Client

**Architecture:** 协调用户画像、向量记忆和 Dify API，提供完整的 AI 聊天功能

**Tech Stack:** Spring Boot 3.2.5, RestClient, RabbitMQ, CompletableFuture

---

## 文件结构

```
ai-chat-service/
├── pom.xml
├── src/main/java/com/lx/governance/aichat/
│   ├── AiChatServiceApplication.java
│   ├── config/
│   │   ├── RestClientConfig.java
│   │   ├── RabbitMQConfig.java
│   │   └── AsyncConfig.java
│   ├── client/
│   │   ├── UserProfileClient.java
│   │   ├── VectorMemoryClient.java
│   │   └── DifyClient.java
│   ├── service/
│   │   └── AiChatService.java
│   ├── controller/
│   │   └── AiChatController.java
│   └── dto/
│       ├── ChatRequest.java
│       ├── ChatResponse.java
│       ├── DifyRequest.java
│       ├── DifyResponse.java
│       ├── UserProfileDTO.java (复用)
│       └── MemoryItemDTO.java (复用)
└── src/main/resources/
    ├── application.yml
    └── logback-spring.xml
```

---

## Task 1: 创建模块基础结构

**Files:**
- Create: `ai-chat-service/pom.xml`
- Create: `ai-chat-service/src/main/java/com/lx/governance/aichat/AiChatServiceApplication.java`
- Create: `ai-chat-service/src/main/resources/application.yml`

- [ ] **Step 1: 创建模块目录**

```bash
mkdir -p ai-chat-service/src/main/java/com/lx/governance/aichat
mkdir -p ai-chat-service/src/main/resources
mkdir -p ai-chat-service/src/test/java/com/lx/governance/aichat
```

- [ ] **Step 2: 创建 pom.xml**

```bash
cat > ai-chat-service/pom.xml << 'POMEOF'
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

    <artifactId>ai-chat-service</artifactId>
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

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>

        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
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
cat > ai-chat-service/src/main/java/com/lx/governance/aichat/AiChatServiceApplication.java << 'JAVAEOF'
package com.lx.governance.aichat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class AiChatServiceApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(AiChatServiceApplication.class, args);
    }
}
JAVAEOF
```

- [ ] **Step 4: 创建 application.yml**

```bash
cat > ai-chat-service/src/main/resources/application.yml << 'YMLEOF'
server:
  port: 8103

spring:
  application:
    name: ai-chat-service
  
  cloud:
    nacos:
      discovery:
        server-addr: localhost:8848
  
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest

# Dify API 配置
dify:
  api:
    base-url: ${DIFY_API_BASE_URL:https://api.dify.ai/v1}
    key: ${DIFY_API_KEY:your-dify-api-key}
    timeout: 30000

# 超时配置
timeout:
  user-profile: 5000
  vector-memory: 5000
  dify-api: 30000

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
    com.lx.governance.aichat: INFO
YMLEOF
```

- [ ] **Step 5: 验证模块可以编译**

```bash
mvn -pl ai-chat-service clean compile
```

Expected: BUILD SUCCESS

---

## Task 2: 创建 DTO 数据传输对象

**Files:**
- Create: `ai-chat-service/src/main/java/com/lx/governance/aichat/dto/*.java` (6个文件)

- [ ] **Step 1: 创建 dto 目录**

```bash
mkdir -p ai-chat-service/src/main/java/com/lx/governance/aichat/dto
```

- [ ] **Step 2: 创建 ChatRequest**

```bash
cat > ai-chat-service/src/main/java/com/lx/governance/aichat/dto/ChatRequest.java << 'JAVAEOF'
package com.lx.governance.aichat.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ChatRequest {
    
    @NotBlank(message = "群组ID不能为空")
    private String groupId;
    
    @NotBlank(message = "用户ID不能为空")
    private String customerId;
    
    @NotBlank(message = "消息内容不能为空")
    private String message;
    
    private String conversationId;
}
JAVAEOF
```

- [ ] **Step 3: 创建 ChatResponse**

```bash
cat > ai-chat-service/src/main/java/com/lx/governance/aichat/dto/ChatResponse.java << 'JAVAEOF'
package com.lx.governance.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {
    private Boolean success;
    private String message;
    private String conversationId;
    private String errorMessage;
    private Long timestamp;
    
    public static ChatResponse success(String message, String conversationId) {
        return ChatResponse.builder()
            .success(true)
            .message(message)
            .conversationId(conversationId)
            .timestamp(System.currentTimeMillis())
            .build();
    }
    
    public static ChatResponse error(String errorMessage) {
        return ChatResponse.builder()
            .success(false)
            .errorMessage(errorMessage)
            .timestamp(System.currentTimeMillis())
            .build();
    }
}
JAVAEOF
```

- [ ] **Step 4: 创建 DifyRequest**

```bash
cat > ai-chat-service/src/main/java/com/lx/governance/aichat/dto/DifyRequest.java << 'JAVAEOF'
package com.lx.governance.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DifyRequest {
    private String query;
    private String user;
    private String conversationId;
    private Map<String, Object> inputs;
    private Boolean responseMode;
}
JAVAEOF
```

- [ ] **Step 5: 创建 DifyResponse**

```bash
cat > ai-chat-service/src/main/java/com/lx/governance/aichat/dto/DifyResponse.java << 'JAVAEOF'
package com.lx.governance.aichat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class DifyResponse {
    private String answer;
    
    @JsonProperty("conversation_id")
    private String conversationId;
    
    @JsonProperty("message_id")
    private String messageId;
    
    private Metadata metadata;
    
    @Data
    public static class Metadata {
        private String usage;
        private Integer retrieverResources;
    }
}
JAVAEOF
```

- [ ] **Step 6: 创建 UserProfileDTO（复用）**

```bash
cat > ai-chat-service/src/main/java/com/lx/governance/aichat/dto/UserProfileDTO.java << 'JAVAEOF'
package com.lx.governance.aichat.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * 用户画像 DTO（从 user-profile-service 复用）
 */
@Data
public class UserProfileDTO {
    private Map<String, Object> basicInfo;
    private Map<String, Object> healthProfile;
    private Map<String, Object> behaviorPreferences;
    private List<Map<String, Object>> globalMemories;
}
JAVAEOF
```

- [ ] **Step 7: 创建 MemoryItemDTO（复用）**

```bash
cat > ai-chat-service/src/main/java/com/lx/governance/aichat/dto/MemoryItemDTO.java << 'JAVAEOF'
package com.lx.governance.aichat.dto;

import lombok.Data;

/**
 * 记忆项 DTO（从 vector-memory-service 复用）
 */
@Data
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

- [ ] **Step 8: 验证编译**

```bash
mvn -pl ai-chat-service compile
```

Expected: BUILD SUCCESS

---

## Task 3: 配置 RestClient（Dify API）

**Files:**
- Create: `ai-chat-service/src/main/java/com/lx/governance/aichat/config/RestClientConfig.java`

- [ ] **Step 1: 创建 config 目录**

```bash
mkdir -p ai-chat-service/src/main/java/com/lx/governance/aichat/config
```

- [ ] **Step 2: 创建 RestClientConfig**

```bash
cat > ai-chat-service/src/main/java/com/lx/governance/aichat/config/RestClientConfig.java << 'JAVAEOF'
package com.lx.governance.aichat.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.time.Duration;

@Configuration
public class RestClientConfig {
    
    @Value("${dify.api.base-url}")
    private String difyBaseUrl;
    
    @Value("${dify.api.key}")
    private String difyApiKey;
    
    @Value("${dify.api.timeout:30000}")
    private int timeout;
    
    @Bean
    public RestClient difyRestClient() {
        return RestClient.builder()
            .baseUrl(difyBaseUrl)
            .defaultHeader("Authorization", "Bearer " + difyApiKey)
            .defaultHeader("Content-Type", "application/json")
            .requestFactory(clientHttpRequestFactory())
            .build();
    }
    
    private ClientHttpRequestFactory clientHttpRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeout));
        factory.setReadTimeout(Duration.ofMillis(timeout));
        return factory;
    }
}
JAVAEOF
```

- [ ] **Step 3: 验证编译**

```bash
mvn -pl ai-chat-service compile
```

Expected: BUILD SUCCESS

---

## Task 4: 配置 RabbitMQ

**Files:**
- Create: `ai-chat-service/src/main/java/com/lx/governance/aichat/config/RabbitMQConfig.java`

- [ ] **Step 1: 创建 RabbitMQConfig**

```bash
cat > ai-chat-service/src/main/java/com/lx/governance/aichat/config/RabbitMQConfig.java << 'JAVAEOF'
package com.lx.governance.aichat.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {
    
    // 用户画像
    public static final String USER_PROFILE_EXCHANGE = "user.profile.exchange";
    
    // 向量记忆
    public static final String VECTOR_MEMORY_EXCHANGE = "vector.memory.exchange";
    
    // 记忆提取
    public static final String MEMORY_EXTRACTION_EXCHANGE = "memory.extraction.exchange";
    
    @Bean
    public DirectExchange userProfileExchange() {
        return new DirectExchange(USER_PROFILE_EXCHANGE, true, false);
    }
    
    @Bean
    public DirectExchange vectorMemoryExchange() {
        return new DirectExchange(VECTOR_MEMORY_EXCHANGE, true, false);
    }
    
    @Bean
    public DirectExchange memoryExtractionExchange() {
        return new DirectExchange(MEMORY_EXTRACTION_EXCHANGE, true, false);
    }
    
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
JAVAEOF
```

- [ ] **Step 2: 验证编译**

```bash
mvn -pl ai-chat-service compile
```

Expected: BUILD SUCCESS

---

## Task 5: 配置异步

**Files:**
- Create: `ai-chat-service/src/main/java/com/lx/governance/aichat/config/AsyncConfig.java`

- [ ] **Step 1: 创建 AsyncConfig**

```bash
cat > ai-chat-service/src/main/java/com/lx/governance/aichat/config/AsyncConfig.java << 'JAVAEOF'
package com.lx.governance.aichat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {
    
    @Bean(name = "chatExecutor")
    public Executor chatExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("chat-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
JAVAEOF
```

- [ ] **Step 2: 验证编译**

```bash
mvn -pl ai-chat-service compile
```

Expected: BUILD SUCCESS

---

继续 Part 2...
