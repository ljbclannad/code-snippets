# ELK 集成实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 6 个核心微服务集成 ELK 日志监控系统，实现异步结构化日志发送和全链路追踪

**Architecture:** 使用 logstash-logback-encoder 通过 TCP 异步发送 JSON 格式日志到 Logstash，经过 filter enrichment 后存储到 Elasticsearch，通过 Kibana 可视化查询和监控

**Tech Stack:** 
- logstash-logback-encoder 7.4
- Logback AsyncAppender
- Logstash 8.x (docker-elk)
- Elasticsearch 8.x
- Kibana 8.x

---

## 文件结构概览

**新增文件：**
- `common/pom.xml` - 添加 logstash-logback-encoder 依赖
- `gateway/src/main/resources/logback-spring.xml` - Gateway 日志配置
- `order-service/src/main/resources/logback-spring.xml` - Order Service 日志配置
- `payment-service/src/main/resources/logback-spring.xml` - Payment Service 日志配置
- `inventory-service/src/main/resources/logback-spring.xml` - Inventory Service 日志配置
- `user-service/src/main/resources/logback-spring.xml` - User Service 日志配置
- `outbox-consumer/src/main/resources/logback-spring.xml` - Outbox Consumer 日志配置
- `/Users/lejinbo/IdeaProjects/docker-elk/logstash/pipeline/logstash.conf` - Logstash pipeline 配置

**修改文件：**
- `order-service/src/main/java/com/lx/governance/order/service/OrderAppService.java` - 添加结构化日志埋点
- `payment-service/src/main/java/com/lx/governance/payment/service/PaymentAppService.java` - 添加结构化日志埋点
- `inventory-service/src/main/java/com/lx/governance/inventory/service/InventoryAppService.java` - 添加结构化日志埋点
- `outbox-consumer/src/main/java/com/lx/governance/outbox/service/OutboxPublisher.java` - 添加结构化日志埋点
- `gateway/src/main/java/com/lx/governance/gateway/filter/AuthTokenGlobalFilter.java` - 添加结构化日志埋点

---

## Task 1: 启动并验证 ELK 环境

**Files:**
- 验证: `/Users/lejinbo/IdeaProjects/docker-elk/docker-compose.yml`

- [ ] **Step 1: 启动 docker-elk**

```bash
cd /Users/lejinbo/IdeaProjects/docker-elk
docker compose --profile=setup up -d
```

预期输出：setup 容器启动并初始化用户

- [ ] **Step 2: 启动 ELK 服务**

```bash
docker compose up -d
```

预期输出：elasticsearch、logstash、kibana 三个容器启动

- [ ] **Step 3: 验证 Elasticsearch**

```bash
curl -s http://localhost:9200 | grep cluster_name
```

预期输出：包含 "cluster_name" 字段的 JSON

- [ ] **Step 4: 验证 Kibana**

```bash
curl -s http://localhost:5601/api/status | grep -o '"state":"green"'
```

预期输出：`"state":"green"`（可能需要等待 30-60 秒）

- [ ] **Step 5: 验证 Logstash TCP 端口**

```bash
nc -zv localhost 50000
```

预期输出：`Connection to localhost port 50000 [tcp/*] succeeded!`

- [ ] **Step 6: 测试 Logstash 接收**

```bash
echo '{"message":"test","service":"test"}' | nc localhost 50000
sleep 2
curl -s "http://localhost:9200/_cat/indices?v" | grep governance
```

预期输出：可能看到 `governance-test-` 开头的索引（如果 Logstash 配置正确）

---

## Task 2: 添加 logstash-logback-encoder 依赖

**Files:**
- Modify: `common/pom.xml`

- [ ] **Step 1: 读取 common/pom.xml**

```bash
cat common/pom.xml
```

确认当前依赖结构

- [ ] **Step 2: 在 dependencies 节点末尾添加依赖**

在 `common/pom.xml` 的 `<dependencies>` 节点中添加：

```xml
<dependency>
    <groupId>net.logstash.logback</groupId>
    <artifactId>logstash-logback-encoder</artifactId>
    <version>7.4</version>
</dependency>
```

添加位置：在现有依赖之后，`</dependencies>` 之前

- [ ] **Step 3: 验证 Maven 依赖**

