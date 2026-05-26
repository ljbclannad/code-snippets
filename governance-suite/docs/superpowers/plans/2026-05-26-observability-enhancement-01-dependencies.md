# 可观测性增强实施计划 - Part 1: 依赖和基础配置

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 配置 Micrometer Metrics + Tracing 依赖，为所有服务启用 Prometheus 和 Jaeger 集成

**Architecture:** 在父 POM 中统一管理版本，在 common 模块添加核心依赖，所有服务通过继承获得监控能力

**Tech Stack:** 
- Micrometer 1.2.5
- OpenTelemetry 1.36.0
- Spring Boot Actuator 3.2.5
- Prometheus
- Jaeger

---

## Task 1: 父 POM 版本管理

**Files:**
- Modify: `pom.xml:24-36` (properties section)
- Modify: `pom.xml:38-81` (dependencyManagement section)

- [ ] **Step 1: 添加 Micrometer Tracing 版本属性**

在 `pom.xml` 的 `<properties>` 部分添加：

```xml
<micrometer-tracing.version>1.2.5</micrometer-tracing.version>
<opentelemetry.version>1.36.0</opentelemetry.version>
```

完整的 properties 部分应该是：

```xml
<properties>
  <java.version>17</java.version>
  <spring-boot.version>3.2.5</spring-boot.version>
  <spring-cloud.version>2023.0.1</spring-cloud.version>
  <spring-cloud-alibaba.version>2022.0.0.0</spring-cloud-alibaba.version>
  <shardingsphere.version>5.5.0</shardingsphere.version>
  <mybatis-spring-boot-starter.version>3.0.3</mybatis-spring-boot-starter.version>
  <seata.version>2.0.0</seata.version>
  <maven.compiler.release>${java.version}</maven.compiler.release>
  <mysql.version>8.0.33</mysql.version>
  <spring-ai.version>1.0.0-M3</spring-ai.version>
  <micrometer-tracing.version>1.2.5</micrometer-tracing.version>
  <opentelemetry.version>1.36.0</opentelemetry.version>
</properties>
```

- [ ] **Step 2: 添加 Micrometer Tracing BOM**

在 `pom.xml` 的 `<dependencyManagement>` 部分，在 Spring AI BOM 之后添加：

```xml
<!-- Micrometer Tracing BOM -->
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-tracing-bom</artifactId>
  <version>${micrometer-tracing.version}</version>
  <type>pom</type>
  <scope>import</scope>
</dependency>
```

- [ ] **Step 3: 验证 POM 格式**

运行：`mvn validate`

预期输出：`BUILD SUCCESS`

- [ ] **Step 4: 提交父 POM 更改**

```bash
git add pom.xml
git commit -m "build: add Micrometer Tracing and OpenTelemetry version management

- Add micrometer-tracing.version 1.2.5
- Add opentelemetry.version 1.36.0
- Add micrometer-tracing-bom to dependencyManagement"
```

---

## Task 2: Common 模块依赖

**Files:**
- Modify: `common/pom.xml:14-65`

- [ ] **Step 1: 添加 Micrometer Registry Prometheus 依赖**

在 `common/pom.xml` 的 `<dependencies>` 部分，在 `spring-boot-starter-actuator` 之后添加：

```xml
<!-- Micrometer Registry for Prometheus -->
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

- [ ] **Step 2: 添加 Micrometer Tracing Bridge**

继续添加：

```xml
<!-- Micrometer Tracing Bridge -->
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
```

- [ ] **Step 3: 添加 OpenTelemetry Exporter**

继续添加：

```xml
<!-- OpenTelemetry Exporter -->
<dependency>
  <groupId>io.opentelemetry</groupId>
  <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
```

- [ ] **Step 4: 添加 AOP 依赖**

继续添加：

```xml
<!-- AOP for custom metrics -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-aop</artifactId>
</dependency>
```

- [ ] **Step 5: 验证依赖解析**

运行：`mvn dependency:tree -pl common | grep -E "micrometer|opentelemetry"`

预期输出应包含：
```
[INFO] +- io.micrometer:micrometer-registry-prometheus:jar:...
[INFO] +- io.micrometer:micrometer-tracing-bridge-otel:jar:...
[INFO] +- io.opentelemetry:opentelemetry-exporter-otlp:jar:...
```

- [ ] **Step 6: 提交 Common 模块依赖**

```bash
git add common/pom.xml
git commit -m "build(common): add Micrometer and OpenTelemetry dependencies

