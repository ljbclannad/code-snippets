# 可观测性增强设计文档

## 文档信息

- **创建日期**: 2026-05-26
- **设计范围**: Micrometer Metrics + Tracing 完整集成
- **目标**: 实现 Metrics（Prometheus + Grafana）和 Tracing（Jaeger）的一次性完整集成

## 1. 目标

为 governance-suite 微服务项目实现完整的可观测性方案，包括：

1. **Metrics 监控**: 集成 Micrometer + Prometheus + Grafana，覆盖 JVM、HTTP、业务指标
2. **分布式 Tracing**: 集成 Micrometer Tracing + OpenTelemetry + Jaeger
3. **告警规则**: 配置 Prometheus 基础告警规则
4. **可视化**: 为 7 个服务创建独立的 Grafana 仪表板
5. **与现有系统集成**: 与 ELK 日志系统协同，保留现有 traceId 机制

## 2. 当前状态

### 2.1 已有基础设施

✅ **Prometheus + Grafana**: 已在 docker-compose.yml 中配置
- Prometheus: 端口 9090，已配置 7 个服务的抓取目标
- Grafana: 端口 3000，已配置数据源和预配置目录

✅ **Actuator**: common 模块已包含 spring-boot-starter-actuator

✅ **Gateway 配置**: 已暴露 actuator 端点（health, info, metrics, prometheus）

✅ **ELK 栈**: 已有 Elasticsearch + Logstash + Kibana 用于日志

✅ **结构化日志**: 已使用 logstash-logback-encoder

### 2.2 缺失部分

❌ **Micrometer 依赖**: 除 common 外，其他服务缺少 micrometer-registry-prometheus
❌ **Tracing 依赖**: 所有服务缺少 micrometer-tracing-bridge-otel
❌ **自定义业务指标**: 无业务指标埋点（订单 TPS、支付成功率等）
❌ **Grafana 仪表板**: 只有 ai-chat-service 仪表板，缺少其他 6 个服务
❌ **告警规则**: 无 Prometheus 告警规则配置
❌ **Jaeger**: 未部署 Jaeger 追踪后端

### 2.3 现有 traceId 机制

- `common` 模块的 `Trace` 类：通过 MDC 管理自定义 traceId
- `TraceIdGlobalFilter`（Gateway）：生成/传递 `X-Trace-Id` 请求头
- `TraceIdFilter`（下游服务）：从请求头提取 traceId 并设置到 MDC
- `ApiResponse` 中包含 traceId 字段返回给客户端

## 3. 整体架构

### 3.1 可观测性架构图

```
┌─────────────┐
│   客户端     │
└──────┬──────┘
       │ HTTP
       ↓
┌─────────────────────────────────────────────────────────┐
│  Gateway (8080)                                          │
│  - 生成 Trace Context (W3C Trace Context)               │
│  - 暴露 /actuator/prometheus                            │
│  - 自定义指标：请求 QPS、限流次数、熔断次数              │
└──────┬──────────────────────────────────────────────────┘
       │
       ├─→ User Service (8084)
       ├─→ Order Service (8081) ──→ Inventory (8083)
       │                        └─→ Payment (8082)
       └─→ Outbox Consumer (8090) ──→ RabbitMQ
       
       每个服务：
       ├─ Micrometer Metrics → Prometheus (9090)
       ├─ Micrometer Tracing → Jaeger (4317 OTLP)
       └─ Structured Logs → Logstash → Elasticsearch

┌──────────────────────────────────────────────────────────┐
│  监控后端                                                 │
├──────────────────────────────────────────────────────────┤
│  Prometheus (9090)                                       │
│  - 每 15s 抓取所有服务的 /actuator/prometheus            │
│  - 数据保留 7 天                                          │
│  - 告警规则评估                                           │
└──────┬───────────────────────────────────────────────────┘
       │
       ↓
┌──────────────────────────────────────────────────────────┐
│  Grafana (3000)                                          │
│  - 7 个服务仪表板（gateway, user, order, payment,        │
│    inventory, outbox, ai-chat）                          │
│  - 数据源：Prometheus                                     │
└──────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────┐
│  Jaeger All-in-One (16686 UI, 4317 OTLP)                │
│  - 接收所有服务的 Trace Span                              │
│  - 内存存储（重启丢失）                                    │
│  - 支持依赖关系图、Trace 查询                             │
└──────────────────────────────────────────────────────────┘
```

### 3.2 可观测性三支柱整合

| 支柱 | 技术栈 | 用途 | 数据保留 |
|------|--------|------|---------|
| **Metrics** | Micrometer + Prometheus + Grafana | 实时指标监控、趋势分析、告警 | 7 天 |
| **Tracing** | Micrometer Tracing + OpenTelemetry + Jaeger | 分布式调用链追踪、性能分析 | 内存（重启丢失） |
| **Logging** | Logback + Logstash + Elasticsearch + Kibana | 结构化日志、全文搜索 | 已有配置 |

### 3.3 关联机制

- **Trace ID 注入日志**: OpenTelemetry trace ID 自动注入到 MDC，在日志中以 `traceId` 字段输出
- **Grafana → Jaeger 链接**: Grafana 仪表板可配置链接到 Jaeger Trace 详情
- **日志 → Trace 关联**: 通过日志中的 traceId 在 Jaeger 中查找对应调用链

## 4. 依赖配置

### 4.1 父 POM 版本管理

在 `pom.xml` 的 `<properties>` 中添加：

```xml
<micrometer-tracing.version>1.2.5</micrometer-tracing.version>
<opentelemetry.version>1.36.0</opentelemetry.version>
```

在 `<dependencyManagement>` 中添加 BOM：

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bom</artifactId>
    <version>${micrometer-tracing.version}</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

### 4.2 Common 模块依赖

`common/pom.xml` 已有 `spring-boot-starter-actuator`，需要添加：

```xml
<!-- Micrometer Registry for Prometheus -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>

<!-- Micrometer Tracing Bridge -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>

<!-- OpenTelemetry Exporter -->
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>

<!-- AOP for custom metrics -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-aop</artifactId>
</dependency>
```

**说明**:
- `micrometer-registry-prometheus`: 提供 Prometheus 格式的指标暴露
- `micrometer-tracing-bridge-otel`: Micrometer 到 OpenTelemetry 的桥接
- `opentelemetry-exporter-otlp`: 通过 OTLP 协议发送 trace 到 Jaeger
- `spring-boot-starter-aop`: 用于自定义指标的 AOP 切面

### 4.3 各服务模块配置

所有服务（gateway, user-service, order-service, payment-service, inventory-service, outbox-consumer）的 `application.yml` 统一添加：

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
      probability: 1.0  # 开发环境 100% 采样
  otlp:
    tracing:
      endpoint: http://localhost:4317
      compression: gzip