```bash
mvn dependency:tree -pl common | grep logstash
```

预期输出：`net.logstash.logback:logstash-logback-encoder:jar:7.4`

- [ ] **Step 4: 提交更改**

```bash
git add common/pom.xml
git commit -m "feat(common): add logstash-logback-encoder dependency

- Add logstash-logback-encoder 7.4 for structured logging
- All core services inherit this dependency via common module

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 3: 创建 Gateway logback 配置

**Files:**
- Create: `gateway/src/main/resources/logback-spring.xml`

- [ ] **Step 1: 创建 logback-spring.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <property name="APP_NAME" value="gateway"/>
    
    <!-- CONSOLE appender -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [%X{traceId}] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    
    <!-- LOGSTASH appender -->
    <appender name="LOGSTASH" class="net.logstash.logback.appender.LogstashTcpSocketAppender">
        <destination>localhost:50000</destination>
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <customFields>{"service":"${APP_NAME}"}</customFields>
        </encoder>
    </appender>
    
    <!-- ASYNC wrapper -->
    <appender name="ASYNC_LOGSTASH" class="ch.qos.logback.classic.AsyncAppender">
        <appender-ref ref="LOGSTASH"/>
        <queueSize>512</queueSize>
        <discardingThreshold>0</discardingThreshold>
        <neverBlock>true</neverBlock>
    </appender>
    
    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="ASYNC_LOGSTASH"/>
    </root>
</configuration>
```

- [ ] **Step 2: 验证文件创建**

```bash
ls -la gateway/src/main/resources/logback-spring.xml
```

预期输出：文件存在

- [ ] **Step 3: 提交更改**

```bash
git add gateway/src/main/resources/logback-spring.xml
git commit -m "feat(gateway): add logback configuration for ELK

- Configure LogstashTcpSocketAppender to send logs to localhost:50000
- Use AsyncAppender with neverBlock=true for performance
- Include traceId in console output

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 4: 创建 Order Service logback 配置

**Files:**
- Create: `order-service/src/main/resources/logback-spring.xml`

- [ ] **Step 1: 创建 logback-spring.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <property name="APP_NAME" value="order-service"/>
    
    <!-- CONSOLE appender -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [%X{traceId}] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    
    <!-- LOGSTASH appender -->
    <appender name="LOGSTASH" class="net.logstash.logback.appender.LogstashTcpSocketAppender">
        <destination>localhost:50000</destination>
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <customFields>{"service":"${APP_NAME}"}</customFields>
        </encoder>
    </appender>
    
    <!-- ASYNC wrapper -->
    <appender name="ASYNC_LOGSTASH" class="ch.qos.logback.classic.AsyncAppender">
        <appender-ref ref="LOGSTASH"/>
        <queueSize>512</queueSize>
        <discardingThreshold>0</discardingThreshold>
        <neverBlock>true</neverBlock>
    </appender>
    
    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="ASYNC_LOGSTASH"/>
    </root>
</configuration>
```

- [ ] **Step 2: 验证文件创建**

```bash
ls -la order-service/src/main/resources/logback-spring.xml
```

预期输出：文件存在

- [ ] **Step 3: 提交更改**

```bash
git add order-service/src/main/resources/logback-spring.xml
git commit -m "feat(order-service): add logback configuration for ELK

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 5: 创建 Payment Service logback 配置

**Files:**
- Create: `payment-service/src/main/resources/logback-spring.xml`

- [ ] **Step 1: 创建 logback-spring.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <property name="APP_NAME" value="payment-service"/>
    
    <!-- CONSOLE appender -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [%X{traceId}] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    
    <!-- LOGSTASH appender -->
    <appender name="LOGSTASH" class="net.logstash.logback.appender.LogstashTcpSocketAppender">
        <destination>localhost:50000</destination>
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <customFields>{"service":"${APP_NAME}"}</customFields>
        </encoder>
    </appender>
    
    <!-- ASYNC wrapper -->
    <appender name="ASYNC_LOGSTASH" class="ch.qos.logback.classic.AsyncAppender">
        <appender-ref ref="LOGSTASH"/>
        <queueSize>512</queueSize>
        <discardingThreshold>0</discardingThreshold>
        <neverBlock>true</neverBlock>
    </appender>
    
    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="ASYNC_LOGSTASH"/>
    </root>
</configuration>
```

