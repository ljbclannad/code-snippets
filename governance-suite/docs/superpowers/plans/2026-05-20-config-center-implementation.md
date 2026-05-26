# 配置中心实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 6 个核心微服务集成 Nacos Config 配置中心，实现动态配置热更新、敏感配置加密和配置变更监听

**Architecture:** 采用分层共享配置模式（common.yml + 服务配置），使用 Nacos 2.x 原生加密保护敏感配置，通过 @RefreshScope 实现业务配置热更新，使用 Spring Event 监听配置变更并执行相应逻辑

**Tech Stack:** Spring Cloud Alibaba Nacos Config 2022.0.0.0、Spring Boot 3.2.5、Nacos Server 2.5.1

---

## 文件结构规划

### 新增文件

**Common 模块**:
- `common/src/main/java/com/lx/governance/common/config/listener/ConfigChangeListener.java` - 通用配置变更监听器

**Gateway**:
- `gateway/src/main/resources/bootstrap.yml` - 引导配置
- `gateway/src/main/java/com/lx/governance/gateway/config/properties/RateLimitProperties.java` - 限流配置类
- `gateway/src/main/java/com/lx/governance/gateway/config/listener/RateLimitConfigChangeListener.java` - 限流配置监听器

**Order Service**:
- `order-service/src/main/resources/bootstrap.yml` - 引导配置
- `order-service/src/main/java/com/lx/governance/order/config/properties/BusinessProperties.java` - 业务配置类
- `order-service/src/main/java/com/lx/governance/order/config/properties/ThreadPoolProperties.java` - 线程池配置类
- `order-service/src/main/java/com/lx/governance/order/config/listener/BusinessConfigChangeListener.java` - 业务配置监听器
- `order-service/src/main/java/com/lx/governance/order/config/listener/ThreadPoolConfigChangeListener.java` - 线程池配置监听器

**Payment Service**:
- `payment-service/src/main/resources/bootstrap.yml` - 引导配置

**Inventory Service**:
- `inventory-service/src/main/resources/bootstrap.yml` - 引导配置

**User Service**:
- `user-service/src/main/resources/bootstrap.yml` - 引导配置

**Outbox Consumer**:
- `outbox-consumer/src/main/resources/bootstrap.yml` - 引导配置
- `outbox-consumer/src/main/java/com/lx/governance/outbox/config/properties/OutboxProperties.java` - Outbox 配置类
- `outbox-consumer/src/main/java/com/lx/governance/outbox/config/listener/OutboxConfigChangeListener.java` - Outbox 配置监听器

### 修改文件

**所有服务的 pom.xml**:
- 添加 Nacos Config 依赖

**docker-compose.yml**:
- 添加 Nacos 加密配置

---

## 实现任务

### Task 1: 配置 Nacos Server 加密功能

**Files:**
- Modify: `docker-compose.yml:42-63`

- [ ] **Step 1: 修改 docker-compose.yml 添加加密配置**

在 nacos 服务的 environment 部分添加加密配置：

```yaml
nacos:
  image: nacos/nacos-server:v2.5.1
  pull_policy: never
  container_name: gov-nacos
  restart: unless-stopped
  environment:
    MODE: standalone
    PREFER_HOST_MODE: hostname
    TZ: Asia/Shanghai
    SPRING_DATASOURCE_PLATFORM: mysql
    MYSQL_SERVICE_HOST: mysql
    MYSQL_SERVICE_PORT: 3306
    MYSQL_SERVICE_DB_NAME: nacos
    MYSQL_SERVICE_USER: root
    MYSQL_SERVICE_PASSWORD: root
    MYSQL_SERVICE_DB_PARAM: characterEncoding=utf8&connectTimeout=1000&socketTimeout=3000&autoReconnect=true&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
    # 启用配置加密
    NACOS_CONFIG_ENCRYPTION_ENABLED: true
    NACOS_CONFIG_ENCRYPTION_KEY: ${NACOS_ENCRYPTION_KEY:-governance-suite-encryption-key-2024}
  ports:
    - "8848:8848"
    - "9848:9848"
    - "9849:9849"
  depends_on:
    - mysql
```

- [ ] **Step 2: 重启 Nacos 服务**

```bash
docker compose restart nacos
```

Expected: Nacos 容器重启成功

- [ ] **Step 3: 验证 Nacos 可访问**

```bash
curl http://localhost:8848/nacos
```

Expected: 返回 Nacos 控制台页面 HTML

- [ ] **Step 4: 提交变更**

```bash
git add docker-compose.yml
git commit -m "feat(nacos): enable config encryption support"
```

---

### Task 2: 添加 Common 模块依赖和通用监听器

**Files:**
- Modify: `common/pom.xml`
- Create: `common/src/main/java/com/lx/governance/common/config/listener/ConfigChangeListener.java`