```

**配置说明**:
- `exposure.include`: 暴露的 actuator 端点
- `metrics.tags`: 为所有指标添加全局标签（应用名、环境）
- `percentiles-histogram`: 启用 HTTP 请求的百分位直方图（用于 P50/P90/P99 计算）
- `tracing.sampling.probability`: 采样率（1.0 = 100%，生产环境建议 0.1-0.2）
- `otlp.tracing.endpoint`: Jaeger OTLP gRPC 端点

## 5. 自定义业务指标设计

### 5.1 埋点策略

采用**混合方式**：
- **AOP 自动埋点**: 通用指标（方法执行时间、成功率）
- **手动埋点**: 特殊业务逻辑（秒杀、Seata 事务、Outbox 处理）

### 5.2 AOP 自动埋点

#### 5.2.1 监控注解

在 `common` 模块创建 `@Monitored` 注解：

```java
package com.lx.governance.common.monitoring.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Monitored {
    /**
     * 指标名称（必填）
     */
    String value();
    
    /**
     * 指标描述
     */
    String description() default "";
    
    /**
     * 是否记录失败次数
     */
    boolean recordFailures() default true;
}
```

#### 5.2.2 监控切面

在 `common` 模块创建 `MonitoringAspect`：

```java
package com.lx.governance.common.monitoring.aspect;

import com.lx.governance.common.monitoring.annotation.Monitored;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class MonitoringAspect {
    
    private final MeterRegistry meterRegistry;
    
    @Around("@annotation(monitored)")
    public Object monitor(ProceedingJoinPoint joinPoint, Monitored monitored) throws Throwable {
        String metricName = monitored.value();
        Timer timer = Timer.builder(metricName + ".duration")
                .description(monitored.description())
                .tag("method", joinPoint.getSignature().getName())
                .register(meterRegistry);
        
        Timer.Sample sample = Timer.start(meterRegistry);
        
        try {
            Object result = joinPoint.proceed();
            sample.stop(timer);
            
            // 记录成功次数
            Counter.builder(metricName + ".total")
                    .tag("status", "success")
                    .tag("method", joinPoint.getSignature().getName())
                    .register(meterRegistry)
                    .increment();
            
            return result;
        } catch (Throwable ex) {
            sample.stop(timer);
            
            // 记录失败次数
            if (monitored.recordFailures()) {
                Counter.builder(metricName + ".total")
                        .tag("status", "failure")
                        .tag("method", joinPoint.getSignature().getName())
                        .tag("exception", ex.getClass().getSimpleName())
                        .register(meterRegistry)
                        .increment();
            }
            
            throw ex;
        }
    }
}
```

**自动记录的指标**:
- `{metricName}.duration`: 方法执行时间（Timer）
- `{metricName}.total`: 方法调用次数（Counter），带 `status` 标签（success/failure）

#### 5.2.3 使用示例

```java
@Service
public class OrderService {
    
    @Monitored(value = "order.create", description = "订单创建")
    public Order createOrder(CreateOrderRequest request) {
        // 业务逻辑
    }
}
```

生成的指标：
- `order.create.duration`: 订单创建耗时
- `order.create.total{status="success"}`: 成功次数
- `order.create.total{status="failure",exception="StockInsufficientException"}`: 失败次数

### 5.3 手动埋点的业务指标

#### 5.3.1 Order Service 指标

| 指标名称 | 类型 | 说明 | 标签 |
|---------|------|------|------|
| `order.create.total` | Counter | 订单创建总数 | status: success/failure |
| `order.create.duration` | Timer | 订单创建耗时 | - |
| `seckill.stock.remaining` | Gauge | 秒杀库存剩余 | sku_id |
| `seckill.orders.total` | Counter | 秒杀订单数 | sku_id, status |
| `seata.transaction.total` | Counter | Seata 事务数 | status: commit/rollback |
| `seata.transaction.duration` | Timer | Seata 事务耗时 | status |

**实现示例**:

```java
@Service
@RequiredArgsConstructor
public class OrderAppService {
    
    private final MeterRegistry meterRegistry;
    
    @GlobalTransactional
    public Order createOrder(CreateOrderRequest request) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String xid = RootContext.getXID();
        
        try {
            // 业务逻辑：调用库存、支付服务
            Order order = doCreateOrder(request);
            
            // 记录 Seata 事务成功
            sample.stop(Timer.builder("seata.transaction.duration")
                    .tag("status", "commit")
                    .register(meterRegistry));
            
            meterRegistry.counter("seata.transaction.total", "status", "commit").increment();
            
            return order;
        } catch (Exception ex) {
            // 记录 Seata 事务回滚
            sample.stop(Timer.builder("seata.transaction.duration")
                    .tag("status", "rollback")
                    .register(meterRegistry));
            
            meterRegistry.counter("seata.transaction.total", "status", "rollback").increment();
            
            throw ex;
        }
    }
    
    public void updateSeckillStock(String skuId, int remaining) {
        meterRegistry.gauge("seckill.stock.remaining", 
                Tags.of("sku_id", skuId), 
                remaining);
    }
}
```

#### 5.3.2 Payment Service 指标

| 指标名称 | 类型 | 说明 | 标签 |
|---------|------|------|------|
| `payment.prepare.total` | Counter | 支付预处理次数 | status: success/failure |
| `payment.confirm.total` | Counter | 支付确认次数 | status: success/failure |
| `payment.success.rate` | Gauge | 支付成功率（滑动窗口） | - |
| `payment.amount.total` | Counter | 支付金额累计 | - |

#### 5.3.3 Inventory Service 指标

| 指标名称 | 类型 | 说明 | 标签 |
|---------|------|------|------|
| `inventory.deduct.total` | Counter | 库存扣减次数 | status: success/failure |
| `inventory.deduct.retry` | Counter | 乐观锁重试次数 | - |
| `inventory.deduct.duration` | Timer | 库存扣减耗时 | - |

#### 5.3.4 Outbox Consumer 指标

| 指标名称 | 类型 | 说明 | 标签 |
|---------|------|------|------|
| `outbox.poll.total` | Counter | 轮询次数 | - |
| `outbox.publish.total` | Counter | 发布次数 | status: success/failure |
| `outbox.publish.lag` | Gauge | 发布延迟（秒） | - |
| `outbox.dead.total` | Counter | DEAD 消息数 | - |
| `outbox.retry.total` | Counter | 重试次数 | - |

**实现示例**:

```java
@Service
@RequiredArgsConstructor
public class OutboxPollingService {
    
    private final MeterRegistry meterRegistry;
    