- [ ] **Step 2: 验证文件创建**

```bash
ls -la payment-service/src/main/resources/logback-spring.xml
```

预期输出：文件存在

- [ ] **Step 3: 提交更改**

```bash
git add payment-service/src/main/resources/logback-spring.xml
git commit -m "feat(payment-service): add logback configuration for ELK

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 6: 创建 Inventory Service logback 配置

**Files:**
- Create: `inventory-service/src/main/resources/logback-spring.xml`

- [ ] **Step 1: 创建 logback-spring.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <property name="APP_NAME" value="inventory-service"/>
    
    <!-- CONSOLE appender -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [%X{traceId}] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    
    <!-- LOGSTASH appender -->
    <appender name="LOGSTASH" class="net.logstash.logback.appender.LogstashTcpSocketAppender">
        <destination>localhost:50000</destination>
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <customFields>{"service":"${APP_NAME}"}</customFields>
        </encoder>
    </appender>
    
    <!-- ASYNC wrapper -->
    <appender name="ASYNC_LOGSTASH" class="ch.qos.logback.classic.AsyncAppender">
        <appender-ref ref="LOGSTASH"/>
        <queueSize>512</queueSize>
        <discardingThreshold>0</discardingThreshold>
        <neverBlock>true</neverBlock>
    </appender>
    
    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="ASYNC_LOGSTASH"/>
    </root>
</configuration>
```

- [ ] **Step 2: 验证文件创建**

```bash
ls -la inventory-service/src/main/resources/logback-spring.xml
```

预期输出：文件存在

- [ ] **Step 3: 提交更改**

```bash
git add inventory-service/src/main/resources/logback-spring.xml
git commit -m "feat(inventory-service): add logback configuration for ELK

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 7: 创建 User Service logback 配置

**Files:**
- Create: `user-service/src/main/resources/logback-spring.xml`

- [ ] **Step 1: 创建 logback-spring.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <property name="APP_NAME" value="user-service"/>
    
    <!-- CONSOLE appender -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [%X{traceId}] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    
    <!-- LOGSTASH appender -->
    <appender name="LOGSTASH" class="net.logstash.logback.appender.LogstashTcpSocketAppender">
        <destination>localhost:50000</destination>
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <customFields>{"service":"${APP_NAME}"}</customFields>
        </encoder>
    </appender>
    
    <!-- ASYNC wrapper -->
    <appender name="ASYNC_LOGSTASH" class="ch.qos.logback.classic.AsyncAppender">
        <appender-ref ref="LOGSTASH"/>
        <queueSize>512</queueSize>
        <discardingThreshold>0</discardingThreshold>
        <neverBlock>true</neverBlock>
    </appender>
    
    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="ASYNC_LOGSTASH"/>
    </root>
</configuration>
```

- [ ] **Step 2: 验证文件创建**

```bash
ls -la user-service/src/main/resources/logback-spring.xml
```

预期输出：文件存在

- [ ] **Step 3: 提交更改**

```bash
git add user-service/src/main/resources/logback-spring.xml
git commit -m "feat(user-service): add logback configuration for ELK

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 8: 创建 Outbox Consumer logback 配置

**Files:**
- Create: `outbox-consumer/src/main/resources/logback-spring.xml`

- [ ] **Step 1: 创建 logback-spring.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <property name="APP_NAME" value="outbox-consumer"/>
    
    <!-- CONSOLE appender -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [%X{traceId}] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    
    <!-- LOGSTASH appender -->
    <appender name="LOGSTASH" class="net.logstash.logback.appender.LogstashTcpSocketAppender">
        <destination>localhost:50000</destination>
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <customFields>{"service":"${APP_NAME}"}</customFields>
        </encoder>
    </appender>
    
    <!-- ASYNC wrapper -->
    <appender name="ASYNC_LOGSTASH" class="ch.qos.logback.classic.AsyncAppender">
        <appender-ref ref="LOGSTASH"/>
        <queueSize>512</queueSize>
        <discardingThreshold>0</discardingThreshold>
        <neverBlock>true</neverBlock>
    </appender>
    
    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="ASYNC_LOGSTASH"/>
    </root>
</configuration>
```

- [ ] **Step 2: 验证文件创建**

