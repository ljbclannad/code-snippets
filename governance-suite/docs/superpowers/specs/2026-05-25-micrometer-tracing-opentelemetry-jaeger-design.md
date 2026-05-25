# Micrometer Tracing + OpenTelemetry + Jaeger 集成设计

## 1. 目标

将 governance-suite 项目的自定义 traceId 追踪机制完全迁移到标准的 OpenTelemetry 分布式追踪方案，使用 Micrometer Tracing 作为抽象层，Jaeger 作为追踪后端。

## 2. 当前状态

### 2.1 现有追踪实现
- `common` 模块的 `Trace` 类：通过 MDC 管理自定义 traceId
- `TraceIdGlobalFilter`（Gateway）：生成/传递 `X-Trace-Id` 请求头
- `TraceIdFilter`（下游服务）：从请求头提取 traceId 并设置到 MDC
- `ApiResponse` 中包含 traceId 字段返回给客户端

### 2.2 技术栈
- Spring Boot 3.2.5
- Spring Cloud Gateway
- OpenFeign
- 已有基础设施：Prometheus、Grafana、ELK

## 3. 架构设计

### 3.1 追踪数据流

```
客户端请求
    ↓
Gateway (生成 trace context)
    ↓
下游服务 (传播 trace context)
    ↓
OpenFeign / JDBC / Redis 调用 (自动埋点)
    ↓
Micrometer Tracing → OpenTelemetry SDK
    ↓
OTLP Exporter (gRPC)
    ↓
Jaeger Collector (4317)
    ↓
Jaeger Storage (内存/Elasticsearch)
    ↓
Jaeger UI (16686)
```

### 3.2 核心组件

| 组件 | 作用 | 配置位置 |
|------|------|----------|
| Micrometer Tracing | Spring Boot 追踪抽象层 | 所有服务模块 |
| OpenTelemetry Bridge | 连接 Micrometer 和 OTel SDK | 所有服务模块 |
| OTLP Exporter | 通过 gRPC 发送 span 到 Jaeger | application.yml |
| Jaeger All-in-One | 接收、存储、展示追踪数据 | docker-compose.yml |

### 3.3 自动埋点范围

Spring Boot 3.x + Micrometer Tracing 自动支持：

- **HTTP 服务端**：Spring MVC / WebFlux 入口请求
- **HTTP 客户端**：RestTemplate、WebClient、OpenFeign
- **消息队列**：RabbitMQ（Spring AMQP）
- **数据库**：JDBC（通过 DataSource 代理）
- **Redis**：Lettuce 客户端
- **异步任务**：`@Async`、`@Scheduled`

**不自动支持（需手动埋点）：**
- Seata 全局事务边界
- 自定义业务逻辑（如秒杀 Lua 脚本执行）
- ShardingSphere 分片路由决策

## 4. 依赖管理

### 4.1 父 POM 依赖管理

在 `pom.xml` 的 `<dependencyManagement>` 中添加：

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bom</artifactId>
    <version>1.2.5</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

### 4.2 Common 模块依赖

在 `common/pom.xml` 中添加：

```xml
<!-- Micrometer Tracing -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>

<!-- OpenTelemetry Exporter -->
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
```

### 4.3 服务模块依赖

Gateway、order-service、payment-service、inventory-service、user-service、outbox-consumer 的 `pom.xml` 中添加：

```xml
<!-- OpenFeign 追踪 -->
<dependency>
    <groupId>io.github.openfeign</groupId>
    <artifactId>feign-micrometer</artifactId>
</dependency>

<!-- JDBC 追踪 -->
<dependency>
    <groupId>net.ttddyy.observation</groupId>
    <artifactId>datasource-micrometer-spring-boot</artifactId>
    <version>1.0.3</version>
</dependency>
```

## 5. 配置设计

### 5.1 统一配置（所有服务）

在每个服务的 `application.yml` 中添加：

```yaml
management:
  tracing:
    enabled: true
    sampling:
      probability: 1.0  # 学习项目全量采样，生产环境建议 0.1
  otlp:
    tracing:
      endpoint: http://localhost:4317  # Jaeger OTLP gRPC 端点
      timeout: 10s
      compression: gzip

logging:
  pattern:
    level: "%5p [${spring.application.name:},%X{traceId:-},%X{spanId:-}]"
```

### 5.2 Gateway 特殊配置

Gateway 使用 WebFlux，需要确保响应式链路传播：

```yaml
spring:
  cloud:
    gateway:
      observability:
        enabled: true  # 启用 Gateway 可观测性
```

### 5.3 OpenFeign 配置

在使用 OpenFeign 的服务中（order-service、user-service）：

```yaml
feign:
  micrometer:
    enabled: true
```

### 5.4 JDBC 追踪配置

在使用数据库的服务中：

```yaml
spring:
  datasource:
    hikari:
      data-source-properties:
        observationEnabled: true  # 启用 HikariCP 观测
```

## 6. 代码迁移

### 6.1 移除自定义追踪代码

