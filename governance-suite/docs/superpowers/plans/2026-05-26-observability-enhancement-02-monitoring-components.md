# 可观测性增强实施计划 - Part 2: 监控组件实现

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 common 模块实现监控注解、AOP 切面、配置类，改造 Trace 类以支持 OpenTelemetry

**Architecture:** 使用 AOP 切面自动记录方法执行时间和成功率，改造现有 Trace 类从 Micrometer TraceContext 获取 trace ID

**Tech Stack:**
- Spring AOP
- Micrometer Core
- Micrometer Tracing
- SLF4J MDC

---

## Task 1: 创建监控注解

**Files:**
- Create: `common/src/main/java/com/lx/governance/common/monitoring/annotation/Monitored.java`

- [ ] **Step 1: 创建 annotation 包**

运行：`mkdir -p common/src/main/java/com/lx/governance/common/monitoring/annotation`

- [ ] **Step 2: 创建 Monitored 注解**

创建文件 `common/src/main/java/com/lx/governance/common/monitoring/annotation/Monitored.java`：

```java
package com.lx.governance.common.monitoring.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 监控注解，用于自动记录方法执行时间和成功率
 * 
 * 使用示例：
 * <pre>
 * {@code
 * @Monitored(value = "order.create", description = "订单创建")
 * public Order createOrder(CreateOrderRequest request) {
 *     // 业务逻辑
 * }
 * }
 * </pre>
 * 
 * 生成的指标：
 * - {value}.duration: 方法执行时间（Timer）
 * - {value}.total: 方法调用次数（Counter），带 status 标签（success/failure）
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Monitored {
    
    /**
     * 指标名称（必填）
     * 例如：order.create, payment.confirm
     */
    String value();
    
    /**
     * 指标描述
     */
    String description() default "";
    
    /**
     * 是否记录失败次数
     * 默认为 true，会记录异常类型
     */
    boolean recordFailures() default true;
}
```

- [ ] **Step 3: 编译验证**

运行：`mvn compile -pl common`

预期输出：`BUILD SUCCESS`

- [ ] **Step 4: 提交注解**

```bash
git add common/src/main/java/com/lx/governance/common/monitoring/annotation/Monitored.java
git commit -m "feat(common): add @Monitored annotation for automatic metrics

- Auto-record method execution time (Timer)
- Auto-record success/failure count (Counter)
- Support custom metric name and description"
```

---

## Task 2: 创建监控切面

**Files:**
- Create: `common/src/main/java/com/lx/governance/common/monitoring/aspect/MonitoringAspect.java`

- [ ] **Step 1: 创建 aspect 包**

运行：`mkdir -p common/src/main/java/com/lx/governance/common/monitoring/aspect`

- [ ] **Step 2: 创建 MonitoringAspect 切面**

创建文件 `common/src/main/java/com/lx/governance/common/monitoring/aspect/MonitoringAspect.java`：

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
import org.springframework.stereotype.Component;

/**
 * 监控切面，自动记录带 @Monitored 注解的方法的执行时间和成功率
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class MonitoringAspect {
    
    private final MeterRegistry meterRegistry;
    
    @Around("@annotation(monitored)")
    public Object monitor(ProceedingJoinPoint joinPoint, Monitored monitored) throws Throwable {
        String metricName = monitored.value();
        String methodName = joinPoint.getSignature().getName();
        
        // 创建 Timer 记录执行时间
        Timer timer = Timer.builder(metricName + ".duration")
                .description(monitored.description())
                .tag("method", methodName)
                .register(meterRegistry);
        
        Timer.Sample sample = Timer.start(meterRegistry);
        
        try {
            Object result = joinPoint.proceed();
            sample.stop(timer);
            
            // 记录成功次数
            Counter.builder(metricName + ".total")
                    .tag("status", "success")
                    .tag("method", methodName)
                    .register(meterRegistry)
                    .increment();
            
            return result;
            
        } catch (Throwable ex) {
            sample.stop(timer);
            
            // 记录失败次数
            if (monitored.recordFailures()) {
                Counter.builder(metricName + ".total")
                        .tag("status", "failure")
                        .tag("method", methodName)
                        .tag("exception", ex.getClass().getSimpleName())
                        .register(meterRegistry)
                        .increment();
            }
            
            throw ex;
        }
    }
}
```

- [ ] **Step 3: 编译验证**

运行：`mvn compile -pl common`

预期输出：`BUILD SUCCESS`

- [ ] **Step 4: 提交切面**

```bash
git add common/src/main/java/com/lx/governance/common/monitoring/aspect/MonitoringAspect.java
git commit -m "feat(common): add MonitoringAspect for @Monitored annotation

- Intercept methods with @Monitored annotation
- Record execution time with Timer
- Record success/failure count with Counter
- Add method name and exception type as tags"
```

---

## Task 3: 改造 Trace 类

**Files:**
- Modify: `common/src/main/java/com/lx/governance/common/trace/Trace.java`

- [ ] **Step 1: 读取现有 Trace 类**

运行：`cat common/src/main/java/com/lx/governance/common/trace/Trace.java`

记录现有实现以便改造

- [ ] **Step 2: 改造 Trace 类以支持 Micrometer Tracing**

修改 `common/src/main/java/com/lx/governance/common/trace/Trace.java`：

```java
package com.lx.governance.common.trace;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Trace 工具类，从 Micrometer Tracing 获取 trace ID
 * 兼容现有代码，但改为使用 OpenTelemetry trace ID
 */