    @Scheduled(fixedDelay = 1000)
    public void pollAndPublish() {
        meterRegistry.counter("outbox.poll.total").increment();
        
        List<OutboxMessage> messages = outboxRepository.findPendingMessages();
        
        for (OutboxMessage msg : messages) {
            try {
                rabbitTemplate.send(msg.getExchange(), msg.getRoutingKey(), msg.getPayload());
                
                // 记录发布延迟
                long lagSeconds = Duration.between(msg.getCreatedAt(), Instant.now()).getSeconds();
                meterRegistry.gauge("outbox.publish.lag", lagSeconds);
                
                meterRegistry.counter("outbox.publish.total", "status", "success").increment();
                
            } catch (Exception ex) {
                meterRegistry.counter("outbox.publish.total", "status", "failure").increment();
                meterRegistry.counter("outbox.retry.total").increment();
            }
        }
    }
}
```

#### 5.3.5 Gateway 指标

| 指标名称 | 类型 | 说明 | 标签 |
|---------|------|------|------|
| `gateway.ratelimit.rejected` | Counter | 限流拒绝次数 | - |
| `gateway.circuit.open` | Gauge | 熔断器打开状态 | route |

#### 5.3.6 User Service 指标

| 指标名称 | 类型 | 说明 | 标签 |
|---------|------|------|------|
| `user.register.total` | Counter | 注册次数 | status: success/failure |
| `user.login.total` | Counter | 登录次数 | status: success/failure |
| `user.token.issued` | Counter | Token 签发次数 | - |

### 5.4 JVM 和基础设施指标

Spring Boot Actuator 自动提供（无需手动埋点）：

**JVM 指标**:
- `jvm.memory.used`: 堆/非堆内存使用量
- `jvm.memory.max`: 最大内存
- `jvm.gc.pause`: GC 暂停时间
- `jvm.gc.memory.allocated`: GC 分配的内存
- `jvm.threads.live`: 活跃线程数
- `jvm.threads.daemon`: 守护线程数

**HTTP 指标**:
- `http.server.requests`: HTTP 请求统计（带 uri、method、status 标签）

**数据库连接池**:
- `hikaricp.connections.active`: 活跃连接数
- `hikaricp.connections.idle`: 空闲连接数
- `hikaricp.connections.pending`: 等待连接数

**Redis 连接池**:
- `lettuce.command.completion`: Redis 命令执行时间

**Tomcat 线程池**:
- `tomcat.threads.busy`: 忙碌线程数
- `tomcat.threads.current`: 当前线程数

## 6. 分布式 Tracing 集成设计

### 6.1 Trace Context 传播

#### 6.1.1 自动传播范围

Micrometer Tracing 自动在以下场景传播 Trace Context（W3C Trace Context 标准）：

| 场景 | 说明 | 自动化程度 |
|------|------|-----------|
| **HTTP 入口** | Spring MVC Controller 自动创建 root span | 100% 自动 |
| **HTTP 出口** | OpenFeign、RestTemplate 自动传播 traceparent header | 100% 自动 |
| **消息队列** | RabbitMQ 生产者/消费者自动传播 | 100% 自动 |
| **数据库** | JDBC 操作自动创建 span | 100% 自动 |
| **Redis** | Lettuce 客户端自动创建 span | 100% 自动 |
| **异步任务** | @Async、@Scheduled 自动传播 | 100% 自动 |

#### 6.1.2 手动埋点场景

需要手动创建 span 的场景：

| 场景 | 原因 | 实现方式 |
|------|------|---------|
| **Seata 全局事务边界** | 需要标记事务开始/提交/回滚 | `Tracer.nextSpan().name("seata-global-tx")` |
| **秒杀 Lua 脚本执行** | Redis Lua 脚本执行不自动追踪 | 在调用前后手动创建 span |
| **ShardingSphere 分片路由** | 需要记录路由到哪个分片 | 在分片逻辑中添加 span tag |
| **Outbox 轮询处理** | 需要追踪从轮询到发布的完整流程 | 为每条消息创建独立 span |

#### 6.1.3 手动埋点示例

**Seata 事务追踪**:

```java
@Service
@RequiredArgsConstructor
public class OrderAppService {
    
    private final Tracer tracer;
    
    @GlobalTransactional
    public Order createOrder(CreateOrderRequest request) {
        Span span = tracer.nextSpan().name("seata-global-transaction");
        
        try (Tracer.SpanInScope ws = tracer.withSpan(span.start())) {
            String xid = RootContext.getXID();
            span.tag("seata.xid", xid);
            span.tag("order.no", request.getOrderNo());
            span.tag("user.id", String.valueOf(request.getUserId()));
            
            // 业务逻辑
            Order order = doCreateOrder(request);
            
            span.tag("seata.status", "commit");
            return order;
            
        } catch (Exception ex) {
            span.tag("seata.status", "rollback");
            span.tag("error", "true");
            span.tag("error.type", ex.getClass().getName());
            throw ex;
        } finally {
            span.end();
        }
    }
}
```

**秒杀 Lua 脚本追踪**:

```java
public boolean executeSeckill(String skuId, Long userId) {
    Span span = tracer.nextSpan().name("seckill-lua-script");
    
    try (Tracer.SpanInScope ws = tracer.withSpan(span.start())) {
        span.tag("sku.id", skuId);
        span.tag("user.id", String.valueOf(userId));
        
        Long result = redisTemplate.execute(seckillScript, 
                Collections.singletonList(skuId), 
                userId);
        
        span.tag("result", result == 1 ? "success" : "failure");
        return result == 1;
        
    } finally {
        span.end();
    }
}
```

**Outbox 消息追踪**:

```java
public void publishMessage(OutboxMessage msg) {
    Span span = tracer.nextSpan().name("outbox-publish");
    
    try (Tracer.SpanInScope ws = tracer.withSpan(span.start())) {
        span.tag("message.id", msg.getId());
        span.tag("exchange", msg.getExchange());
        span.tag("routing.key", msg.getRoutingKey());
        
        long lagSeconds = Duration.between(msg.getCreatedAt(), Instant.now()).getSeconds();
        span.tag("publish.lag.seconds", String.valueOf(lagSeconds));
        
        rabbitTemplate.send(msg.getExchange(), msg.getRoutingKey(), msg.getPayload());
        
        span.tag("status", "success");
        
    } catch (Exception ex) {
        span.tag("status", "failure");
        span.tag("error", "true");
        throw ex;
    } finally {
        span.end();
    }
}
```

### 6.2 Span 标签规范

统一的 span tag 命名约定：

**业务标签**:
```java
span.tag("order.no", orderNo);
span.tag("user.id", userId);
span.tag("payment.amount", amount);
span.tag("sku.id", skuId);
```

**技术标签**:
```java
span.tag("db.shard", "t_order_0");
span.tag("seata.xid", xid);
span.tag("retry.count", String.valueOf(retryCount));
span.tag("cache.hit", "true");
```

**错误标签**:
```java
span.tag("error", "true");
span.tag("error.type", exception.getClass().getName());
span.tag("error.message", exception.getMessage());
```

### 6.3 Trace ID 与现有 traceId 的关系

#### 6.3.1 迁移策略

**逐步迁移，保持兼容**：

1. **保留现有 `Trace` 类和 `TraceIdFilter`**，但改为从 Micrometer 的 `TraceContext` 获取 trace ID
2. **`ApiResponse` 中的 `traceId` 字段**继续返回，但值改为 OpenTelemetry 的 trace ID（16 字节十六进制）
3. **MDC 中同时保留两个字段**：
   - `traceId`：OpenTelemetry trace ID（用于日志关联）
   - `spanId`：当前 span ID

#### 6.3.2 代码调整

**改造 `Trace.java`**:

```java
package com.lx.governance.common.trace;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.TraceContext;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class Trace {
    
    private final Tracer tracer;
    
    /**
     * 获取当前 trace ID（从 Micrometer Tracing）
     */
    public static String getTraceId() {
        TraceContext context = tracer.currentTraceContext().context();
        if (context != null) {
            String traceId = context.traceId();
            MDC.put("traceId", traceId);
            MDC.put("spanId", context.spanId());
            return traceId;
        }
        // Fallback：如果没有 trace context，生成临时 ID
        return generateFallbackId();
    }
    
    private static String generateFallbackId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
    
    public static void clear() {
        MDC.remove("traceId");
        MDC.remove("spanId");
    }
}
```

**简化 `TraceIdFilter`**（下游服务）:

```java
@Component
@Order(-100)
@RequiredArgsConstructor
public class TraceIdFilter extends OncePerRequestFilter {
    