```bash
ls -la outbox-consumer/src/main/resources/logback-spring.xml
```

预期输出：文件存在

- [ ] **Step 3: 提交更改**

```bash
git add outbox-consumer/src/main/resources/logback-spring.xml
git commit -m "feat(outbox-consumer): add logback configuration for ELK

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 9: 配置 Logstash Pipeline

**Files:**
- Modify: `/Users/lejinbo/IdeaProjects/docker-elk/logstash/pipeline/logstash.conf`

- [ ] **Step 1: 备份原配置**

```bash
cp /Users/lejinbo/IdeaProjects/docker-elk/logstash/pipeline/logstash.conf /Users/lejinbo/IdeaProjects/docker-elk/logstash/pipeline/logstash.conf.bak
```

- [ ] **Step 2: 替换 logstash.conf 内容**

```ruby
input {
  tcp {
    port => 50000
    codec => json_lines
  }
}

filter {
  # 1. 添加接收时间戳
  mutate {
    add_field => { "[@metadata][received_at]" => "%{@timestamp}" }
  }
  
  # 2. 解析服务名和环境
  if [service] {
    mutate {
      add_field => { "[@metadata][index_prefix]" => "governance-%{service}" }
    }
  } else {
    mutate {
      add_field => { "[@metadata][index_prefix]" => "governance-unknown" }
    }
  }
  
  # 3. 提取关键字段
  if [event] {
    mutate {
      add_tag => ["business_metric"]
    }
  }
  
  # 4. 异常堆栈处理
  if [stack_trace] {
    mutate {
      add_tag => ["has_exception"]
    }
  }
  
  # 5. traceId 索引优化
  if [traceId] {
    mutate {
      add_field => { "trace_id" => "%{traceId}" }
    }
  }
}

output {
  elasticsearch {
    hosts => ["elasticsearch:9200"]
    user => "logstash_internal"
    password => "${LOGSTASH_INTERNAL_PASSWORD}"
    index => "%{[@metadata][index_prefix]}-%{+YYYY.MM.dd}"
  }
}
```

- [ ] **Step 3: 重启 Logstash**

```bash
cd /Users/lejinbo/IdeaProjects/docker-elk
docker restart docker-elk-logstash-1
```

预期输出：容器重启成功

- [ ] **Step 4: 验证 Logstash 启动**

```bash
sleep 10
docker logs docker-elk-logstash-1 --tail 20
```

预期输出：包含 "Successfully started Logstash API endpoint" 或类似成功消息

- [ ] **Step 5: 验证 TCP 端口**

```bash
nc -zv localhost 50000
```

预期输出：连接成功

---

## Task 10: Order Service 业务日志埋点

**Files:**
- Modify: `order-service/src/main/java/com/lx/governance/order/service/OrderAppService.java`

- [ ] **Step 1: 读取 OrderAppService.java**

```bash
cat order-service/src/main/java/com/lx/governance/order/service/OrderAppService.java
```

确认现有方法结构

- [ ] **Step 2: 在 createOrder 方法添加日志埋点**

在方法开始处添加：
```java
import static net.logstash.logback.argument.StructuredArguments.kv;

log.info("Order creation started", 
    kv("event", "order_create_start"),
    kv("userId", request.getUserId()),
    kv("itemCount", request.getItems().size())
);
```

在方法成功返回前添加：
```java
log.info("Order created successfully", 
    kv("event", "order_created"),
    kv("orderId", orderId),
    kv("userId", request.getUserId()),
    kv("totalAmount", totalAmount),
    kv("status", "SUCCESS")
);
```

在异常捕获处添加：
```java
log.error("Order creation failed", 
    kv("event", "order_create_failed"),
    kv("userId", request.getUserId()),
    kv("error", e.getMessage()),
    kv("status", "FAILED"),
    e
);
```

- [ ] **Step 3: 在 queryOrder 方法添加日志埋点**

在方法开始处添加：
```java
log.info("Order query started", 
    kv("event", "order_query_start"),
    kv("orderId", orderId)
);
```

在查询成功后添加：
```java
log.info("Order query completed", 
    kv("event", "order_query_completed"),
    kv("orderId", orderId),
    kv("found", order != null)
);
```

- [ ] **Step 4: 验证编译**

```bash
mvn clean compile -pl order-service -am
```

预期输出：BUILD SUCCESS

- [ ] **Step 5: 提交更改**

```bash
git add order-service/src/main/java/com/lx/governance/order/service/OrderAppService.java
git commit -m "feat(order-service): add structured logging for business metrics