- [ ] **Step 1: 添加 Nacos Config 依赖到 common/pom.xml**

在 `<dependencies>` 部分添加：

```xml
<!-- Nacos Config -->
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
</dependency>

<!-- Bootstrap 支持 -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-bootstrap</artifactId>
</dependency>
```

- [ ] **Step 2: 创建通用配置变更监听器**

```java
package com.lx.governance.common.config.listener;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.context.scope.refresh.RefreshScopeRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@Slf4j
public class ConfigChangeListener {
    
    @EventListener
    public void onRefresh(RefreshScopeRefreshedEvent event) {
        log.info("配置刷新事件触发: scope={}", event.getName());
        auditConfigChange(event);
    }
    
    private void auditConfigChange(RefreshScopeRefreshedEvent event) {
        log.info("配置变更审计: time={}, scope={}", 
            LocalDateTime.now(), event.getName());
    }
}
```

- [ ] **Step 3: 编译 common 模块验证**

```bash
mvn clean compile -pl common -DskipTests
```

Expected: BUILD SUCCESS

- [ ] **Step 4: 提交变更**

```bash
git add common/pom.xml common/src/main/java/com/lx/governance/common/config/listener/ConfigChangeListener.java
git commit -m "feat(common): add nacos config support and config change listener"
```

---

### Task 3: Gateway 集成 Nacos Config

**Files:**
- Modify: `gateway/pom.xml`
- Create: `gateway/src/main/resources/bootstrap.yml`
- Create: `gateway/src/main/java/com/lx/governance/gateway/config/properties/RateLimitProperties.java`
- Create: `gateway/src/main/java/com/lx/governance/gateway/config/listener/RateLimitConfigChangeListener.java`

- [ ] **Step 1: 添加依赖到 gateway/pom.xml**

在 `<dependencies>` 部分添加：

```xml
<!-- Nacos Config -->
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
</dependency>

<!-- Bootstrap 支持 -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-bootstrap</artifactId>
</dependency>
```

- [ ] **Step 2: 创建 bootstrap.yml**

```yaml
spring:
  application:
    name: gateway
  cloud:
    nacos:
      config:
        server-addr: ${NACOS_ADDR:localhost:8848}
        namespace: ${NACOS_NAMESPACE:}
        group: DEFAULT_GROUP
        file-extension: yml
        refresh-enabled: true
        
        # 共享配置
        shared-configs:
          - data-id: common.yml
            group: DEFAULT_GROUP
            refresh: true
```

- [ ] **Step 3: 创建限流配置属性类**

```java
package com.lx.governance.gateway.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

@Component
@RefreshScope
@ConfigurationProperties(prefix = "gateway.rate-limit")
@Data
public class RateLimitProperties {
    private int replenishRate = 5;
    private int burstCapacity = 10;
}
```

- [ ] **Step 4: 创建限流配置变更监听器**

```java
package com.lx.governance.gateway.config.listener;

import com.lx.governance.gateway.config.properties.RateLimitProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.context.scope.refresh.RefreshScopeRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class RateLimitConfigChangeListener {
    
    private final RateLimitProperties rateLimitProperties;
    
    public RateLimitConfigChangeListener(RateLimitProperties rateLimitProperties) {
        this.rateLimitProperties = rateLimitProperties;
    }
    
    @EventListener
    public void onRefresh(RefreshScopeRefreshedEvent event) {
        log.info("限流配置已更新: replenishRate={}, burstCapacity={}", 
            rateLimitProperties.getReplenishRate(),
            rateLimitProperties.getBurstCapacity());
        
        notifyMonitoring("rate-limit-config-changed", rateLimitProperties);
    }
    
    private void notifyMonitoring(String eventName, Object data) {
        log.info("监控通知: event={}, data={}", eventName, data);
    }
}
```

- [ ] **Step 5: 编译 gateway 模块验证**

```bash
mvn clean compile -pl gateway -DskipTests
```

Expected: BUILD SUCCESS

- [ ] **Step 6: 提交变更**

```bash
git add gateway/pom.xml gateway/src/main/resources/bootstrap.yml gateway/src/main/java/com/lx/governance/gateway/config/
git commit -m "feat(gateway): integrate nacos config with rate limit properties"
```

---

### Task 4: Order Service 集成 Nacos Config

**Files:**
- Modify: `order-service/pom.xml`
- Create: `order-service/src/main/resources/bootstrap.yml`
- Create: `order-service/src/main/java/com/lx/governance/order/config/properties/BusinessProperties.java`
- Create: `order-service/src/main/java/com/lx/governance/order/config/properties/ThreadPoolProperties.java`
- Create: `order-service/src/main/java/com/lx/governance/order/config/listener/BusinessConfigChangeListener.java`
- Create: `order-service/src/main/java/com/lx/governance/order/config/listener/ThreadPoolConfigChangeListener.java`

