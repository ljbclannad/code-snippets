# 配置中心设计方案

**日期**: 2026-05-20  
**作者**: Claude  
**状态**: 已批准

## 1. 概述

### 1.1 背景

当前项目的配置管理存在以下问题：
- 配置硬编码在各服务的 `application.yml` 中
- 配置变更需要重启服务
- 敏感配置（数据库密码、Token 密钥）明文存储
- 多环境配置管理困难

### 1.2 目标

实现完整的配置中心能力：
1. **动态配置热更新** - 业务开关、限流阈值等配置修改后无需重启
2. **多环境配置管理** - 统一管理 dev/test/prod 环境配置
3. **敏感配置加密** - 数据库密码、API Key 等加密存储
4. **配置变更监听** - 配置变更时执行特定逻辑（清理缓存、调整线程池等）

### 1.3 范围

**包含**：
- 分布式系统的 6 个核心服务：gateway、order-service、payment-service、inventory-service、user-service、outbox-consumer

**不包含**：
- AI 系统（ai-chat-service、user-profile-service、vector-memory-service）
- 医生助手 Agent（doctor-assistant-agent）

### 1.4 技术选型

**方案**: Nacos Config（标准集成）

**理由**：
- 复用现有 Nacos 注册中心，无需额外部署
- Spring Cloud Alibaba 原生支持，集成简单
- 支持配置版本管理、回滚、灰度发布
- Nacos 2.5.1 支持原生加密功能

## 2. 整体架构

### 2.1 配置层次结构

```
Nacos Config Server (localhost:8848)
├── common.yml (共享配置，所有服务加载)
│   ├── MySQL 连接信息
│   ├── Redis 连接信息
│   ├── RabbitMQ 连接信息
│   ├── Nacos 注册中心配置
│   └── 通用日志配置
│
├── gateway.yml (网关特有配置)
│   ├── 路由规则
│   ├── 限流阈值 (支持热更新)
│   ├── 黑名单 IP (支持热更新)
│   └── Token 密钥 (加密存储)
│
├── order-service.yml
│   ├── Seata 事务配置
│   ├── ShardingSphere 分片规则
│   ├── 线程池配置
│   └── 业务开关 (支持热更新)
│
├── payment-service.yml
│   ├── ShardingSphere 分片规则
│   └── 支付超时配置 (支持热更新)
│
├── inventory-service.yml
│   └── 库存扣减重试次数 (支持热更新)
│
├── user-service.yml
│   └── Token TTL 配置 (支持热更新)
│
└── outbox-consumer.yml
    ├── 轮询间隔 (支持热更新)
    └── 重试策略 (支持热更新)
```

### 2.2 配置加载流程

```
服务启动
  ↓
读取 bootstrap.yml (本地)
  ↓
连接 Nacos Config Server
  ↓
加载 common.yml (共享配置)
  ↓
加载 {service-name}.yml (服务配置)
  ↓
合并配置 (服务配置优先级高于共享配置)
  ↓
解密敏感配置 (Nacos 服务端解密)
  ↓
启动 Spring Boot 应用
  ↓
监听配置变更 (自动刷新 @RefreshScope Bean)
```

### 2.3 配置优先级

```
bootstrap.yml (本地) 
  ↓ (优先级低)
common.yml (Nacos 共享)
  ↓ (优先级中)
{service-name}.yml (Nacos 服务配置)
  ↓ (优先级高)
环境变量 / 启动参数
  ↓ (优先级最高)
```

## 3. 配置分类与热更新策略

### 3.1 配置分类

#### 静态配置（不支持热更新，需重启）

- 服务端口、服务名
- 数据库连接池配置（URL、用户名、密码、最大连接数）
- Redis 连接信息
- RabbitMQ 连接信息
- Seata 事务配置
- ShardingSphere 分片规则
- MyBatis Mapper 路径

#### 动态配置（支持热更新）

- Gateway 限流阈值（`gateway.rate-limit.replenish-rate`）
- Gateway 黑名单 IP（`gateway.block-ip`）
- Sentinel 熔断规则（`gateway.sentinel.order-list.qps`）
- 业务开关（如秒杀开关、降级开关）
- 超时时间（OpenFeign 超时、业务超时）
- Outbox 轮询间隔（`outbox.poll-interval-ms`）
- 重试策略（最大重试次数、退避时间）
- 线程池动态调整（核心线程数、最大线程数）

### 3.2 热更新实现机制