- Add event-based logging for order creation and query
- Include key business fields: orderId, userId, amount, status
- Use StructuredArguments for JSON output

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 11: Payment Service 业务日志埋点

**Files:**
- Modify: `payment-service/src/main/java/com/lx/governance/payment/service/PaymentAppService.java`

- [ ] **Step 1: 读取 PaymentAppService.java**

```bash
cat payment-service/src/main/java/com/lx/governance/payment/service/PaymentAppService.java
```

确认现有方法结构

- [ ] **Step 2: 在 preparePayment 方法添加日志埋点**

在方法开始处添加：
```java
import static net.logstash.logback.argument.StructuredArguments.kv;

log.info("Payment preparation started", 
    kv("event", "payment_prepare_start"),
    kv("orderId", request.getOrderId()),
    kv("amount", request.getAmount())
);
```

在方法成功返回前添加：
```java
log.info("Payment prepared successfully", 
    kv("event", "payment_prepared"),
    kv("paymentId", paymentId),
    kv("orderId", request.getOrderId()),
    kv("amount", request.getAmount()),
    kv("status", "PREPARED")
);
```

在异常捕获处添加：
```java
log.error("Payment preparation failed", 
    kv("event", "payment_prepare_failed"),
    kv("orderId", request.getOrderId()),
    kv("error", e.getMessage()),
    kv("status", "FAILED"),
    e
);
```

- [ ] **Step 3: 在 confirmPayment 方法添加日志埋点**

在方法开始处添加：
```java
log.info("Payment confirmation started", 
    kv("event", "payment_confirm_start"),
    kv("paymentId", paymentId)
);
```

在确认成功后添加：
```java
log.info("Payment confirmed successfully", 
    kv("event", "payment_confirmed"),
    kv("paymentId", paymentId),
    kv("status", "CONFIRMED")
);
```

- [ ] **Step 4: 验证编译**

```bash
mvn clean compile -pl payment-service -am
```

预期输出：BUILD SUCCESS

- [ ] **Step 5: 提交更改**

```bash
git add payment-service/src/main/java/com/lx/governance/payment/service/PaymentAppService.java
git commit -m "feat(payment-service): add structured logging for business metrics

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 12: Inventory Service 业务日志埋点

**Files:**
- Modify: `inventory-service/src/main/java/com/lx/governance/inventory/service/InventoryAppService.java`

- [ ] **Step 1: 读取 InventoryAppService.java**

```bash
cat inventory-service/src/main/java/com/lx/governance/inventory/service/InventoryAppService.java
```

确认现有方法结构

- [ ] **Step 2: 在 deductStock 方法添加日志埋点**

在方法开始处添加：
```java
import static net.logstash.logback.argument.StructuredArguments.kv;

log.info("Stock deduction started", 
    kv("event", "stock_deduct_start"),
    kv("productId", request.getProductId()),
    kv("quantity", request.getQuantity())
);
```

在方法成功返回前添加：
```java
log.info("Stock deducted successfully", 
    kv("event", "stock_deducted"),
    kv("productId", request.getProductId()),
    kv("quantity", request.getQuantity()),
    kv("status", "SUCCESS")
);
```

在库存不足时添加：
```java
log.warn("Stock deduction failed - insufficient stock", 
    kv("event", "stock_deduct_failed"),
    kv("productId", request.getProductId()),
    kv("requestedQuantity", request.getQuantity()),
    kv("availableStock", currentStock),
    kv("status", "INSUFFICIENT_STOCK")
);
```

- [ ] **Step 3: 验证编译**

```bash
mvn clean compile -pl inventory-service -am
```

预期输出：BUILD SUCCESS

- [ ] **Step 4: 提交更改**

```bash
git add inventory-service/src/main/java/com/lx/governance/inventory/service/InventoryAppService.java
git commit -m "feat(inventory-service): add structured logging for business metrics

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 13: Outbox Consumer 业务日志埋点

**Files:**
- Modify: `outbox-consumer/src/main/java/com/lx/governance/outbox/service/OutboxPublisher.java`