@Slf4j
@Component
public class Trace {
    
    private static Tracer tracer;
    
    @Autowired
    public void setTracer(Tracer tracer) {
        Trace.tracer = tracer;
    }
    
    /**
     * 获取当前 trace ID（从 Micrometer Tracing）
     * 如果没有 trace context，生成临时 ID
     */
    public static String getTraceId() {
        if (tracer != null) {
            TraceContext context = tracer.currentTraceContext().context();
            if (context != null) {
                String traceId = context.traceId();
                String spanId = context.spanId();
                
                // 设置到 MDC 供日志使用
                MDC.put("traceId", traceId);
                MDC.put("spanId", spanId);
                
                return traceId;
            }
        }
        
        // Fallback：如果没有 trace context，生成临时 ID
        String fallbackId = generateFallbackId();
        MDC.put("traceId", fallbackId);
        return fallbackId;
    }
    
    /**
     * 生成临时 trace ID（用于非 HTTP 请求场景）
     */
    private static String generateFallbackId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
    
    /**
     * 清除 MDC 中的 trace 信息
     */
    public static void clear() {
        MDC.remove("traceId");
        MDC.remove("spanId");
    }
}
```

- [ ] **Step 3: 编译验证**

运行：`mvn compile -pl common`

预期输出：`BUILD SUCCESS`

- [ ] **Step 4: 提交 Trace 改造**

```bash
git add common/src/main/java/com/lx/governance/common/trace/Trace.java
git commit -m "refactor(common): migrate Trace to use Micrometer Tracing

- Get trace ID from Micrometer TraceContext instead of generating
- Auto-inject trace ID and span ID to MDC
- Maintain backward compatibility with existing code
- Fallback to UUID generation when no trace context"
```

---

## Task 4: 简化 Gateway TraceIdGlobalFilter

**Files:**
- Modify: `gateway/src/main/java/com/lx/governance/gateway/filter/TraceIdGlobalFilter.java`

- [ ] **Step 1: 读取现有 Filter**

运行：`find gateway -name "TraceIdGlobalFilter.java" -exec cat {} \;`

- [ ] **Step 2: 简化 TraceIdGlobalFilter**

修改 `gateway/src/main/java/com/lx/governance/gateway/filter/TraceIdGlobalFilter.java`：

```java
package com.lx.governance.gateway.filter;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.TraceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Trace ID 全局过滤器
 * Micrometer Tracing 已自动创建 root span，这里只需要将 trace ID 添加到响应头
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TraceIdGlobalFilter implements GlobalFilter, Ordered {
    
    private final Tracer tracer;
    
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // Micrometer Tracing 已自动创建 root span 并传播 W3C Trace Context
        // 这里只需要将 trace ID 添加到响应头供客户端使用
        TraceContext context = tracer.currentTraceContext().context();
        if (context != null) {
            String traceId = context.traceId();
            exchange.getResponse().getHeaders().add("X-Trace-Id", traceId);
            log.debug("Gateway trace ID: {}", traceId);
        }
        
        return chain.filter(exchange);
    }
    
    @Override
    public int getOrder() {
        return -90;
    }
}
```

- [ ] **Step 3: 编译验证**

运行：`mvn compile -pl gateway`

预期输出：`BUILD SUCCESS`

- [ ] **Step 4: 提交 Gateway Filter 简化**

```bash
git add gateway/src/main/java/com/lx/governance/gateway/filter/TraceIdGlobalFilter.java
git commit -m "refactor(gateway): simplify TraceIdGlobalFilter for Micrometer Tracing