#### 配置类示例

```java
@Component
@RefreshScope  // 支持配置热更新
@ConfigurationProperties(prefix = "gateway.rate-limit")
public class RateLimitConfig {
    private int replenishRate = 5;
    private int burstCapacity = 10;
    
    // getters and setters
}
```

#### 刷新流程

```
Nacos 控制台修改配置
  ↓
Nacos Server 推送变更通知
  ↓
Spring Cloud 客户端接收通知
  ↓
销毁旧的 @RefreshScope Bean
  ↓
重新创建 Bean（注入新配置）
  ↓
业务代码自动使用新配置
```

#### 注意事项

- 只有标注 `@RefreshScope` 的 Bean 会被刷新
- 配置类需要用 `@ConfigurationProperties` 绑定
- 不要在 `@RefreshScope` Bean 中缓存配置值到成员变量

## 4. 敏感配置加密方案

### 4.1 Nacos 2.x 原生加密

#### 加密流程

```
1. 在 Nacos 控制台配置加密插件
   ↓
2. 创建/编辑配置时，标记敏感字段
   ↓
3. Nacos Server 使用 AES 加密存储
   ↓
4. 客户端请求配置时，Nacos Server 自动解密
   ↓
5. 应用接收到明文配置
```

#### 需要加密的配置项

- 数据库密码：`spring.shardingsphere.datasource.ds0.password`
- Redis 密码（如果有）：`spring.data.redis.password`
- RabbitMQ 密码：`spring.rabbitmq.password`
- Token 密钥：`app.auth.token-secret`
- 第三方 API Key（如有）

### 4.2 Nacos 加密配置步骤

#### 1. Nacos Server 配置加密插件

在 `docker-compose.yml` 中添加 Nacos 加密配置：

```yaml
nacos:
  image: nacos/nacos-server:v2.5.1
  container_name: gov-nacos
  environment:
    # 启用配置加密
    NACOS_CONFIG_ENCRYPTION_ENABLED: true
    # 加密密钥（生产环境应从外部注入）
    NACOS_CONFIG_ENCRYPTION_KEY: ${NACOS_ENCRYPTION_KEY:-governance-suite-encryption-key-2024}
```

#### 2. 在 Nacos 控制台标记敏感字段

创建配置时，在配置内容中使用特殊标记：

```yaml
spring:
  shardingsphere:
    datasource:
      ds0:
        password: cipher(root)  # cipher() 标记需要加密
```

#### 3. 客户端无需改动

应用代码无需感知加密，直接读取配置：

```java
@Value("${spring.shardingsphere.datasource.ds0.password}")
private String dbPassword;  // 自动获得解密后的明文
```

### 4.3 密钥管理

**开发环境**：
- 密钥硬编码在 `docker-compose.yml` 中
- 方便本地调试

**生产环境**：
- 密钥通过环境变量注入：`NACOS_ENCRYPTION_KEY`
- 不提交到代码仓库
- 由运维团队管理

## 5. 配置文件组织

### 5.1 bootstrap.yml（各服务本地文件）

每个服务需要创建 `bootstrap.yml` 作为引导配置：

```yaml
spring:
  application:
    name: order-service  # 服务名
  cloud:
    nacos:
      config:
        server-addr: ${NACOS_ADDR:localhost:8848}
        namespace: ${NACOS_NAMESPACE:}  # 空为 public，可按环境隔离
        group: DEFAULT_GROUP
        file-extension: yml
        refresh-enabled: true  # 启用自动刷新
        
        # 共享配置
        shared-configs:
          - data-id: common.yml
            group: DEFAULT_GROUP
            refresh: true  # common.yml 也支持刷新
        
        # 扩展配置（可选，用于多环境）
        extension-configs:
          - data-id: common-${spring.profiles.active}.yml
            group: DEFAULT_GROUP
            refresh: true
```

### 5.2 Nacos 配置文件内容

#### common.yml（共享配置）

```yaml
# MySQL 配置
mysql:
  host: ${MYSQL_HOST:localhost}
  port: ${MYSQL_PORT:3306}
  username: ${MYSQL_USER:root}
  password: cipher(root)  # 加密

# Redis 配置
redis:
  host: ${REDIS_HOST:localhost}
  port: ${REDIS_PORT:36379}

# RabbitMQ 配置
rabbitmq:
  host: ${RABBIT_HOST:localhost}
  port: ${RABBIT_PORT:5672}
  username: ${RABBIT_USER:guest}
  password: cipher(guest)  # 加密

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

#### gateway.yml（网关配置）

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
    token-secret: cipher(governance-suite-dev-secret)  # 加密
    token-ttl-seconds: 7200
```