- [ ] **Step 1: 读取 OutboxPublisher.java**

```bash
cat outbox-consumer/src/main/java/com/lx/governance/outbox/service/OutboxPublisher.java
```

确认现有方法结构

- [ ] **Step 2: 在消息发布方法添加日志埋点**

在消息发布开始处添加：
```java
import static net.logstash.logback.argument.StructuredArguments.kv;

log.info("Outbox message publishing started", 
    kv("event", "outbox_publish_start"),
    kv("messageId", message.getId()),
    kv("eventType", message.getEventType())
);
```

在发布成功后添加：
```java
log.info("Outbox message published successfully", 
    kv("event", "outbox_published"),
    kv("messageId", message.getId()),
    kv("eventType", message.getEventType()),
    kv("status", "SUCCESS")
);
```

在发布失败时添加：
```java
log.error("Outbox message publishing failed", 
    kv("event", "outbox_publish_failed"),
    kv("messageId", message.getId()),
    kv("eventType", message.getEventType()),
    kv("retryCount", message.getRetryCount()),
    kv("error", e.getMessage()),
    kv("status", "FAILED"),
    e
);
```

- [ ] **Step 3: 验证编译**

```bash
mvn clean compile -pl outbox-consumer -am
```

预期输出：BUILD SUCCESS

- [ ] **Step 4: 提交更改**

```bash
git add outbox-consumer/src/main/java/com/lx/governance/outbox/service/OutboxPublisher.java
git commit -m "feat(outbox-consumer): add structured logging for business metrics

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 14: Gateway 业务日志埋点

**Files:**
- Modify: `gateway/src/main/java/com/lx/governance/gateway/filter/AuthTokenGlobalFilter.java`

- [ ] **Step 1: 读取 AuthTokenGlobalFilter.java**

```bash
cat gateway/src/main/java/com/lx/governance/gateway/filter/AuthTokenGlobalFilter.java
```

确认现有方法结构

- [ ] **Step 2: 在认证成功处添加日志埋点**

在认证成功后添加：
```java
import static net.logstash.logback.argument.StructuredArguments.kv;

log.info("Request authenticated", 
    kv("event", "auth_success"),
    kv("userId", userId),
    kv("path", request.getPath()),
    kv("method", request.getMethod())
);
```

在认证失败处添加：
```java
log.warn("Request authentication failed", 
    kv("event", "auth_failed"),
    kv("path", request.getPath()),
    kv("method", request.getMethod()),
    kv("reason", "invalid_token")
);
```

- [ ] **Step 3: 验证编译**

```bash
mvn clean compile -pl gateway -am
```

预期输出：BUILD SUCCESS

- [ ] **Step 4: 提交更改**

```bash
git add gateway/src/main/java/com/lx/governance/gateway/filter/AuthTokenGlobalFilter.java
git commit -m "feat(gateway): add structured logging for authentication events

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 15: 配置 Kibana Dashboard

**Files:**
- Manual: Kibana UI 配置

- [ ] **Step 1: 访问 Kibana**

打开浏览器访问：http://localhost:5601

使用默认凭据登录（如果需要）

- [ ] **Step 2: 创建 Index Pattern**

1. 导航到 Stack Management > Index Patterns
2. 点击 "Create index pattern"
3. 输入 index pattern: `governance-*`
4. 选择 time field: `@timestamp`
5. 点击 "Create index pattern"

预期结果：Index pattern 创建成功，显示字段列表

- [ ] **Step 3: 验证索引数据**

1. 导航到 Discover
2. 选择 `governance-*` index pattern
3. 设置时间范围为 Last 15 minutes
4. 在搜索栏输入: `service:order-service`

预期结果：能看到 order-service 的日志记录

- [ ] **Step 4: 创建业务指标 Dashboard**

1. 导航到 Dashboard > Create dashboard
2. 点击 "Create visualization"

**可视化 1: 订单创建趋势**
- Visualization type: Line
- Metrics: Count
- Buckets: Date Histogram on @timestamp
- Filter: `event:order_created`
- 保存为 "Order Creation Trend"

**可视化 2: 服务请求分布**
- Visualization type: Pie
- Metrics: Count
- Buckets: Terms on service.keyword
- 保存为 "Service Request Distribution"