**删除以下文件：**
- `common/src/main/java/com/lx/governance/common/trace/Trace.java`
- `common/src/main/java/com/lx/governance/common/web/TraceIdFilter.java`
- `gateway/src/main/java/com/lx/governance/gateway/filter/TraceIdGlobalFilter.java`

**删除相关配置类：**
- 搜索所有注册 `TraceIdFilter` 的 `@Bean` 方法并删除

### 6.2 修改 ApiResponse

将 `ApiResponse` 中的 `traceId` 字段改为从 OTel 获取：

```java
// 修改前
private String traceId = Trace.getOrCreate();

// 修改后
private String traceId;

// 在构造方法或静态工厂方法中注入 Tracer
public static <T> ApiResponse<T> success(T data, Tracer tracer) {
    ApiResponse<T> response = new ApiResponse<>();
    response.success = true;
    response.code = 200;
    response.message = "success";
    response.data = data;
    
    // 从当前 span 获取 trace ID
    Span currentSpan = tracer.currentSpan();
    if (currentSpan != null) {
        response.traceId = currentSpan.context().traceId();
    }
    
    return response;
}
```

**替代方案（推荐）：** 使用 MDC 自动注入

OpenTelemetry 会自动将 `traceId` 和 `spanId` 写入 MDC，可以直接读取：

```java
import org.slf4j.MDC;

private String traceId = MDC.get("traceId");
```

### 6.3 GlobalExceptionHandler 适配

修改 `GlobalExceptionHandler` 中获取 traceId 的方式：

```java
// 修改前
String traceId = Trace.getOrCreate();

// 修改后
String traceId = MDC.get("traceId");
if (traceId == null) {
    traceId = "unknown";
}
```

### 6.4 手动埋点（可选）

对于需要细粒度追踪的业务逻辑（如秒杀流程），可以手动创建 span：

```java
@Service
public class SeckillService {
    private final Tracer tracer;
    
    public SeckillService(Tracer tracer) {
        this.tracer = tracer;
    }
    
    public void processSeckill(Long skuId, Long userId) {
        Span span = tracer.nextSpan().name("seckill.lua-script").start();
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            span.tag("sku.id", String.valueOf(skuId));
            span.tag("user.id", String.valueOf(userId));
            
            // 执行 Lua 脚本
            redisTemplate.execute(luaScript, ...);
            
            span.event("lua-script-executed");
        } catch (Exception e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
```

## 7. 基础设施配置

### 7.1 Docker Compose 添加 Jaeger

在 `docker-compose.yml` 中添加：

```yaml
jaeger:
  image: jaegertracing/all-in-one:1.57
  container_name: gov-jaeger
  restart: unless-stopped
  environment:
    - COLLECTOR_OTLP_ENABLED=true
    - SPAN_STORAGE_TYPE=memory
  ports:
    - "16686:16686"  # Jaeger UI
    - "4317:4317"    # OTLP gRPC
    - "4318:4318"    # OTLP HTTP
  healthcheck:
    test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:16686/"]
    interval: 30s
    timeout: 10s
    retries: 3
```

### 7.2 Grafana 集成（可选）

Grafana 可以通过 Jaeger 数据源查询追踪数据：

在 `docker/grafana/provisioning/datasources/` 中添加 `jaeger.yml`：

```yaml
apiVersion: 1

datasources:
  - name: Jaeger
    type: jaeger
    access: proxy
    url: http://jaeger:16686
    isDefault: false
    editable: true
```

## 8. 验证方案

### 8.1 启动顺序

```bash
# 1. 启动基础设施（包括新增的 Jaeger）
docker compose up -d

# 2. 等待 Jaeger 健康检查通过
docker compose ps jaeger

# 3. 构建所有服务
mvn -T 1C clean package -DskipTests

# 4. 启动服务（每个服务一个终端）
mvn -q -pl gateway spring-boot:run
mvn -q -pl order-service spring-boot:run
mvn -q -pl payment-service spring-boot:run
mvn -q -pl inventory-service spring-boot:run
mvn -q -pl user-service spring-boot:run
mvn -q -pl outbox-consumer spring-boot:run
```

### 8.2 验证步骤

**1. 检查服务启动日志**

确认日志中包含 OTel 初始化信息：

```
INFO [order-service,64f8a3b2c1e9d7f0,a1b2c3d4e5f6g7h8] ...
```

格式：`[服务名,traceId,spanId]`

**2. 发起测试请求**

```bash
# 注册用户
curl -X POST http://localhost:8080/api/user/register \
  -H "Content-Type: application/json" \
  -d '{"username":"test","password":"123456"}'

# 登录获取 token
TOKEN=$(curl -X POST http://localhost:8080/api/user/login \
  -H "Content-Type: application/json" \
  -d '{"username":"test","password":"123456"}' | jq -r '.data.token')

# 创建订单（触发分布式事务）
curl -X POST http://localhost:8080/api/order/create \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"skuId":1,"quantity":2}'
```

**3. 查看 Jaeger UI**