- [ ] **Step 1: 添加依赖到 order-service/pom.xml**

```xml
<!-- Nacos Config -->
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
</dependency>

<!-- Bootstrap 支持 -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-bootstrap</artifactId>
</dependency>
```

- [ ] **Step 2: 创建 bootstrap.yml**

```yaml
spring:
  application:
    name: order-service
  cloud:
    nacos:
      config:
        server-addr: ${NACOS_ADDR:localhost:8848}
        namespace: ${NACOS_NAMESPACE:}
        group: DEFAULT_GROUP
        file-extension: yml
        refresh-enabled: true
        
        shared-configs:
          - data-id: common.yml
            group: DEFAULT_GROUP
            refresh: true
```

- [ ] **Step 3: 创建业务配置属性类**

```java
package com.lx.governance.order.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

@Component
@RefreshScope
@ConfigurationProperties(prefix = "app.business")
@Data
public class BusinessProperties {
    private boolean seckillEnabled = true;
    private int orderTimeoutSeconds = 1800;
}
```

- [ ] **Step 4: 创建线程池配置属性类**

```java
package com.lx.governance.order.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

@Component
@RefreshScope
@ConfigurationProperties(prefix = "app.thread-pool.io")
@Data
public class ThreadPoolProperties {
    private int coreSize = 32;
    private int maxSize = 64;
    private int queueCapacity = 2000;
    private int keepAliveSeconds = 60;
}
```

- [ ] **Step 5: 创建业务配置变更监听器**

```java
package com.lx.governance.order.config.listener;

import com.lx.governance.order.config.properties.BusinessProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.context.scope.refresh.RefreshScopeRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@Slf4j
public class BusinessConfigChangeListener {
    
    private final BusinessProperties businessProperties;
    private final StringRedisTemplate redisTemplate;
    
    public BusinessConfigChangeListener(
            BusinessProperties businessProperties,
            StringRedisTemplate redisTemplate) {
        this.businessProperties = businessProperties;
        this.redisTemplate = redisTemplate;
    }
    
    @EventListener
    public void onRefresh(RefreshScopeRefreshedEvent event) {
        boolean seckillEnabled = businessProperties.isSeckillEnabled();
        log.warn("秒杀开关状态变更: enabled={}", seckillEnabled);
        
        if (!seckillEnabled) {
            clearSeckillCache();
        }
    }
    
    private void clearSeckillCache() {
        Set<String> keys = redisTemplate.keys("seckill:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
            log.info("已清理秒杀缓存: count={}", keys.size());
        }
    }
}
```

- [ ] **Step 6: 创建线程池配置变更监听器**

```java
package com.lx.governance.order.config.listener;

import com.lx.governance.order.config.properties.ThreadPoolProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.context.scope.refresh.RefreshScopeRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class ThreadPoolConfigChangeListener {
    
    private final ThreadPoolProperties threadPoolProperties;
    private final ThreadPoolTaskExecutor ioExecutor;
    
    public ThreadPoolConfigChangeListener(
            ThreadPoolProperties threadPoolProperties,
            @Qualifier("ioExecutor") ThreadPoolTaskExecutor ioExecutor) {
        this.threadPoolProperties = threadPoolProperties;
        this.ioExecutor = ioExecutor;
    }
    
    @EventListener
    public void onRefresh(RefreshScopeRefreshedEvent event) {
        int newCoreSize = threadPoolProperties.getCoreSize();
        int newMaxSize = threadPoolProperties.getMaxSize();
        
        log.info("线程池配置变更: coreSize={}, maxSize={}", newCoreSize, newMaxSize);
        
        ioExecutor.setCorePoolSize(newCoreSize);
        ioExecutor.setMaxPoolSize(newMaxSize);
        
        log.info("线程池已调整: active={}, poolSize={}, queueSize={}", 
            ioExecutor.getActiveCount(),
            ioExecutor.getPoolSize(),
            ioExecutor.getThreadPoolExecutor().getQueue().size());
    }
}
```

- [ ] **Step 7: 编译 order-service 模块验证**

```bash
mvn clean compile -pl order-service -DskipTests
```

Expected: BUILD SUCCESS

- [ ] **Step 8: 提交变更**

```bash
git add order-service/pom.xml order-service/src/main/resources/bootstrap.yml order-service/src/main/java/com/lx/governance/order/config/
git commit -m "feat(order): integrate nacos config with business and thread pool properties"
```

---

### Task 5: Payment Service 集成 Nacos Config

**Files:**
- Modify: `payment-service/pom.xml`
- Create: `payment-service/src/main/resources/bootstrap.yml`