    private final Tracer tracer;
    
    @Override
    protected void doFilterInternal(HttpServletRequest request, 
                                   HttpServletResponse response, 
                                   FilterChain filterChain) throws ServletException, IOException {
        try {
            // Micrometer Tracing 已自动从 traceparent header 提取 trace context
            // 这里只需要将 trace ID 放入 MDC 和响应头
            TraceContext context = tracer.currentTraceContext().context();
            if (context != null) {
                String traceId = context.traceId();
                MDC.put("traceId", traceId);
                MDC.put("spanId", context.spanId());
                response.setHeader("X-Trace-Id", traceId);
            }
            
            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}
```

**Gateway 的 `TraceIdGlobalFilter` 简化**:

```java
@Component
@Order(-90)
@RequiredArgsConstructor
public class TraceIdGlobalFilter implements GlobalFilter {
    
    private final Tracer tracer;
    
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // Micrometer Tracing 已自动创建 root span
        // 这里只需要将 trace ID 添加到响应头
        TraceContext context = tracer.currentTraceContext().context();
        if (context != null) {
            exchange.getResponse().getHeaders().add("X-Trace-Id", context.traceId());
        }
        
        return chain.filter(exchange);
    }
}
```

### 6.4 Jaeger 部署配置

#### 6.4.1 Docker Compose 配置

在 `docker-compose.yml` 中添加 Jaeger 服务：

```yaml
jaeger:
  image: jaegertracing/all-in-one:1.55
  container_name: gov-jaeger
  restart: unless-stopped
  environment:
    - COLLECTOR_OTLP_ENABLED=true
    - SPAN_STORAGE_TYPE=memory
    - MEMORY_MAX_TRACES=10000
  ports:
    - "16686:16686"  # Jaeger UI
    - "4317:4317"    # OTLP gRPC
    - "4318:4318"    # OTLP HTTP (备用)
  healthcheck:
    test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:16686/"]
    interval: 30s
    timeout: 10s
    retries: 3
```

**配置说明**:
- `COLLECTOR_OTLP_ENABLED=true`: 启用 OTLP 协议接收
- `SPAN_STORAGE_TYPE=memory`: 使用内存存储（开发环境）
- `MEMORY_MAX_TRACES=10000`: 最多保留 10000 条 trace
- 端口 4317: OTLP gRPC（服务通过此端口发送 span）
- 端口 16686: Jaeger UI（浏览器访问）

#### 6.4.2 生产环境建议

如需持久化存储，修改为 Elasticsearch 后端：

```yaml
jaeger:
  image: jaegertracing/all-in-one:1.55
  environment:
    - COLLECTOR_OTLP_ENABLED=true
    - SPAN_STORAGE_TYPE=elasticsearch
    - ES_SERVER_URLS=http://elasticsearch:9200
    - ES_INDEX_PREFIX=jaeger
  depends_on:
    elasticsearch:
      condition: service_healthy
```

## 7. Grafana 仪表板设计

### 7.1 仪表板结构

每个服务一个独立仪表板，统一的布局结构（5 行布局）：

```
┌─────────────────────────────────────────────────────┐
│  服务名称 Dashboard                                  │
├─────────────────────────────────────────────────────┤
│  第一行：核心业务指标（4 个 Stat 面板）               │
│  - 请求总数  - 成功率  - P99延迟  - 错误率           │
├─────────────────────────────────────────────────────┤
│  第二行：业务指标时序图（2 个 Graph 面板）            │
│  - QPS 趋势  - 响应时间分位数（P50/P90/P99）         │
├─────────────────────────────────────────────────────┤
│  第三行：服务特定业务指标（因服务而异）               │
│  例如 order-service:                                │
│  - 订单创建成功率  - Seata 事务状态分布              │
│  - 秒杀库存剩余    - 分片路由分布                    │
├─────────────────────────────────────────────────────┤
│  第四行：JVM 指标（3 个 Graph 面板）                 │
│  - 堆内存使用  - GC 次数  - 线程数                   │
├─────────────────────────────────────────────────────┤
│  第五行：基础设施指标（2 个 Graph 面板）              │
│  - 数据库连接池  - Redis 连接池                      │
└─────────────────────────────────────────────────────┘
```

### 7.2 仪表板清单

| 服务 | 文件名 | 特色指标 |
|------|--------|---------|
| Gateway | `gateway-dashboard.json` | 限流拒绝数、熔断器状态、路由分布 |
| User Service | `user-service-dashboard.json` | 注册/登录成功率、Token 签发数 |
| Order Service | `order-service-dashboard.json` | 订单创建成功率、Seata 事务、秒杀 TPS |
| Payment Service | `payment-service-dashboard.json` | 支付成功率、两阶段耗时、分片分布 |
| Inventory Service | `inventory-service-dashboard.json` | 库存扣减成功率、乐观锁重试次数 |
| Outbox Consumer | `outbox-consumer-dashboard.json` | 发布延迟、重试次数、DEAD 消息数 |
| AI Chat Service | `ai-chat-service.json` | 已存在，保持不变 |

### 7.3 通用面板 PromQL 示例

#### 7.3.1 核心业务指标（第一行）

**请求总数（Stat 面板）**:
```promql
sum(increase(http_server_requests_seconds_count{application="order-service"}[1h]))
```

**成功率（Stat 面板，百分比）**:
```promql
sum(rate(http_server_requests_seconds_count{application="order-service",status!~"5.."}[5m]))
/
sum(rate(http_server_requests_seconds_count{application="order-service"}[5m]))
* 100
```

**P99 延迟（Stat 面板，毫秒）**:
```promql
histogram_quantile(0.99, 
  sum(rate(http_server_requests_seconds_bucket{application="order-service"}[5m])) by (le)
) * 1000
```

**错误率（Stat 面板，百分比）**:
```promql
sum(rate(http_server_requests_seconds_count{application="order-service",status=~"5.."}[5m]))
/
sum(rate(http_server_requests_seconds_count{application="order-service"}[5m]))
* 100
```

#### 7.3.2 业务指标时序图（第二行）

**QPS 趋势（Graph 面板）**:
```promql
sum(rate(http_server_requests_seconds_count{application="order-service"}[1m])) by (uri)
```

**响应时间分位数（Graph 面板）**:
```promql
# P50
histogram_quantile(0.50, sum(rate(http_server_requests_seconds_bucket{application="order-service"}[5m])) by (le)) * 1000

# P90
histogram_quantile(0.90, sum(rate(http_server_requests_seconds_bucket{application="order-service"}[5m])) by (le)) * 1000

# P99
histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket{application="order-service"}[5m])) by (le)) * 1000
```

#### 7.3.3 JVM 指标（第四行）

**堆内存使用（Graph 面板）**:
```promql
jvm_memory_used_bytes{application="order-service",area="heap"} / 1024 / 1024
```

**GC 次数（Graph 面板）**:
```promql
rate(jvm_gc_pause_seconds_count{application="order-service"}[1m])
```

**线程数（Graph 面板）**:
```promql
jvm_threads_live{application="order-service"}
```

#### 7.3.4 基础设施指标（第五行）

**数据库连接池（Graph 面板）**:
```promql
hikaricp_connections_active{application="order-service"}
hikaricp_connections_idle{application="order-service"}
```

### 7.4 服务特定指标示例

#### Order Service 特色面板

**订单创建成功率**:
```promql
sum(rate(order_create_total{status="success"}[5m]))
/
sum(rate(order_create_total[5m]))
* 100
```

**Seata 事务状态分布（Pie Chart）**:
```promql
sum(increase(seata_transaction_total[1h])) by (status)
```

**秒杀库存剩余（Gauge）**:
```promql
seckill_stock_remaining
```

#### Payment Service 特色面板

**支付成功率**:
```promql
sum(rate(payment_confirm_total{status="success"}[5m]))
/
sum(rate(payment_confirm_total[5m]))
* 100
```

**两阶段耗时对比**:
```promql
# Prepare 阶段
histogram_quantile(0.99, sum(rate(payment_prepare_duration_bucket[5m])) by (le)) * 1000