访问 http://localhost:16686

- 选择 Service：`gateway`
- 点击 "Find Traces"
- 应该看到完整的调用链：
  ```
  gateway
    └─ order-service
        ├─ inventory-service (库存扣减)
        ├─ payment-service (支付准备)
        ├─ MySQL (写入订单)
        └─ MySQL (写入 outbox)
  ```

**4. 验证 span 标签**

点击某个 trace，检查 span 是否包含：
- `http.method`、`http.url`、`http.status_code`
- `db.system=mysql`、`db.statement`（SQL 语句）
- `messaging.system=rabbitmq`（如果触发了消息发送）

**5. 验证错误追踪**

触发失败场景：

```bash
# 启动库存服务时注入失败
LAB_FAIL_INVENTORY_DEDUCT=true mvn -q -pl inventory-service spring-boot:run

# 再次创建订单
curl -X POST http://localhost:8080/api/order/create \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"skuId":1,"quantity":2}'
```

在 Jaeger UI 中应该看到：
- 失败的 span 标记为红色
- `error=true` 标签
- 异常堆栈信息

### 8.3 性能验证

检查追踪对性能的影响：

```bash
# 无追踪基线（理论值，实际无法测试）
ab -n 1000 -c 10 http://localhost:8080/api/user/login

# 有追踪
ab -n 1000 -c 10 http://localhost:8080/api/user/login
```

预期：P99 延迟增加 < 5ms（全量采样），< 1ms（10% 采样）

## 9. 迁移风险与缓解

### 9.1 风险

| 风险 | 影响 | 概率 | 缓解措施 |
|------|------|------|----------|
| MDC traceId 格式变化 | 日志解析脚本失效 | 高 | OTel 的 traceId 是 32 字符十六进制，与原 UUID 格式兼容 |
| ApiResponse.traceId 为空 | 客户端无法关联请求 | 中 | 在 GlobalExceptionHandler 中添加降级逻辑 |
| OpenFeign 追踪失效 | 跨服务调用链断裂 | 低 | 集成测试验证 |
| Jaeger 内存溢出 | 追踪数据丢失 | 中 | 学习项目可接受，生产需配置持久化存储 |

### 9.2 回滚方案

如果迁移失败，可以快速回滚：

1. 恢复被删除的 `Trace`、`TraceIdFilter`、`TraceIdGlobalFilter` 文件
2. 移除 Micrometer Tracing 依赖
3. 停止 Jaeger 容器

建议在 Git 中创建迁移分支，保留回滚点。

## 10. 后续优化

### 10.1 采样策略

生产环境建议使用动态采样：

```yaml
management:
  tracing:
    sampling:
      probability: 0.1  # 10% 采样率
```

或基于请求路径的采样：

```java
@Bean
public Sampler customSampler() {
    return Sampler.traceIdRatioBased(0.1)  // 默认 10%
        .or(Sampler.alwaysOn()  // 错误请求全量采样
            .when(span -> span.getStatus() == StatusCode.ERROR));
}
```

### 10.2 持久化存储

Jaeger 生产环境建议使用 Elasticsearch 存储：

```yaml
jaeger:
  environment:
    - SPAN_STORAGE_TYPE=elasticsearch
    - ES_SERVER_URLS=http://elasticsearch:9200
  depends_on:
    - elasticsearch
```

### 10.3 Seata 集成

为 Seata 全局事务添加追踪：

```java
@Aspect
@Component
public class SeataTracingAspect {
    private final Tracer tracer;
    
    @Around("@annotation(io.seata.spring.annotation.GlobalTransactional)")
    public Object traceGlobalTransaction(ProceedingJoinPoint pjp) throws Throwable {
        Span span = tracer.nextSpan().name("seata.global-transaction").start();
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            String xid = RootContext.getXID();
            if (xid != null) {
                span.tag("seata.xid", xid);
            }
            return pjp.proceed();
        } catch (Throwable e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
```

### 10.4 关联日志与追踪

在 Logstash 配置中添加 traceId 字段，实现日志与追踪的关联：

```ruby
filter {
  grok {
    match => { "message" => "\[%{DATA:service},%{DATA:trace_id},%{DATA:span_id}\]" }
  }
}
```

在 Jaeger UI 中点击 "Logs" 标签，可以跳转到 Kibana 查看该 trace 的所有日志。

## 11. 总结

本设计将 governance-suite 从自定义追踪完全迁移到 OpenTelemetry 标准方案：

- **移除**：自定义 `Trace` 类、`TraceIdFilter`、`TraceIdGlobalFilter`
- **添加**：Micrometer Tracing + OpenTelemetry + Jaeger
- **自动埋点**：HTTP、OpenFeign、JDBC、Redis、RabbitMQ
- **手动埋点**：Seata 事务、秒杀流程（可选）
- **验证**：通过 Jaeger UI 查看完整调用链

迁移后，项目将具备标准的分布式追踪能力，便于理解微服务间的调用关系和性能瓶颈。