**可视化 3: 错误率监控**
- Visualization type: Line
- Metrics: Count
- Buckets: Date Histogram on @timestamp
- Filter: `level:ERROR`
- 保存为 "Error Rate"

**可视化 4: 支付状态分布**
- Visualization type: Pie
- Metrics: Count
- Buckets: Terms on status.keyword
- Filter: `event:payment_*`
- 保存为 "Payment Status Distribution"

3. 将所有可视化添加到 Dashboard
4. 保存 Dashboard 为 "Governance Suite Monitoring"

- [ ] **Step 5: 创建告警规则（可选）**

1. 导航到 Stack Management > Rules and Connectors
2. 点击 "Create rule"
3. 配置错误率告警：
   - Name: "High Error Rate Alert"
   - Check every: 1 minute
   - Conditions: Count over all documents > 10 in last 5 minutes
   - Filter: `level:ERROR`
   - Actions: 根据需要配置通知方式

---

## Task 16: 端到端验证测试

**Files:**
- Test: 完整链路验证

- [ ] **Step 1: 启动所有服务**

```bash
# 确保 ELK 已启动
cd /Users/lejinbo/IdeaProjects/docker-elk
docker compose ps

# 启动微服务（根据实际启动方式调整）
cd /Users/lejinbo/LLM/code-snippets/governance-suite
# 启动 gateway, order-service, payment-service, inventory-service, user-service, outbox-consumer
```

预期输出：所有服务启动成功，无异常日志

- [ ] **Step 2: 验证日志连接**

等待 30 秒后检查各服务日志：

```bash
# 检查 order-service 日志
tail -f order-service/logs/application.log | grep "LogstashTcpSocketAppender"
```

预期输出：无连接错误，或看到 "Connected to Logstash" 类似消息

- [ ] **Step 3: 执行订单创建测试**

```bash
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer test-token" \
  -d '{
    "userId": "user123",
    "items": [
      {"productId": "prod1", "quantity": 2, "price": 100}
    ]
  }'
```

预期输出：订单创建成功，返回 orderId

- [ ] **Step 4: 在 Kibana 验证日志**

1. 打开 Kibana Discover
2. 搜索: `event:order_created AND userId:user123`
3. 验证字段：
   - `service` = "order-service"
   - `event` = "order_created"
   - `userId` = "user123"
   - `orderId` 存在
   - `totalAmount` 存在
   - `status` = "SUCCESS"

预期结果：能找到对应的日志记录，所有字段正确

- [ ] **Step 5: 验证全链路追踪**

1. 从上一步的日志中复制 `traceId`
2. 在 Kibana 搜索: `traceId:"<复制的traceId>"`
3. 验证能看到：
   - gateway 的认证日志
   - order-service 的订单创建日志
   - payment-service 的支付准备日志
   - inventory-service 的库存扣减日志
   - outbox-consumer 的消息发布日志（如果有）

预期结果：能看到完整的调用链路，所有服务的日志都包含相同的 traceId

- [ ] **Step 6: 验证错误日志**

触发一个错误场景（例如库存不足）：

```bash
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer test-token" \
  -d '{
    "userId": "user456",
    "items": [
      {"productId": "prod999", "quantity": 9999, "price": 100}
    ]
  }'
```

在 Kibana 搜索: `event:stock_deduct_failed AND productId:prod999`

预期结果：能看到库存不足的错误日志，包含 `status:INSUFFICIENT_STOCK`

- [ ] **Step 7: 验证 Dashboard**

1. 打开 "Governance Suite Monitoring" Dashboard
2. 验证所有可视化都有数据显示
3. 验证时间范围选择器工作正常
4. 验证可以通过点击图表进行数据钻取

预期结果：Dashboard 正常显示，数据实时更新

- [ ] **Step 8: 性能验证**

检查服务性能影响：

```bash
# 检查 order-service 的 CPU 和内存使用
docker stats --no-stream | grep order-service

# 检查日志队列状态（在服务日志中查找）
grep "AsyncAppender" order-service/logs/application.log
```

预期结果：
- CPU 使用率增加 < 5%
- 内存使用增加 < 50MB
- 无 "queue full" 或 "discarding events" 警告

- [ ] **Step 9: 压力测试（可选）**

使用 JMeter 或 ab 进行压力测试：