# Confirm 阶段
histogram_quantile(0.99, sum(rate(payment_confirm_duration_bucket[5m])) by (le)) * 1000
```

#### Outbox Consumer 特色面板

**发布延迟（Gauge）**:
```promql
outbox_publish_lag
```

**DEAD 消息增长趋势**:
```promql
increase(outbox_dead_total[10m])
```

### 7.5 Grafana 预配置

#### 7.5.1 数据源配置

`docker/grafana/provisioning/datasources/prometheus.yml` 已存在，确保内容为：

```yaml
apiVersion: 1

datasources:
  - name: Prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true
    editable: true
```

#### 7.5.2 仪表板自动加载配置

`docker/grafana/provisioning/dashboards/dashboard.yml` 已存在，确保内容为：

```yaml
apiVersion: 1

providers:
  - name: 'Governance Suite'
    orgId: 1
    folder: 'Services'
    type: file
    disableDeletion: false
    updateIntervalSeconds: 10
    allowUiUpdates: true
    options:
      path: /etc/grafana/provisioning/dashboards
```

#### 7.5.3 仪表板变量

每个仪表板支持的变量：

```json
{
  "templating": {
    "list": [
      {
        "name": "application",
        "type": "constant",
        "current": {
          "value": "order-service"
        }
      },
      {
        "name": "environment",
        "type": "query",
        "datasource": "Prometheus",
        "query": "label_values(environment)",
        "current": {
          "value": "dev"
        }
      },
      {
        "name": "instance",
        "type": "query",
        "datasource": "Prometheus",
        "query": "label_values(up{application=\"$application\"}, instance)",
        "multi": true,
        "includeAll": true
      }
    ]
  }
}
```

### 7.6 Jaeger 链接集成

在 Grafana 仪表板中添加 Jaeger 链接，点击 trace ID 跳转到 Jaeger UI：

```json
{
  "links": [
    {
      "title": "View in Jaeger",
      "url": "http://localhost:16686/trace/${__field.name}",
      "targetBlank": true
    }
  ]
}
```

## 8. Prometheus 告警规则

### 8.1 告警规则文件结构

创建 `docker/prometheus/alerts/` 目录，按服务拆分告警规则：

```
docker/prometheus/alerts/
├── common-alerts.yml          # 通用告警（JVM、HTTP）
├── gateway-alerts.yml         # 网关告警
├── order-service-alerts.yml   # 订单服务告警
├── payment-service-alerts.yml # 支付服务告警
├── inventory-alerts.yml       # 库存服务告警
└── outbox-alerts.yml          # Outbox 告警
```

### 8.2 通用告警规则

**`docker/prometheus/alerts/common-alerts.yml`**:

```yaml
groups:
  - name: common
    interval: 30s
    rules:
      # 服务下线
      - alert: ServiceDown
        expr: up{job=~".*-service|gateway"} == 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "服务 {{ $labels.job }} 下线"
          description: "{{ $labels.instance }} 已下线超过 1 分钟"

      # HTTP 错误率过高
      - alert: HighHttpErrorRate
        expr: |
          sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) by (application)
          /
          sum(rate(http_server_requests_seconds_count[5m])) by (application)
          > 0.05
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "{{ $labels.application }} HTTP 错误率过高"
          description: "5xx 错误率 {{ $value | humanizePercentage }}"

      # HTTP 响应时间过长
      - alert: HighHttpLatency
        expr: |
          histogram_quantile(0.99, 
            sum(rate(http_server_requests_seconds_bucket[5m])) by (application, le)
          ) > 3
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "{{ $labels.application }} HTTP P99 延迟过高"
          description: "P99 延迟 {{ $value | humanizeDuration }}"

      # JVM 堆内存使用率过高
      - alert: HighJvmMemoryUsage
        expr: |
          jvm_memory_used_bytes{area="heap"} 
          / 
          jvm_memory_max_bytes{area="heap"} 
          > 0.85
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "{{ $labels.application }} JVM 堆内存使用率过高"
          description: "堆内存使用率 {{ $value | humanizePercentage }}"

      # GC 时间占比过高
      - alert: HighGcTime
        expr: |
          rate(jvm_gc_pause_seconds_sum[5m]) 
          / 
          rate(jvm_gc_pause_seconds_count[5m]) 
          > 0.1
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "{{ $labels.application }} GC 时间占比过高"
          description: "平均 GC 时间 {{ $value | humanizeDuration }}"

      # 线程数过多
      - alert: HighThreadCount
        expr: jvm_threads_live > 500
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "{{ $labels.application }} 线程数过多"
          description: "当前线程数 {{ $value }}"

      # 数据库连接池耗尽
      - alert: DatabaseConnectionPoolExhausted
        expr: |
          hikaricp_connections_active 
          / 
          hikaricp_connections_max 
          > 0.9
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: "{{ $labels.application }} 数据库连接池接近耗尽"
          description: "连接池使用率 {{ $value | humanizePercentage }}"