- [ ] **Step 1: 添加依赖到 payment-service/pom.xml**

```xml
<!-- Nacos Config -->
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
</dependency>

<!-- Bootstrap 支持 -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-bootstrap</artifactId>
</dependency>
```

- [ ] **Step 2: 创建 bootstrap.yml**

```yaml
spring:
  application:
    name: payment-service
  cloud:
    nacos:
      config:
        server-addr: ${NACOS_ADDR:localhost:8848}
        namespace: ${NACOS_NAMESPACE:}
        group: DEFAULT_GROUP
        file-extension: yml
        refresh-enabled: true
        
        shared-configs:
          - data-id: common.yml
            group: DEFAULT_GROUP
            refresh: true
```

- [ ] **Step 3: 编译验证**

```bash
mvn clean compile -pl payment-service -DskipTests
```

Expected: BUILD SUCCESS

- [ ] **Step 4: 提交变更**

```bash
git add payment-service/pom.xml payment-service/src/main/resources/bootstrap.yml
git commit -m "feat(payment): integrate nacos config"
```

---

### Task 6: Inventory Service 集成 Nacos Config

**Files:**
- Modify: `inventory-service/pom.xml`
- Create: `inventory-service/src/main/resources/bootstrap.yml`

- [ ] **Step 1: 添加依赖到 inventory-service/pom.xml**

```xml
<!-- Nacos Config -->
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
</dependency>

<!-- Bootstrap 支持 -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-bootstrap</artifactId>
</dependency>
```

- [ ] **Step 2: 创建 bootstrap.yml**

```yaml
spring:
  application:
    name: inventory-service
  cloud:
    nacos:
      config:
        server-addr: ${NACOS_ADDR:localhost:8848}
        namespace: ${NACOS_NAMESPACE:}
        group: DEFAULT_GROUP
        file-extension: yml
        refresh-enabled: true
        
        shared-configs:
          - data-id: common.yml
            group: DEFAULT_GROUP
            refresh: true
```

- [ ] **Step 3: 编译验证**

```bash
mvn clean compile -pl inventory-service -DskipTests
```

Expected: BUILD SUCCESS

- [ ] **Step 4: 提交变更**

```bash
git add inventory-service/pom.xml inventory-service/src/main/resources/bootstrap.yml
git commit -m "feat(inventory): integrate nacos config"
```

---

### Task 7: User Service 集成 Nacos Config

**Files:**
- Modify: `user-service/pom.xml`
- Create: `user-service/src/main/resources/bootstrap.yml`

- [ ] **Step 1: 添加依赖到 user-service/pom.xml**

```xml
<!-- Nacos Config -->
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
</dependency>

<!-- Bootstrap 支持 -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-bootstrap</artifactId>
</dependency>
```

- [ ] **Step 2: 创建 bootstrap.yml**

```yaml
spring:
  application:
    name: user-service
  cloud:
    nacos:
      config:
        server-addr: ${NACOS_ADDR:localhost:8848}
        namespace: ${NACOS_NAMESPACE:}
        group: DEFAULT_GROUP
        file-extension: yml
        refresh-enabled: true
        
        shared-configs:
          - data-id: common.yml
            group: DEFAULT_GROUP
            refresh: true
```

- [ ] **Step 3: 编译验证**

```bash
mvn clean compile -pl user-service -DskipTests
```

Expected: BUILD SUCCESS

- [ ] **Step 4: 提交变更**

```bash
git add user-service/pom.xml user-service/src/main/resources/bootstrap.yml
git commit -m "feat(user): integrate nacos config"
```

---

### Task 8: Outbox Consumer 集成 Nacos Config

**Files:**
- Modify: `outbox-consumer/pom.xml`
- Create: `outbox-consumer/src/main/resources/bootstrap.yml`
- Create: `outbox-consumer/src/main/java/com/lx/governance/outbox/config/properties/OutboxProperties.java`
- Create: `outbox-consumer/src/main/java/com/lx/governance/outbox/config/listener/OutboxConfigChangeListener.java`

- [ ] **Step 1: 添加依赖到 outbox-consumer/pom.xml**

```xml
<!-- Nacos Config -->
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
</dependency>

<!-- Bootstrap 支持 -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-bootstrap</artifactId>
</dependency>
```

- [ ] **Step 2: 创建 bootstrap.yml**

```yaml
spring:
  application:
    name: outbox-consumer
  cloud:
    nacos:
      config:
        server-addr: ${NACOS_ADDR:localhost:8848}
        namespace: ${NACOS_NAMESPACE:}
        group: DEFAULT_GROUP
        file-extension: yml
        refresh-enabled: true
        
        shared-configs:
          - data-id: common.yml
            group: DEFAULT_GROUP
            refresh: true
```

- [ ] **Step 3: 创建 Outbox 配置属性类**