```bash
# 使用 ab 发送 1000 个请求
ab -n 1000 -c 10 -H "Authorization: Bearer test-token" \
  -p order-request.json -T application/json \
  http://localhost:8080/api/orders
```

在 Kibana 验证：
- 所有请求都有日志记录
- 无日志丢失
- Dashboard 正确显示请求量峰值

预期结果：日志系统在高负载下稳定运行

---

## 自审检查清单

在标记计划为完成之前，验证以下各项：

### 配置完整性
- [ ] 所有 6 个服务都有 logback-spring.xml 配置
- [ ] 所有配置文件中的 APP_NAME 正确
- [ ] AsyncAppender 配置参数一致（queueSize=512, neverBlock=true）
- [ ] Logstash destination 都指向 localhost:50000

### 依赖正确性
- [ ] common/pom.xml 包含 logstash-logback-encoder 7.4
- [ ] 所有服务都继承自 common 模块
- [ ] Maven 依赖树中能找到 logstash-logback-encoder

### 日志埋点完整性
- [ ] Order Service: order_created, order_query_completed 事件
- [ ] Payment Service: payment_prepared, payment_confirmed 事件
- [ ] Inventory Service: stock_deducted, stock_deduct_failed 事件
- [ ] Outbox Consumer: outbox_published, outbox_publish_failed 事件
- [ ] Gateway: auth_success, auth_failed 事件
- [ ] 所有埋点都使用 StructuredArguments.kv()
- [ ] 所有埋点都包含 event 字段

### Logstash 配置
- [ ] Pipeline 配置文件语法正确
- [ ] Input 监听 TCP 50000 端口
- [ ] Filter 正确提取 service 和 event 字段
- [ ] Output 连接到 Elasticsearch
- [ ] Index pattern 为 governance-%{service}-%{+YYYY.MM.dd}

### Kibana 配置
- [ ] Index pattern `governance-*` 创建成功
- [ ] Dashboard 包含至少 4 个可视化
- [ ] 所有可视化都能正常显示数据

### 功能验证
- [ ] 单个服务日志能发送到 Elasticsearch
- [ ] 多服务日志能通过 traceId 关联
- [ ] 业务指标日志能正确查询
- [ ] 错误日志能正确捕获和显示
- [ ] Dashboard 实时更新

### 性能验证
- [ ] 服务启动时间增加 < 2 秒
- [ ] 运行时 CPU 增加 < 5%
- [ ] 运行时内存增加 < 50MB
- [ ] 无日志队列溢出警告
- [ ] 压力测试下无日志丢失

### 文档和提交
- [ ] 所有代码更改都已提交
- [ ] Commit message 遵循约定式提交规范
- [ ] 每个 commit 都包含 Co-Authored-By
- [ ] 设计文档已更新（如有变更）

---

## 执行选项

完成计划编写后，选择以下方式之一执行：

### 选项 1: Subagent-Driven（推荐）

使用 `subagent-driven-development` 技能自动执行计划：

```
/subagent-driven-development docs/superpowers/plans/2026-05-22-elk-integration.md
```

**优点：**
- 自动并行执行独立任务
- 自动处理依赖关系
- 自动验证每个步骤
- 自动提交代码更改
- 保持主对话上下文清晰

**适用场景：**
- 任务较多（> 10 个）
- 任务之间有复杂依赖
- 希望自动化执行

### 选项 2: Inline Execution

使用 `executing-plans` 技能在当前对话中执行：

```
/executing-plans docs/superpowers/plans/2026-05-22-elk-integration.md
```

**优点：**
- 在当前对话中执行
- 可以实时查看进度
- 可以随时干预和调整

**适用场景：**
- 任务较少（< 10 个）
- 需要频繁人工确认
- 希望保持对话连续性

---

## 预期结果

执行完成后，系统将具备以下能力：

1. **日志收集**：所有 6 个核心服务的日志自动发送到 ELK
2. **结构化日志**：业务指标以 JSON 格式存储，便于查询和分析
3. **全链路追踪**：通过 traceId 关联一次请求的所有服务日志
4. **实时监控**：Kibana Dashboard 实时显示业务指标和系统状态
5. **告警能力**：可配置基于日志的告警规则
6. **性能影响最小**：异步发送，对业务性能影响 < 5%