```

### 8.3 Gateway 告警规则

**`docker/prometheus/alerts/gateway-alerts.yml`**:

```yaml
groups:
  - name: gateway
    interval: 30s
    rules:
      # 限流拒绝率过高
      - alert: HighRateLimitRejection
        expr: |
          rate(gateway_ratelimit_rejected_total[5m]) > 10
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "Gateway 限流拒绝率过高"
          description: "每秒拒绝 {{ $value }} 个请求"

      # 熔断器打开
      - alert: CircuitBreakerOpen
        expr: gateway_circuit_open == 1
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "Gateway 熔断器打开"
          description: "路由 {{ $labels.route }} 的熔断器已打开"
```

### 8.4 Order Service 告警规则

**`docker/prometheus/alerts/order-service-alerts.yml`**:

```yaml
groups:
  - name: order-service
    interval: 30s
    rules:
      # 订单创建失败率过高
      - alert: HighOrderFailureRate
        expr: |
          sum(rate(order_create_total{status="failure"}[5m]))
          /
          sum(rate(order_create_total[5m]))
          > 0.1
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "订单创建失败率过高"
          description: "失败率 {{ $value | humanizePercentage }}"

      # Seata 事务回滚率过高
      - alert: HighSeataRollbackRate
        expr: |
          sum(rate(seata_transaction_total{status="rollback"}[5m]))
          /
          sum(rate(seata_transaction_total[5m]))
          > 0.2
        for: 3m
        labels:
          severity: critical
        annotations:
          summary: "Seata 分布式事务回滚率过高"
          description: "回滚率 {{ $value | humanizePercentage }}"

      # 秒杀库存耗尽
      - alert: SeckillStockDepleted
        expr: seckill_stock_remaining == 0
        for: 30s
        labels:
          severity: info
        annotations:
          summary: "秒杀商品库存耗尽"
          description: "SKU {{ $labels.sku_id }} 库存已清空"

      # 订单创建耗时过长
      - alert: SlowOrderCreation
        expr: |
          histogram_quantile(0.99, 
            sum(rate(order_create_duration_bucket[5m])) by (le)
          ) > 5
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "订单创建耗时过长"
          description: "P99 耗时 {{ $value | humanizeDuration }}"
```

### 8.5 Payment Service 告警规则

**`docker/prometheus/alerts/payment-service-alerts.yml`**:

```yaml
groups:
  - name: payment-service
    interval: 30s
    rules:
      # 支付成功率过低
      - alert: LowPaymentSuccessRate
        expr: |
          sum(rate(payment_confirm_total{status="success"}[5m]))
          /
          sum(rate(payment_confirm_total[5m]))
          < 0.95
        for: 3m
        labels:
          severity: critical
        annotations:
          summary: "支付成功率过低"
          description: "成功率 {{ $value | humanizePercentage }}"

      # 支付预处理失败率过高
      - alert: HighPaymentPrepareFailureRate
        expr: |
          sum(rate(payment_prepare_total{status="failure"}[5m]))
          /
          sum(rate(payment_prepare_total[5m]))
          > 0.1
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "支付预处理失败率过高"
          description: "失败率 {{ $value | humanizePercentage }}"
```

### 8.6 Inventory Service 告警规则

**`docker/prometheus/alerts/inventory-alerts.yml`**:

```yaml
groups:
  - name: inventory-service
    interval: 30s
    rules:
      # 库存扣减失败率过高
      - alert: HighInventoryDeductFailureRate
        expr: |
          sum(rate(inventory_deduct_total{status="failure"}[5m]))
          /
          sum(rate(inventory_deduct_total[5m]))
          > 0.1
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "库存扣减失败率过高"
          description: "失败率 {{ $value | humanizePercentage }}"

      # 乐观锁重试次数过多
      - alert: HighInventoryRetryCount
        expr: rate(inventory_deduct_retry_total[5m]) > 5
        for: 3m
        labels:
          severity: warning
        annotations:
          summary: "库存扣减乐观锁重试次数过多"
          description: "每秒重试 {{ $value }} 次，可能存在高并发冲突"
```

### 8.7 Outbox Consumer 告警规则

**`docker/prometheus/alerts/outbox-alerts.yml`**:

```yaml
groups:
  - name: outbox
    interval: 30s
    rules:
      # Outbox 发布延迟过高
      - alert: HighOutboxPublishLag
        expr: outbox_publish_lag > 60
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "Outbox 发布延迟过高"
          description: "延迟 {{ $value | humanizeDuration }}"

      # DEAD 消息数增长
      - alert: OutboxDeadMessagesIncreasing
        expr: increase(outbox_dead_total[10m]) > 5
        labels:
          severity: critical
        annotations:
          summary: "Outbox DEAD 消息数增长"
          description: "10 分钟内新增 {{ $value }} 条 DEAD 消息"

      # Outbox 发布失败率过高
      - alert: HighOutboxPublishFailureRate
        expr: |
          sum(rate(outbox_publish_total{status="failure"}[5m]))
          /
          sum(rate(outbox_publish_total[5m]))
          > 0.2
        for: 3m
        labels:
          severity: warning
        annotations:
          summary: "Outbox 发布失败率过高"
          description: "失败率 {{ $value | humanizePercentage }}"
```

### 8.8 Prometheus 配置更新

#### 8.8.1 加载告警规则

在 `docker/prometheus/prometheus.yml` 中添加：

```yaml
rule_files:
  - '/etc/prometheus/alerts/*.yml'