#### order-service.yml（订单服务配置）

```yaml
server:
  port: 8081

spring:
  application:
    name: order-service
  shardingsphere:
    datasource:
      names: ds0
      ds0:
        type: com.zaxxer.hikari.HikariDataSource
        driver-class-name: com.mysql.cj.jdbc.Driver
        jdbc-url: jdbc:mysql://${mysql.host}:${mysql.port}/order_db?useSSL=false&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
        username: ${mysql.username}
        password: ${mysql.password}  # 从 common.yml 继承，已加密
    rules:
      sharding:
        tables:
          t_order:
            actual-data-nodes: ds0.t_order_${0..1}
            table-strategy:
              standard:
                sharding-column: order_no
                sharding-algorithm-name: order-inline
        sharding-algorithms:
          order-inline:
            type: INLINE
            props:
              algorithm-expression: t_order_${Math.abs(order_no.hashCode()) % 2}

# Seata 配置
seata:
  enabled: true
  application-id: ${spring.application.name}
  tx-service-group: order_tx_group
  service:
    vgroup-mapping:
      order_tx_group: default
    grouplist:
      default: ${SEATA_ADDR:localhost:8091}

# 动态配置（支持热更新）
app:
  thread-pool:
    io:
      core-size: 32
      max-size: 64
  business:
    seckill-enabled: true  # 秒杀开关
    order-timeout-seconds: 1800  # 订单超时时间
```

## 6. 实现细节

### 6.1 依赖管理

各服务 `pom.xml` 添加依赖：

```xml
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
</dependency>

<!-- 支持 bootstrap.yml -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-bootstrap</artifactId>
</dependency>
```

### 6.2 配置类改造

#### 1. 创建动态配置类

```java
// gateway 限流配置
@Component
@RefreshScope
@ConfigurationProperties(prefix = "gateway.rate-limit")
@Data
public class RateLimitProperties {
    private int replenishRate = 5;
    private int burstCapacity = 10;
}

// order-service 业务配置
@Component
@RefreshScope
@ConfigurationProperties(prefix = "app.business")
@Data
public class BusinessProperties {
    private boolean seckillEnabled = true;
    private int orderTimeoutSeconds = 1800;
}

// order-service 线程池配置
@Component
@RefreshScope
@ConfigurationProperties(prefix = "app.thread-pool.io")
@Data
public class ThreadPoolProperties {
    private int coreSize = 32;
    private int maxSize = 64;
    private int queueCapacity = 2000;
}
```

#### 2. 使用配置类

```java
@Service
public class OrderAppService {
    private final BusinessProperties businessProperties;
    
    public OrderAppService(BusinessProperties businessProperties) {
        this.businessProperties = businessProperties;
    }
    
    public Map<String, String> createV2(CreateOrderRequest req, String requestId) {
        // 动态读取秒杀开关
        if (!businessProperties.isSeckillEnabled()) {
            throw new IllegalStateException("秒杀功能已关闭");
        }
        // ... 秒杀逻辑
    }
}
```

### 6.3 配置刷新验证

通过 Actuator 端点查看配置：

```bash
# 查看当前配置
curl http://localhost:8081/actuator/env

# 手动触发刷新（如果自动刷新失败）
curl -X POST http://localhost:8081/actuator/refresh
```

## 7. 配置变更监听机制

### 7.1 通用配置变更监听器

```java
@Component
@Slf4j
public class ConfigChangeListener {
    
    @EventListener
    public void onRefresh(RefreshScopeRefreshedEvent event) {
        log.info("配置刷新事件触发: scope={}", event.getName());
        // 记录配置变更到审计日志
        auditConfigChange(event);
    }
    
    private void auditConfigChange(RefreshScopeRefreshedEvent event) {
        // 记录变更时间、变更范围
        log.info("配置变更审计: time={}, scope={}", 
            LocalDateTime.now(), event.getName());
    }
}
```

### 7.2 Gateway 限流配置变更监听

```java
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
        
        // 可选：通知监控系统
        notifyMonitoring("rate-limit-config-changed", rateLimitProperties);
    }
    
    private void notifyMonitoring(String event, Object data) {
        // 发送到监控系统（如 Prometheus Pushgateway）
        log.info("监控通知: event={}, data={}", event, data);
    }
}
```