- Add micrometer-registry-prometheus for Prometheus metrics
- Add micrometer-tracing-bridge-otel for distributed tracing
- Add opentelemetry-exporter-otlp for OTLP protocol
- Add spring-boot-starter-aop for custom metrics aspect"
```

---

## Task 3: Gateway 配置更新

**Files:**
- Modify: `gateway/src/main/resources/application.yml:81-121`

- [ ] **Step 1: 更新 Gateway management 配置**

在 `gateway/src/main/resources/application.yml` 中，更新 `management` 部分（已有部分配置，需要补充）：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,refresh,env,metrics,prometheus
  endpoint:
    health:
      show-details: always
  health:
    defaults:
      enabled: true
  metrics:
    export:
      prometheus:
        enabled: true
    tags:
      application: ${spring.application.name}
      environment: ${ENVIRONMENT:dev}
    distribution:
      percentiles-histogram:
        http.server.requests: true
  tracing:
    sampling:
      probability: 1.0
  otlp:
    tracing:
      endpoint: http://localhost:4317
      compression: gzip
```

- [ ] **Step 2: 验证配置格式**

运行：`mvn validate -pl gateway`

预期输出：`BUILD SUCCESS`

- [ ] **Step 3: 提交 Gateway 配置**

```bash
git add gateway/src/main/resources/application.yml
git commit -m "config(gateway): add Micrometer Tracing configuration

- Enable 100% trace sampling for development
- Configure OTLP endpoint for Jaeger
- Enable HTTP request percentiles histogram"
```

---

## Task 4: User Service 配置

**Files:**
- Modify: `user-service/src/main/resources/application.yml`

- [ ] **Step 1: 添加 User Service management 配置**

在 `user-service/src/main/resources/application.yml` 中添加 `management` 部分：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: always
  metrics:
    export:
      prometheus:
        enabled: true
    tags:
      application: ${spring.application.name}
      environment: ${ENVIRONMENT:dev}
    distribution:
      percentiles-histogram:
        http.server.requests: true
  tracing:
    sampling:
      probability: 1.0
  otlp:
    tracing:
      endpoint: http://localhost:4317
      compression: gzip
```

- [ ] **Step 2: 验证配置**

运行：`mvn validate -pl user-service`

预期输出：`BUILD SUCCESS`

- [ ] **Step 3: 提交 User Service 配置**

```bash
git add user-service/src/main/resources/application.yml
git commit -m "config(user-service): add Micrometer Tracing configuration"
```

---

## Task 5: Order Service 配置

**Files:**
- Modify: `order-service/src/main/resources/application.yml`

- [ ] **Step 1: 添加 Order Service management 配置**

在 `order-service/src/main/resources/application.yml` 中添加 `management` 部分：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: always
  metrics:
    export:
      prometheus:
        enabled: true
    tags:
      application: ${spring.application.name}
      environment: ${ENVIRONMENT:dev}
    distribution:
      percentiles-histogram:
        http.server.requests: true
  tracing:
    sampling:
      probability: 1.0
  otlp:
    tracing:
      endpoint: http://localhost:4317
      compression: gzip
```

- [ ] **Step 2: 验证配置**

运行：`mvn validate -pl order-service`

预期输出：`BUILD SUCCESS`

- [ ] **Step 3: 提交 Order Service 配置**

```bash
git add order-service/src/main/resources/application.yml
git commit -m "config(order-service): add Micrometer Tracing configuration"
```

---

## Task 6: Payment Service 配置

**Files:**
- Modify: `payment-service/src/main/resources/application.yml`

- [ ] **Step 1: 添加 Payment Service management 配置**

在 `payment-service/src/main/resources/application.yml` 中添加 `management` 部分：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: always
  metrics:
    export:
      prometheus:
        enabled: true
    tags:
      application: ${spring.application.name}
      environment: ${ENVIRONMENT:dev}
    distribution:
      percentiles-histogram:
        http.server.requests: true
  tracing:
    sampling:
      probability: 1.0
  otlp:
    tracing:
      endpoint: http://localhost:4317
      compression: gzip