```

#### 8.8.2 Docker Compose 挂载

在 `docker-compose.yml` 的 prometheus 服务中添加告警规则目录挂载：

```yaml
prometheus:
  image: prom/prometheus:latest
  container_name: gov-prometheus
  restart: unless-stopped
  ports:
    - "9090:9090"
  volumes:
    - ./docker/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro
    - ./docker/prometheus/alerts:/etc/prometheus/alerts:ro  # 新增
    - gov_prometheus_data:/prometheus
  command:
    - '--config.file=/etc/prometheus/prometheus.yml'
    - '--storage.tsdb.path=/prometheus'
    - '--storage.tsdb.retention.time=7d'  # 新增：7 天保留
    - '--web.console.libraries=/usr/share/prometheus/console_libraries'
    - '--web.console.templates=/usr/share/prometheus/consoles'
    - '--web.enable-lifecycle'
```

### 8.9 告警级别说明

| 级别 | 说明 | 响应时间 |
|------|------|---------|
| **critical** | 严重影响业务，需要立即处理 | 立即 |
| **warning** | 潜在问题，需要关注 | 30 分钟内 |
| **info** | 信息性告警，无需立即处理 | 工作时间内 |

## 9. 代码结构调整

### 9.1 Common 模块新增组件

```
common/src/main/java/com/lx/governance/common/
├── monitoring/
│   ├── annotation/
│   │   └── Monitored.java              # 监控注解
│   ├── aspect/
│   │   └── MonitoringAspect.java       # AOP 切面
│   ├── config/
│   │   ├── MetricsConfig.java          # Metrics 配置类
│   │   └── TracingConfig.java          # Tracing 配置类（可选）
│   └── util/
│       └── MetricsHelper.java          # 指标工具类
└── trace/
    └── Trace.java                       # 改造：从 TraceContext 获取 ID
```

### 9.2 各服务模块调整清单

每个服务需要完成的调整：

#### 9.2.1 配置文件调整

在 `application.yml` 中添加 management 配置（见 4.3 节）。

#### 9.2.2 业务代码调整

**Gateway**:
- 简化 `TraceIdGlobalFilter`（见 6.3.2 节）
- 添加限流拒绝指标埋点
- 添加熔断器状态指标埋点

**User Service**:
- 在 `UserService.register()` 添加 `@Monitored("user.register")`
- 在 `UserService.login()` 添加 `@Monitored("user.login")`
- 在 `AuthTokenService.generateToken()` 添加 token 签发计数

**Order Service**:
- 在 `OrderAppService.createOrder()` 添加 Seata 事务追踪（见 6.1.3 节）
- 在秒杀方法中添加 Lua 脚本追踪
- 添加秒杀库存 Gauge 更新
- 添加 `@Monitored("order.create")`

**Payment Service**:
- 在 `PaymentService.prepare()` 添加 `@Monitored("payment.prepare")`
- 在 `PaymentService.confirm()` 添加 `@Monitored("payment.confirm")`
- 添加支付成功率 Gauge 计算

**Inventory Service**:
- 在 `InventoryService.deduct()` 添加 `@Monitored("inventory.deduct")`
- 在乐观锁重试循环中添加重试计数

**Outbox Consumer**:
- 在轮询方法中添加指标埋点（见 5.3.4 节）
- 为每条消息添加 span 追踪（见 6.1.3 节）

### 9.3 配置类示例

**MetricsConfig.java**（可选，用于自定义配置）:

```java
package com.lx.governance.common.monitoring.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetricsConfig {
    
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> metricsCommonTags() {
        return registry -> registry.config()
                .commonTags("region", "cn-east")
                .commonTags("team", "governance");
    }
}
```

## 10. 实施步骤和验证

### 10.1 实施步骤

#### 阶段 1：依赖和配置（预计 1 小时）

1. 修改父 POM 添加版本管理
2. 修改 common 模块 POM 添加依赖
3. 更新所有服务的 `application.yml`
4. 创建 common 模块的监控组件（注解、切面、配置类）
5. 改造 `Trace.java` 和 Filter 类

#### 阶段 2：基础设施部署（预计 30 分钟）

1. 在 `docker-compose.yml` 添加 Jaeger 服务
2. 创建 `docker/prometheus/alerts/` 目录
3. 编写 6 个告警规则文件
4. 更新 `prometheus.yml` 加载告警规则
5. 启动基础设施：`docker compose up -d`

#### 阶段 3：自定义指标埋点（预计 2 小时）

1. 在各服务的关键方法添加 `@Monitored` 注解
2. 在特殊场景添加手动埋点（Seata、秒杀、Outbox）
3. 添加 Tracing 手动 span 创建

#### 阶段 4：Grafana 仪表板（预计 2 小时）

1. 创建 6 个服务的仪表板 JSON 文件
2. 放置到 `docker/grafana/provisioning/dashboards/`
3. 重启 Grafana 验证自动加载

#### 阶段 5：验证和调优（预计 1 小时）

1. 构建并启动所有服务
2. 执行业务操作触发指标
3. 验证 Prometheus、Grafana、Jaeger
4. 触发告警场景验证告警规则

### 10.2 验证清单

#### 10.2.1 基础验证

- [ ] 项目构建成功：`mvn clean package -DskipTests`
- [ ] 基础设施启动：`docker compose up -d`
- [ ] 所有服务启动成功
- [ ] Actuator 端点可访问：
  ```bash
  curl http://localhost:8080/actuator/prometheus  # Gateway
  curl http://localhost:8081/actuator/prometheus  # Order
  curl http://localhost:8082/actuator/prometheus  # Payment
  curl http://localhost:8083/actuator/prometheus  # Inventory
  curl http://localhost:8084/actuator/prometheus  # User
  curl http://localhost:8090/actuator/prometheus  # Outbox
  ```

#### 10.2.2 Prometheus 验证

- [ ] 访问 Prometheus UI：http://localhost:9090
- [ ] 检查 Targets 状态（Status → Targets）：所有服务应为 UP
- [ ] 查询基础指标：
  ```promql
  up{job="gateway"}
  jvm_memory_used_bytes{application="order-service"}
  http_server_requests_seconds_count
  ```
- [ ] 检查告警规则加载（Status → Rules）：应显示 6 个规则组

#### 10.2.3 Metrics 验证

执行业务操作后查询自定义指标：

```bash
# 注册用户
curl -X POST http://localhost:8080/api/user/register \
  -H "Content-Type: application/json" \
  -d '{"username":"test","password":"123456"}'