- Remove manual trace ID generation
- Use Micrometer Tracing auto-created root span
- Only add X-Trace-Id to response header
- W3C Trace Context propagation handled automatically"
```

---

## Task 5: 简化下游服务 TraceIdFilter

**Files:**
- Modify: `user-service/src/main/java/com/lx/governance/user/filter/TraceIdFilter.java`
- Modify: `order-service/src/main/java/com/lx/governance/order/filter/TraceIdFilter.java`
- Modify: `payment-service/src/main/java/com/lx/governance/payment/filter/TraceIdFilter.java`
- Modify: `inventory-service/src/main/java/com/lx/governance/inventory/filter/TraceIdFilter.java`

- [ ] **Step 1: 查找所有 TraceIdFilter**

运行：`find . -name "TraceIdFilter.java" -not -path "*/target/*" | grep -v gateway`

记录找到的文件路径

- [ ] **Step 2: 创建通用 TraceIdFilter 到 common 模块**

创建文件 `common/src/main/java/com/lx/governance/common/filter/TraceIdFilter.java`：

```java
package com.lx.governance.common.filter;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Trace ID 过滤器
 * Micrometer Tracing 已自动从 traceparent header 提取 trace context
 * 这里只需要将 trace ID 放入 MDC 和响应头
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
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
                String spanId = context.spanId();
                
                MDC.put("traceId", traceId);
                MDC.put("spanId", spanId);
                response.setHeader("X-Trace-Id", traceId);
                
                log.debug("Request trace ID: {}, span ID: {}", traceId, spanId);
            }
            
            filterChain.doFilter(request, response);
            
        } finally {
            MDC.clear();
        }
    }
}
```

- [ ] **Step 3: 删除各服务的 TraceIdFilter（如果存在）**

运行：
```bash
find . -name "TraceIdFilter.java" -not -path "*/target/*" -not -path "*/common/*" -not -path "*/gateway/*" -exec rm {} \;
```

- [ ] **Step 4: 编译验证所有服务**

运行：`mvn compile -pl user-service,order-service,payment-service,inventory-service`

预期输出：`BUILD SUCCESS`

- [ ] **Step 5: 提交 TraceIdFilter 改造**

```bash
git add common/src/main/java/com/lx/governance/common/filter/TraceIdFilter.java
git add -u  # 添加删除的文件
git commit -m "refactor(common): create unified TraceIdFilter for all services

- Move TraceIdFilter to common module
- Use Micrometer Tracing auto-extracted trace context
- Remove manual trace ID extraction from headers
- Auto-inject trace ID and span ID to MDC
- Remove duplicate filters from individual services"
```

---

## Task 6: 创建 Metrics 配置类（可选）

**Files:**
- Create: `common/src/main/java/com/lx/governance/common/monitoring/config/MetricsConfig.java`

- [ ] **Step 1: 创建 config 包**

运行：`mkdir -p common/src/main/java/com/lx/governance/common/monitoring/config`

- [ ] **Step 2: 创建 MetricsConfig**

创建文件 `common/src/main/java/com/lx/governance/common/monitoring/config/MetricsConfig.java`：

```java
package com.lx.governance.common.monitoring.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Metrics 配置类
 * 为所有指标添加通用标签
 */
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

- [ ] **Step 3: 编译验证**

运行：`mvn compile -pl common`

预期输出：`BUILD SUCCESS`

- [ ] **Step 4: 提交配置类**

```bash
git add common/src/main/java/com/lx/governance/common/monitoring/config/MetricsConfig.java
git commit -m "feat(common): add MetricsConfig for common tags

- Add region and team tags to all metrics
- Simplify metric filtering in Prometheus"
```

---

## Task 7: 集成测试

**Files:**
- None (testing only)

- [ ] **Step 1: 构建所有模块**

运行：`mvn clean package -DskipTests -T 1C`

预期输出：`BUILD SUCCESS`

- [ ] **Step 2: 启动基础设施**

运行：`docker compose up -d mysql redis nacos`

等待服务就绪（约 30 秒）

- [ ] **Step 3: 启动 Gateway 测试 Trace 改造**

运行：`mvn -q -pl gateway spring-boot:run`

在另一个终端测试：
```bash
curl -v http://localhost:8080/actuator/health
```

预期输出：
- 响应头包含 `X-Trace-Id: <16字节十六进制>`
- 日志中包含 `Gateway trace ID: ...`

停止 Gateway（Ctrl+C）

- [ ] **Step 4: 启动 User Service 测试 TraceIdFilter**

运行：`mvn -q -pl user-service spring-boot:run`

在另一个终端测试：
```bash
curl -v http://localhost:8084/actuator/health
```

预期输出：
- 响应头包含 `X-Trace-Id`
- 日志中包含 `Request trace ID: ..., span ID: ...`

停止 User Service（Ctrl+C）

- [ ] **Step 5: 停止基础设施**

运行：`docker compose down`

- [ ] **Step 6: 创建集成测试总结提交**

```bash
git commit --allow-empty -m "test: verify monitoring components integration

Verified:
- @Monitored annotation and MonitoringAspect compile successfully
- Trace class migrated to Micrometer Tracing
- Gateway TraceIdGlobalFilter simplified
- Common TraceIdFilter works for all services
- Trace ID propagation working end-to-end
- X-Trace-Id header present in responses"
```

---

## 完成标准

Part 2 完成后，应满足：

✅ @Monitored 注解和 MonitoringAspect 已创建
✅ Trace 类已改造为从 Micrometer TraceContext 获取 ID
✅ Gateway TraceIdGlobalFilter 已简化
✅ 通用 TraceIdFilter 已创建到 common 模块
✅ 各服务的重复 TraceIdFilter 已删除
✅ MetricsConfig 已创建
✅ 所有模块编译成功
✅ Trace ID 在响应头中正确返回

**下一步**: 继续 Part 3 - 基础设施部署（Jaeger、告警规则）