### 7.3 Order Service 业务开关监听

```java
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
            // 秒杀关闭时，清理 Redis 中的秒杀数据
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

### 7.4 线程池动态调整监听

```java
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
        
        // 动态调整线程池
        ioExecutor.setCorePoolSize(newCoreSize);
        ioExecutor.setMaxPoolSize(newMaxSize);
        
        log.info("线程池已调整: active={}, poolSize={}, queueSize={}", 
            ioExecutor.getActiveCount(),
            ioExecutor.getPoolSize(),
            ioExecutor.getThreadPoolExecutor().getQueue().size());
    }
}
```

### 7.5 Outbox Consumer 轮询间隔监听

```java
@Component
@Slf4j
public class OutboxConfigChangeListener {
    
    private final OutboxProperties outboxProperties;
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> currentTask;
    
    public OutboxConfigChangeListener(OutboxProperties outboxProperties) {
        this.outboxProperties = outboxProperties;
        this.scheduler = Executors.newScheduledThreadPool(1);
    }
    
    @EventListener
    public void onRefresh(RefreshScopeRefreshedEvent event) {
        long newInterval = outboxProperties.getPollIntervalMs();
        log.info("Outbox 轮询间隔变更: intervalMs={}", newInterval);
        
        // 取消旧任务
        if (currentTask != null) {
            currentTask.cancel(false);
        }
        
        // 使用新间隔重新调度
        currentTask = scheduler.scheduleWithFixedDelay(
            this::pollOutbox,
            0,
            newInterval,
            TimeUnit.MILLISECONDS
        );
        
        log.info("Outbox 轮询任务已重新调度");
    }
    
    private void pollOutbox() {
        // 轮询逻辑
    }
}
```

### 7.6 监听器组织结构

```
common/
  └── listener/
      └── ConfigChangeListener.java  (通用监听器)

gateway/
  └── listener/
      └── RateLimitConfigChangeListener.java

order-service/
  └── listener/
      ├── BusinessConfigChangeListener.java
      └── ThreadPoolConfigChangeListener.java

outbox-consumer/
  └── listener/
      └── OutboxConfigChangeListener.java
```

## 8. 部署与运维

### 8.1 Nacos 配置初始化

#### 1. 启动 Nacos 并配置加密

修改 `docker-compose.yml`：

```yaml
nacos:
  image: nacos/nacos-server:v2.5.1
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
    # 启用配置加密
    NACOS_CONFIG_ENCRYPTION_ENABLED: true
    NACOS_CONFIG_ENCRYPTION_KEY: ${NACOS_ENCRYPTION_KEY:-governance-suite-encryption-key-2024}
  ports:
    - "8848:8848"
  depends_on:
    - mysql
```

#### 2. 在 Nacos 控制台创建配置

访问 http://localhost:8848/nacos (nacos/nacos)

创建以下配置文件：
- `common.yml` (Group: DEFAULT_GROUP)
- `gateway.yml` (Group: DEFAULT_GROUP)
- `order-service.yml` (Group: DEFAULT_GROUP)
- `payment-service.yml` (Group: DEFAULT_GROUP)
- `inventory-service.yml` (Group: DEFAULT_GROUP)
- `user-service.yml` (Group: DEFAULT_GROUP)
- `outbox-consumer.yml` (Group: DEFAULT_GROUP)

### 8.2 配置迁移步骤

#### 阶段 1：准备工作

1. 备份现有 `application.yml` 文件
2. 在 Nacos 控制台创建配置文件
3. 将配置内容迁移到 Nacos（先不启用加密）

#### 阶段 2：服务改造

1. 各服务添加 Nacos Config 依赖
2. 创建 `bootstrap.yml` 文件
3. 保留 `application.yml` 作为本地兜底配置

#### 阶段 3：灰度验证

1. 先改造一个服务（如 user-service）
2. 验证配置加载、热更新、监听器
3. 确认无问题后，逐个改造其他服务

#### 阶段 4：启用加密

1. 在 Nacos 中标记敏感字段为 `cipher()`
2. 重启服务验证解密功能
3. 删除本地配置文件中的敏感信息

### 8.3 配置管理规范

#### 1. 配置变更流程

```
开发提出变更需求
  ↓
在 Nacos 控制台修改配置
  ↓
观察服务日志（监听器输出）
  ↓
验证功能是否生效
  ↓