```java
package com.lx.governance.outbox.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

@Component
@RefreshScope
@ConfigurationProperties(prefix = "outbox")
@Data
public class OutboxProperties {
    private long pollIntervalMs = 1000;
    private int maxRetryCount = 5;
    private long retryBackoffMs = 2000;
}
```

- [ ] **Step 4: 创建 Outbox 配置变更监听器**

```java
package com.lx.governance.outbox.config.listener;

import com.lx.governance.outbox.config.properties.OutboxProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.context.scope.refresh.RefreshScopeRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class OutboxConfigChangeListener {
    
    private final OutboxProperties outboxProperties;
    
    public OutboxConfigChangeListener(OutboxProperties outboxProperties) {
        this.outboxProperties = outboxProperties;
    }
    
    @EventListener
    public void onRefresh(RefreshScopeRefreshedEvent event) {
        long newInterval = outboxProperties.getPollIntervalMs();
        int newMaxRetry = outboxProperties.getMaxRetryCount();
        
        log.info("Outbox 配置变更: pollIntervalMs={}, maxRetryCount={}", 
            newInterval, newMaxRetry);
        
        log.info("注意: 轮询间隔变更需要重启服务才能生效（定时任务已启动）");
    }
}
```

- [ ] **Step 5: 编译验证**

```bash
mvn clean compile -pl outbox-consumer -DskipTests
```

Expected: BUILD SUCCESS

- [ ] **Step 6: 提交变更**

```bash
git add outbox-consumer/pom.xml outbox-consumer/src/main/resources/bootstrap.yml outbox-consumer/src/main/java/com/lx/governance/outbox/config/
git commit -m "feat(outbox): integrate nacos config with outbox properties"
```

---

### Task 9: 在 Nacos 控制台创建配置文件

**Files:**
- None (手动操作)

- [ ] **Step 1: 访问 Nacos 控制台**

打开浏览器访问: http://localhost:8848/nacos

登录账号: nacos / nacos

- [ ] **Step 2: 创建 common.yml 共享配置**

点击"配置管理" -> "配置列表" -> "+" 按钮

- Data ID: `common.yml`
- Group: `DEFAULT_GROUP`
- 配置格式: `YAML`
- 配置内容:

```yaml
# MySQL 配置
mysql:
  host: ${MYSQL_HOST:localhost}
  port: ${MYSQL_PORT:3306}
  username: ${MYSQL_USER:root}
  password: cipher(root)

# Redis 配置
redis:
  host: ${REDIS_HOST:localhost}
  port: ${REDIS_PORT:36379}

# RabbitMQ 配置
rabbitmq:
  host: ${RABBIT_HOST:localhost}
  port: ${RABBIT_PORT:5672}
  username: ${RABBIT_USER:guest}
  password: cipher(guest)

# Nacos 注册中心
spring:
  cloud:
    nacos:
      discovery:
        server-addr: ${NACOS_ADDR:localhost:8848}
        register-enabled: true

# 日志配置
logging:
  level:
    root: INFO
    com.lx.governance: DEBUG
```

点击"发布"

- [ ] **Step 3: 创建 gateway.yml 配置**

- Data ID: `gateway.yml`
- Group: `DEFAULT_GROUP`
- 配置格式: `YAML`
- 配置内容:

```yaml
server:
  port: 8080

spring:
  application:
    name: gateway
  data:
    redis:
      host: ${redis.host}
      port: ${redis.port}

# 动态配置（支持热更新）
gateway:
  rate-limit:
    replenish-rate: 5
    burst-capacity: 10
  block-ip: 10.10.10.10
  sentinel:
    order-list:
      qps: 2

# 认证配置
app:
  auth:
    token-secret: cipher(governance-suite-dev-secret)
    token-ttl-seconds: 7200
```

点击"发布"

- [ ] **Step 4: 创建 order-service.yml 配置**

- Data ID: `order-service.yml`
- Group: `DEFAULT_GROUP`
- 配置格式: `YAML`
- 配置内容:

```yaml
server:
  port: 8081

spring:
  application:
    name: order-service
  data:
    redis:
      host: ${redis.host}
      port: ${redis.port}
  rabbitmq:
    host: ${rabbitmq.host}
    port: ${rabbitmq.port}
    username: ${rabbitmq.username}
    password: ${rabbitmq.password}

# 动态配置（支持热更新）
app:
  thread-pool:
    io:
      core-size: 32
      max-size: 64
      queue-capacity: 2000
      keep-alive-seconds: 60
  business:
    seckill-enabled: true
    order-timeout-seconds: 1800
```

点击"发布"

- [ ] **Step 5: 创建 payment-service.yml 配置**

- Data ID: `payment-service.yml`
- Group: `DEFAULT_GROUP`
- 配置格式: `YAML`
- 配置内容:

```yaml
server:
  port: 8082

spring:
  application:
    name: payment-service
  rabbitmq:
    host: ${rabbitmq.host}
    port: ${rabbitmq.port}
    username: ${rabbitmq.username}
    password: ${rabbitmq.password}
```

点击"发布"

- [ ] **Step 6: 创建 inventory-service.yml 配置**

- Data ID: `inventory-service.yml`
- Group: `DEFAULT_GROUP`
- 配置格式: `YAML`
- 配置内容:

```yaml
server:
  port: 8083

spring:
  application:
    name: inventory-service
```

点击"发布"

- [ ] **Step 7: 创建 user-service.yml 配置**

- Data ID: `user-service.yml`
- Group: `DEFAULT_GROUP`
- 配置格式: `YAML`
- 配置内容:

```yaml
server:
  port: 8084

spring:
  application:
    name: user-service

app:
  auth:
    token-secret: ${app.auth.token-secret}
    token-ttl-seconds: ${app.auth.token-ttl-seconds}
```

点击"发布"

- [ ] **Step 8: 创建 outbox-consumer.yml 配置**

- Data ID: `outbox-consumer.yml`
- Group: `DEFAULT_GROUP`
- 配置格式: `YAML`
- 配置内容:

```yaml
server:
  port: 8090

spring:
  application:
    name: outbox-consumer
  rabbitmq:
    host: ${rabbitmq.host}
    port: ${rabbitmq.port}
    username: ${rabbitmq.username}
    password: ${rabbitmq.password}

# 动态配置（支持热更新）
outbox:
  poll-interval-ms: 1000
  max-retry-count: 5
  retry-backoff-ms: 2000
```

点击"发布"

- [ ] **Step 9: 验证所有配置已创建**

在"配置列表"页面，确认以下配置都已存在：
- common.yml
- gateway.yml
- order-service.yml
- payment-service.yml
- inventory-service.yml
- user-service.yml
- outbox-consumer.yml

Expected: 7 个配置文件都显示在列表中

---

### Task 10: 集成测试 - User Service

**Files:**
- None (测试验证)

- [ ] **Step 1: 启动基础设施**

```bash
docker compose up -d
```

Expected: MySQL, Redis, RabbitMQ, Nacos 都启动成功

- [ ] **Step 2: 等待 Nacos 就绪**

```bash
sleep 10
curl http://localhost:8848/nacos/v1/console/health/readiness
```

Expected: 返回 "ok"

- [ ] **Step 3: 启动 user-service**

```bash
mvn -q -pl user-service spring-boot:run
```

Expected: 服务启动成功，日志中显示：
- "Located property source: [BootstrapPropertySource {name='bootstrap'}]"
- "Located property source: CompositePropertySource {name='NACOS'}"
- "配置刷新事件触发" (来自 ConfigChangeListener)

- [ ] **Step 4: 验证配置加载**

```bash
curl http://localhost:8084/actuator/env | jq '.propertySources[] | select(.name | contains("NACOS"))'
```

Expected: 返回包含 common.yml 和 user-service.yml 的配置源

- [ ] **Step 5: 测试配置热更新**

在 Nacos 控制台修改 user-service.yml，将 `app.auth.token-ttl-seconds` 从 7200 改为 3600，点击"发布"

观察 user-service 日志，应该看到：
```
配置刷新事件触发: scope=refreshScope
配置变更审计: time=2026-05-20T..., scope=refreshScope
```

- [ ] **Step 6: 验证新配置生效**

```bash
curl http://localhost:8084/actuator/env/app.auth.token-ttl-seconds
```

Expected: 返回 3600

- [ ] **Step 7: 停止 user-service**

按 Ctrl+C 停止服务

---

### Task 11: 集成测试 - Gateway 和 Order Service

**Files:**
- None (测试验证)

- [ ] **Step 1: 启动 gateway**

```bash
mvn -q -pl gateway spring-boot:run
```

Expected: 服务启动成功，加载 common.yml 和 gateway.yml

- [ ] **Step 2: 测试 Gateway 限流配置热更新**

在 Nacos 控制台修改 gateway.yml，将 `gateway.rate-limit.replenish-rate` 从 5 改为 10

观察 gateway 日志：
```
限流配置已更新: replenishRate=10, burstCapacity=10
监控通知: event=rate-limit-config-changed, data=...
```

- [ ] **Step 3: 启动 order-service**

在新终端：
```bash
mvn -q -pl order-service spring-boot:run
```

Expected: 服务启动成功

- [ ] **Step 4: 测试 Order Service 业务开关热更新**

在 Nacos 控制台修改 order-service.yml，将 `app.business.seckill-enabled` 从 true 改为 false