```

- [ ] **Step 2: 验证配置**

运行：`mvn validate -pl payment-service`

预期输出：`BUILD SUCCESS`

- [ ] **Step 3: 提交 Payment Service 配置**

```bash
git add payment-service/src/main/resources/application.yml
git commit -m "config(payment-service): add Micrometer Tracing configuration"
```

---

## Task 7: Inventory Service 配置

**Files:**
- Modify: `inventory-service/src/main/resources/application.yml`

- [ ] **Step 1: 添加 Inventory Service management 配置**

在 `inventory-service/src/main/resources/application.yml` 中添加 `management` 部分：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: always
  metrics:
    export:
      prometheus:
        enabled: true
    tags:
      application: ${spring.application.name}
      environment: ${ENVIRONMENT:dev}
    distribution:
      percentiles-histogram:
        http.server.requests: true
  tracing:
    sampling:
      probability: 1.0
  otlp:
    tracing:
      endpoint: http://localhost:4317
      compression: gzip
```

- [ ] **Step 2: 验证配置**

运行：`mvn validate -pl inventory-service`

预期输出：`BUILD SUCCESS`

- [ ] **Step 3: 提交 Inventory Service 配置**

```bash
git add inventory-service/src/main/resources/application.yml
git commit -m "config(inventory-service): add Micrometer Tracing configuration"
```

---

## Task 8: Outbox Consumer 配置

**Files:**
- Modify: `outbox-consumer/src/main/resources/application.yml`

- [ ] **Step 1: 添加 Outbox Consumer management 配置**

在 `outbox-consumer/src/main/resources/application.yml` 中添加 `management` 部分：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: always
  metrics:
    export:
      prometheus:
        enabled: true
    tags:
      application: ${spring.application.name}
      environment: ${ENVIRONMENT:dev}
    distribution:
      percentiles-histogram:
        http.server.requests: true
  tracing:
    sampling:
      probability: 1.0
  otlp:
    tracing:
      endpoint: http://localhost:4317
      compression: gzip
```

- [ ] **Step 2: 验证配置**

运行：`mvn validate -pl outbox-consumer`

预期输出：`BUILD SUCCESS`

- [ ] **Step 3: 提交 Outbox Consumer 配置**

```bash
git add outbox-consumer/src/main/resources/application.yml
git commit -m "config(outbox-consumer): add Micrometer Tracing configuration"
```

---

## Task 9: 构建验证

**Files:**
- None (verification only)

- [ ] **Step 1: 清理并构建所有模块**

运行：`mvn clean package -DskipTests -T 1C`

预期输出：`BUILD SUCCESS` 且所有模块编译成功

- [ ] **Step 2: 检查依赖冲突**

运行：`mvn dependency:tree | grep -i conflict`

预期输出：无冲突或仅有可接受的版本差异

- [ ] **Step 3: 验证 Actuator 端点可用性**

启动 Gateway：`mvn -q -pl gateway spring-boot:run`

在另一个终端运行：`curl http://localhost:8080/actuator/prometheus`

预期输出：包含 Prometheus 格式的指标数据，例如：
```
# HELP jvm_memory_used_bytes The amount of used memory
# TYPE jvm_memory_used_bytes gauge
jvm_memory_used_bytes{area="heap",id="G1 Eden Space",} 1.234567E7
```

停止 Gateway（Ctrl+C）

- [ ] **Step 4: 创建验证总结提交**

```bash
git commit --allow-empty -m "chore: verify observability dependencies integration

All services now have:
- Micrometer Metrics with Prometheus registry
- Micrometer Tracing with OpenTelemetry bridge
- Actuator endpoints exposed
- OTLP exporter configured for Jaeger

Verified:
- All modules build successfully
- No dependency conflicts
- Actuator /prometheus endpoint accessible"
```

---

## 完成标准

Part 1 完成后，应满足：

✅ 父 POM 包含 Micrometer Tracing 版本管理
✅ Common 模块包含所有必需的监控依赖
✅ 所有 6 个服务的 application.yml 配置了 management 部分
✅ 项目可以成功构建（`mvn clean package -DskipTests`）
✅ Gateway 的 /actuator/prometheus 端点可访问

**下一步**: 继续 Part 2 - 监控组件实现（注解、切面、Trace 改造）