# 创建订单
curl -X POST http://localhost:8080/api/order/create \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"skuId":"SKU001","quantity":1}'
```

在 Prometheus 中查询：

```promql
order_create_total
payment_prepare_total
inventory_deduct_total
user_register_total
```

- [ ] 自定义业务指标正常采集
- [ ] 指标包含正确的标签（status、application 等）

#### 10.2.4 Tracing 验证

- [ ] 访问 Jaeger UI：http://localhost:16686
- [ ] 在 Service 下拉框中看到所有服务
- [ ] 执行创建订单操作
- [ ] 在 Jaeger 中搜索 trace（选择 gateway 服务，点击 Find Traces）
- [ ] 验证调用链：
  - Gateway → Order Service → Inventory Service
  - Gateway → Order Service → Payment Service
- [ ] 验证 span 包含正确的 tag：
  - `order.no`
  - `user.id`
  - `seata.xid`
- [ ] 验证数据库和 Redis 操作自动追踪

#### 10.2.5 Grafana 验证

- [ ] 访问 Grafana：http://localhost:3000（admin/admin）
- [ ] 检查数据源配置（Configuration → Data Sources）：Prometheus 应为绿色
- [ ] 验证仪表板自动加载（Dashboards → Browse → Services 文件夹）
- [ ] 打开 Order Service 仪表板，检查：
  - [ ] 核心业务指标显示正常（请求总数、成功率、P99 延迟、错误率）
  - [ ] QPS 趋势图有数据
  - [ ] JVM 指标显示正常
  - [ ] 订单创建成功率面板有数据
  - [ ] Seata 事务状态分布有数据

#### 10.2.6 告警验证

- [ ] 在 Prometheus UI 查看 Alerts 页面（Alerts 菜单）
- [ ] 触发告警场景：
  ```bash
  # 停止一个服务触发 ServiceDown 告警
  # 等待 1 分钟后查看告警状态变为 Firing
  ```
- [ ] 验证告警状态变化：Inactive → Pending → Firing
- [ ] 恢复服务后验证告警解除

#### 10.2.7 日志关联验证

- [ ] 执行业务操作获取 trace ID（从响应头 `X-Trace-Id` 或 `ApiResponse.traceId`）
- [ ] 在 Kibana 中搜索该 trace ID
- [ ] 验证日志中包含 `traceId` 和 `spanId` 字段
- [ ] 在 Jaeger 中搜索同一 trace ID
- [ ] 验证日志时间戳与 span 时间戳对应

### 10.3 性能影响评估

| 组件 | CPU 开销 | 内存开销 | 网络开销 | 说明 |
|------|---------|---------|---------|------|
| Micrometer Metrics | < 1% | ~10MB | 可忽略 | 内存中聚合，定期暴露 |
| Micrometer Tracing (100% 采样) | 2-5% | ~20MB | ~1KB/请求 | 每个请求创建 span |
| Prometheus 抓取 | 可忽略 | - | ~10KB/15s | 每 15s 一次 HTTP 请求 |
| Jaeger OTLP 导出 | 1-3% | ~15MB | ~2KB/span | 异步批量发送 |
| **总计** | **3-9%** | **~45MB** | **低** | 开发环境可接受 |

### 10.4 生产环境优化建议

当项目进入生产环境时，建议进行以下调整：

#### 10.4.1 Tracing 采样率

降低采样率以减少性能开销：

```yaml
management:
  tracing:
    sampling:
      probability: 0.1  # 10% 采样
```

#### 10.4.2 Jaeger 存储

切换到 Elasticsearch 持久化存储：

```yaml
jaeger:
  environment:
    - SPAN_STORAGE_TYPE=elasticsearch
    - ES_SERVER_URLS=http://elasticsearch:9200
```

#### 10.4.3 Prometheus 数据保留

延长数据保留时间：

```yaml
command:
  - '--storage.tsdb.retention.time=30d'
```

#### 10.4.4 Alertmanager 集成

配置 Alertmanager 实现告警通知：

```yaml
alertmanager:
  image: prom/alertmanager:latest
  ports:
    - "9093:9093"
  volumes:
    - ./docker/alertmanager/config.yml:/etc/alertmanager/config.yml
```

在 `prometheus.yml` 中添加：

```yaml
alerting:
  alertmanagers:
    - static_configs:
        - targets: ['alertmanager:9093']
```

## 11. 总结

### 11.1 实施范围

本设计方案将实现：

✅ **完整的 Metrics 监控**
- JVM 指标（内存、GC、线程）
- HTTP 指标（QPS、延迟、错误率）
- 业务指标（订单、支付、库存、Outbox）
- 基础设施指标（数据库连接池、Redis）

✅ **分布式 Tracing**
- 从 Gateway 到下游服务的完整调用链
- 数据库、Redis、消息队列自动追踪
- Seata 事务、秒杀、Outbox 手动追踪
- W3C Trace Context 标准传播

✅ **可视化**
- 7 个服务的 Grafana 仪表板
- 统一的布局和指标展示
- Jaeger UI 调用链查询

✅ **告警**
- 6 个告警规则文件
- 覆盖服务健康、性能、业务异常
- 基础告警（无通知渠道）

✅ **系统集成**
- 与 ELK 日志系统协同（trace ID 关联）
- 保留现有 traceId 机制（改为从 OpenTelemetry 获取）
- 开发环境优化（7 天数据保留、内存存储）

### 11.2 技术栈

| 层次 | 技术 | 版本 |
|------|------|------|
| 指标采集 | Micrometer | Spring Boot 3.2.5 内置 |
| 指标格式 | Prometheus | - |
| 指标存储 | Prometheus | latest |
| 可视化 | Grafana | latest |
| 追踪桥接 | Micrometer Tracing | 1.2.5 |
| 追踪协议 | OpenTelemetry | 1.36.0 |
| 追踪后端 | Jaeger | 1.55 |
| 告警 | Prometheus Rules | - |

### 11.3 预期效果

实施完成后，团队将获得：

1. **实时监控能力**：通过 Grafana 仪表板实时查看所有服务的健康状况
2. **问题定位能力**：通过 Jaeger 快速定位分布式调用链中的性能瓶颈
3. **告警响应能力**：通过 Prometheus 告警规则及时发现异常
4. **日志关联能力**：通过 trace ID 关联日志和调用链
5. **学习价值**：理解可观测性三支柱（Metrics、Tracing、Logging）的实践

### 11.4 后续扩展方向

1. **Alertmanager 集成**：添加邮件、钉钉、Slack 等通知渠道
2. **自定义 Exporter**：为 Seata、Sentinel、Nacos 添加专用 exporter
3. **分布式追踪采样策略**：实现基于错误率、延迟的智能采样
4. **SLO/SLI 监控**：定义服务等级目标并监控
5. **成本优化**：长期存储迁移到 Thanos 或 VictoriaMetrics