观察 order-service 日志：
```
秒杀开关状态变更: enabled=false
已清理秒杀缓存: count=0
```

- [ ] **Step 5: 测试线程池配置热更新**

修改 order-service.yml，将 `app.thread-pool.io.core-size` 从 32 改为 40

观察日志：
```
线程池配置变更: coreSize=40, maxSize=64
线程池已调整: active=0, poolSize=..., queueSize=0
```

- [ ] **Step 6: 停止所有服务**

按 Ctrl+C 停止 gateway 和 order-service

---

### Task 12: 更新文档

**Files:**
- Modify: `README.md`

- [ ] **Step 1: 在 README.md 添加配置中心说明**

在"快速开始"章节后添加新章节：

```markdown
## 配置中心

### 配置管理

本项目使用 Nacos Config 作为配置中心，支持动态配置热更新和敏感配置加密。

**配置文件组织**：
- `common.yml` - 共享配置（MySQL、Redis、RabbitMQ）
- `{service-name}.yml` - 服务特有配置

**访问 Nacos 控制台**：
- URL: http://localhost:8848/nacos
- 账号: nacos / nacos

### 动态配置热更新

以下配置支持热更新（无需重启服务）：

| 服务 | 配置项 | 说明 |
|------|--------|------|
| gateway | `gateway.rate-limit.replenish-rate` | 限流令牌补充速率 |
| gateway | `gateway.rate-limit.burst-capacity` | 限流突发容量 |
| gateway | `gateway.block-ip` | 黑名单 IP |
| order-service | `app.business.seckill-enabled` | 秒杀开关 |
| order-service | `app.thread-pool.io.core-size` | IO 线程池核心线程数 |
| outbox-consumer | `outbox.poll-interval-ms` | 轮询间隔（需重启生效）|

**修改配置**：
1. 登录 Nacos 控制台
2. 进入"配置管理" -> "配置列表"
3. 找到对应配置文件，点击"编辑"
4. 修改配置值，点击"发布"
5. 观察服务日志，确认配置已刷新

### 敏感配置加密

数据库密码、Token 密钥等敏感配置使用 Nacos 原生加密功能：

```yaml
# 在 Nacos 配置中使用 cipher() 标记
mysql:
  password: cipher(root)
```

加密密钥配置在 `docker-compose.yml` 的 `NACOS_CONFIG_ENCRYPTION_KEY` 环境变量中。

### 配置回滚

Nacos 支持配置历史版本管理：
1. 在配置详情页点击"历史版本"
2. 选择要回滚的版本
3. 点击"回滚"
4. 服务自动加载回滚后的配置

### 多环境配置

通过 Namespace 隔离不同环境：
- `public` - 开发环境（默认）
- `test` - 测试环境
- `prod` - 生产环境

启动服务时指定 Namespace：
```bash
mvn spring-boot:run -Dspring.cloud.nacos.config.namespace=prod
```
```

- [ ] **Step 2: 验证 README 格式**

```bash
cat README.md | grep -A 5 "## 配置中心"
```

Expected: 显示新添加的配置中心章节

- [ ] **Step 3: 提交文档更新**

```bash
git add README.md
git commit -m "docs: add nacos config center usage guide"
```

---

### Task 13: 验收测试

**Files:**
- None (验收测试)

- [ ] **Step 1: 启动所有服务**

```bash
docker compose up -d
sleep 10

# 启动所有微服务
mvn -q -pl gateway spring-boot:run &
mvn -q -pl order-service spring-boot:run &
mvn -q -pl payment-service spring-boot:run &
mvn -q -pl inventory-service spring-boot:run &
mvn -q -pl user-service spring-boot:run &
mvn -q -pl outbox-consumer spring-boot:run &

sleep 30
```

- [ ] **Step 2: 验证所有服务已注册到 Nacos**

```bash
curl -s "http://localhost:8848/nacos/v1/ns/instance/list?serviceName=gateway" | jq '.hosts | length'
curl -s "http://localhost:8848/nacos/v1/ns/instance/list?serviceName=order-service" | jq '.hosts | length'
curl -s "http://localhost:8848/nacos/v1/ns/instance/list?serviceName=payment-service" | jq '.hosts | length'
curl -s "http://localhost:8848/nacos/v1/ns/instance/list?serviceName=inventory-service" | jq '.hosts | length'
curl -s "http://localhost:8848/nacos/v1/ns/instance/list?serviceName=user-service" | jq '.hosts | length'
curl -s "http://localhost:8848/nacos/v1/ns/instance/list?serviceName=outbox-consumer" | jq '.hosts | length'
```

Expected: 每个服务都返回 1（表示有 1 个实例注册）

- [ ] **Step 3: 验证配置加载**

检查每个服务的日志，确认都包含：
```
Located property source: CompositePropertySource {name='NACOS'}
```