记录变更到文档
```

#### 2. 配置回滚

- Nacos 支持配置历史版本查看
- 可以一键回滚到历史版本
- 回滚后服务自动刷新

#### 3. 多环境管理

使用 Namespace 隔离环境：
- `public`：开发环境
- `test`：测试环境
- `prod`：生产环境

通过启动参数指定：
```bash
-Dspring.cloud.nacos.config.namespace=prod
```

### 8.4 监控与告警

#### 1. 配置加载监控

```java
@Component
public class ConfigHealthIndicator implements HealthIndicator {
    
    private final NacosConfigManager nacosConfigManager;
    
    @Override
    public Health health() {
        try {
            // 检查 Nacos 连接
            boolean connected = nacosConfigManager.getConfigService() != null;
            if (connected) {
                return Health.up()
                    .withDetail("nacos", "connected")
                    .build();
            }
        } catch (Exception e) {
            return Health.down()
                .withDetail("error", e.getMessage())
                .build();
        }
        return Health.down().build();
    }
}
```

#### 2. 配置变更日志

- 所有配置变更都会输出到日志
- 可以通过 ELK 收集分析
- 关键配置变更（如限流阈值）记录到审计表

### 8.5 故障处理

#### 场景 1：Nacos 不可用

- 服务启动时会使用本地缓存配置（`${user.home}/nacos/config/` 目录）
- 服务可以正常运行，但无法接收配置更新

#### 场景 2：配置错误导致服务异常

- 立即在 Nacos 控制台回滚配置
- 服务自动刷新，恢复正常

#### 场景 3：加密密钥丢失

- 无法解密敏感配置，服务启动失败
- 需要重新设置密钥或重新加密配置

### 8.6 文档输出

完成后会生成以下文档：
- `docs/superpowers/specs/2026-05-20-config-center-design.md` - 设计文档
- `docs/config-center-migration-guide.md` - 迁移指南（可选）
- `README.md` 更新 - 添加配置中心使用说明

## 9. 关键决策记录

### 9.1 为什么选择 Nacos Config 而不是 Apollo？

- **复用基础设施**：项目已使用 Nacos 做注册中心，直接启用 Config 功能无需额外部署
- **学习成本低**：Spring Cloud Alibaba 官方支持，文档完善
- **功能够用**：满足所有需求（热更新、多环境、加密）
- **运维简单**：不需要额外维护 Apollo Server

### 9.2 为什么采用分层共享配置？

- **减少重复**：MySQL、Redis 等基础设施配置在 `common.yml` 中统一管理
- **便于维护**：修改数据库地址只需改一处
- **灵活覆盖**：服务配置可以覆盖共享配置，满足特殊需求

### 9.3 为什么只有部分配置支持热更新？

- **安全性**：数据库连接池、分片规则等底层配置热更新风险高
- **稳定性**：避免运行时修改核心配置导致不可预期的问题
- **实用性**：业务开关、限流阈值等运营配置才是真正需要热更新的

### 9.4 为什么使用 Nacos 原生加密而不是 Jasypt？

- **服务端加密**：密文在 Nacos Server 端加解密，客户端无需感知
- **统一管理**：加密密钥在 Nacos Server 统一管理，不需要每个服务配置
- **更安全**：密钥不需要分发到各个服务

### 9.5 为什么需要配置变更监听？

- **业务联动**：秒杀开关关闭时需要清理缓存
- **资源调整**：线程池配置变更需要动态调整
- **审计追踪**：记录配置变更历史，便于问题排查
- **监控告警**：关键配置变更需要通知监控系统

## 10. 风险与限制

### 10.1 已知风险

1. **Nacos 单点故障**
   - 风险：Nacos 不可用时无法接收配置更新
   - 缓解：服务使用本地缓存配置，可以正常运行

2. **配置错误导致服务异常**
   - 风险：错误的配置可能导致服务不可用
   - 缓解：Nacos 支持配置回滚，可以快速恢复

3. **热更新失效**
   - 风险：某些配置修改后未生效
   - 缓解：通过 Actuator 手动触发刷新

### 10.2 限制

1. **不支持所有配置热更新**
   - 数据库连接池、分片规则等需要重启
   - 只有标注 `@RefreshScope` 的 Bean 会刷新

2. **加密密钥管理**
   - 密钥丢失后无法解密配置
   - 需要妥善保管密钥

3. **配置变更无审批流程**
   - Nacos 控制台直接修改即生效
   - 生产环境建议配合权限管理

## 11. 后续优化方向

### 11.1 短期优化（1-2 周）

1. **配置变更审计表**
   - 记录谁、何时、修改了什么配置
   - 便于问题追溯

2. **配置变更通知**
   - 关键配置变更发送邮件/短信通知
   - 提高配置变更的可见性

### 11.2 中期优化（1-2 月）

1. **配置灰度发布**
   - 配置变更先在部分实例生效
   - 验证无问题后全量发布

2. **配置依赖检查**
   - 检查配置项之间的依赖关系
   - 避免配置不一致导致的问题

### 11.3 长期优化（3-6 月）

1. **配置审批流程**
   - 生产环境配置变更需要审批
   - 集成工单系统

2. **配置 A/B 测试**
   - 支持不同用户使用不同配置
   - 用于功能灰度和效果对比

## 12. 验收标准

### 12.1 功能验收

- [ ] 6 个服务都成功接入 Nacos Config
- [ ] 共享配置（common.yml）正常加载
- [ ] 服务特有配置正常加载
- [ ] 配置优先级正确（服务配置 > 共享配置）
- [ ] 敏感配置加密存储并正确解密
- [ ] 动态配置支持热更新（无需重启）
- [ ] 配置变更监听器正常工作
- [ ] 配置回滚功能正常

### 12.2 性能验收

- [ ] 服务启动时间增加 < 2 秒
- [ ] 配置刷新延迟 < 5 秒
- [ ] Nacos 不可用时服务可以正常启动

### 12.3 文档验收

- [ ] 设计文档完整
- [ ] 迁移指南清晰
- [ ] README 更新配置中心使用说明
- [ ] 配置变更流程文档

## 13. 附录

### 13.1 相关文档

- [Nacos Config 官方文档](https://nacos.io/zh-cn/docs/quick-start-spring-cloud.html)
- [Spring Cloud Alibaba 文档](https://spring-cloud-alibaba-group.github.io/github-pages/2022/zh-cn/index.html)
- [配置加密插件文档](https://nacos.io/zh-cn/docs/plugin-config-encryption.html)

### 13.2 配置文件清单

| 配置文件 | 位置 | 说明 |
|---------|------|------|
| `bootstrap.yml` | 各服务 `src/main/resources/` | 引导配置，指定 Nacos 地址 |
| `common.yml` | Nacos Config | 共享配置 |
| `gateway.yml` | Nacos Config | 网关配置 |
| `order-service.yml` | Nacos Config | 订单服务配置 |
| `payment-service.yml` | Nacos Config | 支付服务配置 |
| `inventory-service.yml` | Nacos Config | 库存服务配置 |
| `user-service.yml` | Nacos Config | 用户服务配置 |
| `outbox-consumer.yml` | Nacos Config | Outbox 消费者配置 |

### 13.3 配置项索引

#### 动态配置（支持热更新）

| 配置项 | 服务 | 默认值 | 说明 |
|-------|------|--------|------|
| `gateway.rate-limit.replenish-rate` | gateway | 5 | 限流令牌补充速率 |
| `gateway.rate-limit.burst-capacity` | gateway | 10 | 限流突发容量 |
| `gateway.block-ip` | gateway | 10.10.10.10 | 黑名单 IP |
| `gateway.sentinel.order-list.qps` | gateway | 2 | Sentinel QPS 阈值 |
| `app.business.seckill-enabled` | order-service | true | 秒杀开关 |
| `app.business.order-timeout-seconds` | order-service | 1800 | 订单超时时间 |
| `app.thread-pool.io.core-size` | order-service | 32 | IO 线程池核心线程数 |
| `app.thread-pool.io.max-size` | order-service | 64 | IO 线程池最大线程数 |

#### 静态配置（需要重启）

| 配置项 | 服务 | 说明 |
|-------|------|------|
| `server.port` | 所有服务 | 服务端口 |
| `spring.application.name` | 所有服务 | 服务名 |
| `mysql.host` | common | MySQL 地址 |
| `mysql.password` | common | MySQL 密码（加密） |
| `redis.host` | common | Redis 地址 |
| `rabbitmq.host` | common | RabbitMQ 地址 |
| `seata.*` | order-service | Seata 配置 |
| `spring.shardingsphere.*` | order/payment | 分片规则 |

---

**设计完成日期**: 2026-05-20  
**下一步**: 编写实现计划（Implementation Plan）