- [ ] **Step 4: 验收标准 1 - 配置热更新**

修改 gateway.yml 的 `gateway.rate-limit.replenish-rate` 为 20

Expected: gateway 日志显示 "限流配置已更新: replenishRate=20"

- [ ] **Step 5: 验收标准 2 - 敏感配置加密**

在 Nacos 控制台查看 common.yml，确认：
- `mysql.password` 显示为加密后的密文（不是 "root"）
- `rabbitmq.password` 显示为加密后的密文

在服务日志中确认：
- 服务能正常连接 MySQL 和 RabbitMQ（说明解密成功）

- [ ] **Step 6: 验收标准 3 - 配置分层共享**

验证 order-service 同时加载了 common.yml 和 order-service.yml：

```bash
curl -s http://localhost:8081/actuator/env | jq '.propertySources[] | select(.name | contains("NACOS")) | .name'
```

Expected: 返回包含 common.yml 和 order-service.yml 的配置源

- [ ] **Step 7: 验收标准 4 - 配置变更监听**

修改 order-service.yml 的 `app.business.seckill-enabled` 为 false

Expected: order-service 日志显示：
```
秒杀开关状态变更: enabled=false
已清理秒杀缓存: count=...
```

- [ ] **Step 8: 验收标准 5 - 配置回滚**

在 Nacos 控制台：
1. 进入 gateway.yml 配置详情
2. 点击"历史版本"
3. 选择修改前的版本（replenishRate=5）
4. 点击"回滚"

Expected: gateway 日志显示 "限流配置已更新: replenishRate=5"

- [ ] **Step 9: 停止所有服务**

```bash
pkill -f "spring-boot:run"
docker compose down
```

- [ ] **Step 10: 验收通过确认**

确认以下验收标准全部通过：
- ✅ 所有服务成功集成 Nacos Config
- ✅ 配置热更新功能正常
- ✅ 敏感配置加密/解密正常
- ✅ 配置分层共享正常
- ✅ 配置变更监听器正常工作
- ✅ 配置回滚功能正常

---

## 总结

### 预计工作量

| 任务 | 预计时间 |
|------|----------|
| Task 1-2: Common 模块基础设施 | 1 小时 |
| Task 3-8: 6 个服务集成 | 3 小时 |
| Task 9: Nacos 配置文件创建 | 1 小时 |
| Task 10-11: 集成测试 | 1.5 小时 |
| Task 12: 文档更新 | 0.5 小时 |
| Task 13: 验收测试 | 1 小时 |
| **总计** | **8 小时** |

### 关键风险

1. **Nacos 加密密钥管理**
   - 风险: 密钥泄露导致敏感配置暴露
   - 缓解: 生产环境通过环境变量注入，不提交到代码仓库

2. **配置热更新失败**
   - 风险: @RefreshScope 未正确配置导致配置不刷新
   - 缓解: 每个动态配置类都添加集成测试验证

3. **配置优先级混乱**
   - 风险: bootstrap.yml、common.yml、service.yml 优先级不明确
   - 缓解: 在设计文档中明确优先级规则，测试验证

4. **服务启动失败**
   - 风险: Nacos 未就绪时服务启动失败
   - 缓解: bootstrap.yml 配置 fail-fast=false，允许降级启动

### 依赖项

- Nacos Server 2.x 已部署（docker-compose.yml 中已包含）
- Spring Cloud Alibaba 版本兼容（已在父 pom.xml 中管理）
- 所有服务已集成 Nacos Discovery（已完成）

### 后续优化

1. **配置灰度发布**: 使用 Nacos Beta 发布功能，先在部分实例验证
2. **配置变更审计**: 集成 Nacos 审计日志到 ELK
3. **配置监控告警**: 配置变更时发送钉钉/企业微信通知
4. **多环境管理**: 创建 test、prod Namespace，隔离不同环境配置

---

## 验收标准

实现完成后，需满足以下标准：

- [ ] 所有 6 个核心服务（gateway、order、payment、inventory、user、outbox-consumer）成功集成 Nacos Config
- [ ] 服务启动时能正确加载 common.yml 和服务特有配置
- [ ] 动态配置（限流、业务开关、线程池）支持热更新，无需重启服务
- [ ] 敏感配置（数据库密码、Token 密钥）使用 cipher() 加密存储
- [ ] 配置变更监听器正常工作，能记录审计日志
- [ ] 配置回滚功能正常，能恢复到历史版本
- [ ] README.md 包含配置中心使用指南
- [ ] 所有代码通过编译，无语法错误
- [ ] 集成测试通过，验证配置加载和热更新功能

---

**计划创建时间**: 2026-05-20  
**预计完成时间**: 2026-05-21  
**负责人**: 待分配  
**状态**: 待开始
