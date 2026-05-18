# 生产级增强详细设计

**父文档**: [AI对话流程重构设计方案](./2026-05-18-ai-chat-flow-refactor-design.md)

---

## 1. 概述

生产级增强设计涵盖容错、降级、监控、配置管理等关键生产环境特性，确保系统的高可用性和可维护性。

### 1.1 设计目标

- **高可用性**：服务可用性 > 99.9%
- **容错能力**：关键依赖故障时自动降级
- **可观测性**：完整的监控指标和告警
- **可维护性**：清晰的日志和健康检查

---

## 2. 容错和降级策略

### 2.1 SessionContextService容错

```java
@Service
@Slf4j
public class SessionContextService {
    
    @Autowired
    private RedisTemplate<String, String> redisTemplate;
    
    @Autowired
    private DeepSeekIntentAnalyzer intentAnalyzer;
    
    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;
    
    /**
     * 获取或创建会话上下文（带降级）
     */
    @CircuitBreaker(name = "sessionContext", fallbackMethod = "getContextFallback")
    @Retry(name = "sessionContext", fallbackMethod = "getContextFallback")
    public SessionContext getOrCreateContext(
            String conversationId, 
            String customerId,
            String groupId,
            String currentMessage) {
        
        String key = CONTEXT_KEY_PREFIX + conversationId;
        
        try {
            SessionContext context = getFromRedis(key);
            
            if (context == null) {
                // 调用DeepSeek分析意图（带超时控制）
                context = intentAnalyzer.analyzeIntentWithTimeout(
                    currentMessage, 
                    Collections.emptyList(),
                    5000 // 5秒超时
                );
                
                if (context == null) {
                    // DeepSeek失败，使用默认上下文
                    context = createDefaultContext(conversationId, customerId, groupId);
                }
                
                context.setConversationId(conversationId);
                context.setCustomerId(customerId);
                context.setGroupId(groupId);
                context.setSessionStartTime(System.currentTimeMillis());
                context.setMessageCount(1);
                
                saveToRedis(key, context);
            }
            
            return context;
            
        } catch (Exception e) {
            log.error("获取会话上下文失败，使用降级策略: conversationId={}", conversationId, e);
            return createDefaultContext(conversationId, customerId, groupId);
        }
    }
    
    /**
     * 降级方法：返回默认上下文
     */
    private SessionContext getContextFallback(
            String conversationId, 
            String customerId,
            String groupId,
            String currentMessage,
            Throwable t) {
        
        log.warn("会话上下文服务降级: conversationId={}, error={}", 
            conversationId, t.getMessage());
        
        return createDefaultContext(conversationId, customerId, groupId);
    }
    
    /**
     * 创建默认上下文（不依赖DeepSeek）
     */
    private SessionContext createDefaultContext(
            String conversationId,
            String customerId, 
            String groupId) {
        
        return SessionContext.builder()
            .conversationId(conversationId)
            .customerId(customerId)
            .groupId(groupId)
            .currentTopic("健康咨询")
            .conversationPhase("consulting")
            .mentionedEntities(new ArrayList<>())
            .extractedData(new HashMap<>())
            .userIntent("一般咨询")
            .pendingQuestions(new ArrayList<>())
            .complexity("simple")
            .recentSummaries(new ArrayList<>())
            .sessionStartTime(System.currentTimeMillis())
            .messageCount(1)
            .build();
    }
    
    /**
     * 异步更新上下文（不阻塞主流程）
     */
    @Async("contextUpdateExecutor")
    public CompletableFuture<Void> updateContextAsync(
            String conversationId,
            String userMessage,
            String aiResponse) {
        
        return CompletableFuture.runAsync(() -> {
            try {
                updateContext(conversationId, userMessage, aiResponse);
            } catch (Exception e) {
                log.error("异步更新上下文失败: conversationId={}", conversationId, e);
                // 不抛出异常，避免影响主流程
            }
        });
    }
}
```

### 2.2 RedisProfileService容错

```java
@Service
@Slf4j
public class RedisProfileService {
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    @Autowired
    private UserProfileService userProfileService;
    
    /**
     * 获取用户画像（带降级）
     */
    public UserProfileDTO getProfile(String customerId) {
        String key = PROFILE_KEY_PREFIX + customerId;
        
        try {
            // 1. 尝试从Redis获取
            Map<Object, Object> cached = redisTemplate.opsForHash().entries(key);
            
            if (!cached.isEmpty()) {
                log.debug("从Redis获取用户画像: customerId={}", customerId);
                return deserializeProfile(cached);
            }
            
            // 2. Redis未命中，从MySQL构建
            log.info("Redis未命中，从MySQL构建画像: customerId={}", customerId);
            UserProfileDTO profile = userProfileService.buildUserProfile(customerId);
            
            if (profile != null) {
                // 3. 回写Redis
                saveToRedis(key, profile);
            }
            
            return profile;
            
        } catch (Exception e) {
            log.error("从Redis获取画像失败，降级到MySQL: customerId={}", customerId, e);
            // 降级：直接查询MySQL
            return userProfileService.buildUserProfile(customerId);
        }
    }
}
```

---

## 3. Redis配置优化

### 3.1 连接池配置

```java
@Configuration
public class RedisConfig {
    
    @Bean
    public RedisTemplate<String, Object> redisTemplate(
            RedisConnectionFactory connectionFactory) {
        
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        
        // 使用Jackson序列化（比JDK序列化更高效）
        Jackson2JsonRedisSerializer<Object> serializer = 
            new Jackson2JsonRedisSerializer<>(Object.class);
        
        ObjectMapper mapper = new ObjectMapper();
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        mapper.activateDefaultTyping(
            LaissezFaireSubTypeValidator.instance,
            ObjectMapper.DefaultTyping.NON_FINAL
        );
        serializer.setObjectMapper(mapper);
        
        // Key使用String序列化
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        
        // Value使用Jackson序列化
        template.setValueSerializer(serializer);
        template.setHashValueSerializer(serializer);
        
        template.afterPropertiesSet();
        return template;
    }
    
    @Bean
    public LettuceConnectionFactory redisConnectionFactory() {
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
            .commandTimeout(Duration.ofSeconds(5))
            .shutdownTimeout(Duration.ofMillis(100))
            .build();
        
        RedisStandaloneConfiguration serverConfig = new RedisStandaloneConfiguration();
        serverConfig.setHostName("${REDIS_HOST:localhost}");
        serverConfig.setPort(6379);
        serverConfig.setPassword("${REDIS_PASSWORD:}");
        serverConfig.setDatabase(0);
        
        return new LettuceConnectionFactory(serverConfig, clientConfig);
    }
    
    @Bean("contextUpdateExecutor")
    public Executor contextUpdateExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("context-update-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
```

### 3.2 生产环境配置

```yaml
# application-prod.yml
spring:
  # Redis配置
  redis:
    host: ${REDIS_HOST:redis-cluster.internal}
    port: ${REDIS_PORT:6379}
    password: ${REDIS_PASSWORD}
    database: 0
    lettuce:
      pool:
        max-active: 20
        max-idle: 10
        min-idle: 5
        max-wait: 2000ms
      shutdown-timeout: 100ms
    timeout: 5000ms

  # RabbitMQ配置
  rabbitmq:
    host: ${RABBITMQ_HOST:rabbitmq-cluster.internal}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USERNAME}
    password: ${RABBITMQ_PASSWORD}
    virtual-host: /prod
    listener:
      simple:
        acknowledge-mode: manual
        prefetch: 1
        retry:
          enabled: true
          max-attempts: 3
          initial-interval: 1000ms
          multiplier: 2
          max-interval: 10000ms
    template:
      retry:
        enabled: true
        max-attempts: 3

  # 数据库连接池
  datasource:
    hikari:
      minimum-idle: 10
      maximum-pool-size: 50
      idle-timeout: 600000
      max-lifetime: 1800000
      connection-timeout: 30000
      leak-detection-threshold: 60000
```

---

## 4. Resilience4j配置

### 4.1 熔断器配置

```yaml
resilience4j:
  circuitbreaker:
    instances:
      sessionContext:
        failure-rate-threshold: 50
        slow-call-rate-threshold: 50
        slow-call-duration-threshold: 5s
        wait-duration-in-open-state: 60s
        permitted-number-of-calls-in-half-open-state: 3
        sliding-window-size: 10
        minimum-number-of-calls: 5
      
      deepseek:
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
        sliding-window-size: 10
  
  retry:
    instances:
      sessionContext:
        max-attempts: 3
        wait-duration: 500ms
        exponential-backoff-multiplier: 2
      
      milvus:
        max-attempts: 3
        wait-duration: 1000ms

  timelimiter:
    instances:
      deepseek:
        timeout-duration: 10s
```

---

## 5. 监控和可观测性

### 5.1 指标定义

```java
@Component
@Slf4j
public class AiChatMetrics {
    
    private final MeterRegistry meterRegistry;
    
    // 计数器
    private final Counter chatRequestCounter;
    private final Counter contextCacheHitCounter;
    private final Counter contextCacheMissCounter;
    private final Counter profileCacheHitCounter;
    private final Counter profileCacheMissCounter;
    private final Counter memoryDuplicateCounter;
    
    // 计时器
    private final Timer chatResponseTimer;
    private final Timer contextAnalysisTimer;
    private final Timer embeddingTimer;
    private final Timer milvusSearchTimer;
    
    public AiChatMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        
        // 初始化指标
        this.chatRequestCounter = Counter.builder("ai.chat.requests")
            .description("Total chat requests")
            .register(meterRegistry);
        
        this.contextCacheHitCounter = Counter.builder("ai.context.cache.hit")
            .description("Context cache hits")
            .register(meterRegistry);
        
        this.contextCacheMissCounter = Counter.builder("ai.context.cache.miss")
            .description("Context cache misses")
            .register(meterRegistry);
        
        this.profileCacheHitCounter = Counter.builder("ai.profile.cache.hit")
            .description("Profile cache hits")
            .register(meterRegistry);
        
        this.profileCacheMissCounter = Counter.builder("ai.profile.cache.miss")
            .description("Profile cache misses")
            .register(meterRegistry);
        
        this.memoryDuplicateCounter = Counter.builder("ai.memory.duplicate")
            .description("Duplicate memories filtered")
            .register(meterRegistry);
        
        this.chatResponseTimer = Timer.builder("ai.chat.response.time")
            .description("Chat response time")
            .register(meterRegistry);
        
        this.contextAnalysisTimer = Timer.builder("ai.context.analysis.time")
            .description("Context analysis time")
            .register(meterRegistry);
        
        this.embeddingTimer = Timer.builder("ai.embedding.time")
            .description("Embedding generation time")
            .register(meterRegistry);
        
        this.milvusSearchTimer = Timer.builder("ai.milvus.search.time")
            .description("Milvus search time")
            .register(meterRegistry);
    }
    
    public void recordChatRequest() {
        chatRequestCounter.increment();
    }
    
    public void recordContextCacheHit() {
        contextCacheHitCounter.increment();
    }
    
    public void recordContextCacheMiss() {
        contextCacheMissCounter.increment();
    }
    
    public void recordProfileCacheHit() {
        profileCacheHitCounter.increment();
    }
    
    public void recordProfileCacheMiss() {
        profileCacheMissCounter.increment();
    }
    
    public void recordMemoryDuplicate() {
        memoryDuplicateCounter.increment();
    }
    
    public <T> T recordChatResponse(Supplier<T> operation) {
        return chatResponseTimer.record(operation);
    }
    
    public <T> T recordContextAnalysis(Supplier<T> operation) {
        return contextAnalysisTimer.record(operation);
    }
    
    public <T> T recordEmbedding(Supplier<T> operation) {
        return embeddingTimer.record(operation);
    }
    
    public <T> T recordMilvusSearch(Supplier<T> operation) {
        return milvusSearchTimer.record(operation);
    }
}
```

### 5.2 Prometheus配置

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus
  metrics:
    export:
      prometheus:
        enabled: true
    tags:
      application: ${spring.application.name}
      environment: prod
```

---

## 6. 健康检查

### 6.1 AiServicesHealthIndicator

```java
@Component
public class AiServicesHealthIndicator implements HealthIndicator {
    
    @Autowired
    private RedisTemplate<String, String> redisTemplate;
    
    @Autowired
    private VectorStoreService vectorStoreService;
    
    @Autowired
    private DeepSeekClient deepSeekClient;
    
    @Override
    public Health health() {
        Map<String, Object> details = new HashMap<>();
        boolean allHealthy = true;
        
        // 检查Redis
        try {
            redisTemplate.opsForValue().get("health:check");
            details.put("redis", "UP");
        } catch (Exception e) {
            details.put("redis", "DOWN: " + e.getMessage());
            allHealthy = false;
        }
        
        // 检查Milvus
        try {
            vectorStoreService.healthCheck();
            details.put("milvus", "UP");
        } catch (Exception e) {
            details.put("milvus", "DOWN: " + e.getMessage());
            allHealthy = false;
        }
        
        // 检查DeepSeek API
        try {
            deepSeekClient.healthCheck();
            details.put("deepseek", "UP");
        } catch (Exception e) {
            details.put("deepseek", "DOWN: " + e.getMessage());
            allHealthy = false;
        }
        
        if (allHealthy) {
            return Health.up().withDetails(details).build();
        } else {
            return Health.down().withDetails(details).build();
        }
    }
}
```

---

## 7. 日志配置

### 7.1 logback-spring.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <springProperty scope="context" name="APP_NAME" source="spring.application.name"/>
    
    <!-- 控制台输出 -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    
    <!-- 文件输出 -->
    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>/var/log/${APP_NAME}/application.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>/var/log/${APP_NAME}/application.%d{yyyy-MM-dd}.log</fileNamePattern>
            <maxHistory>30</maxHistory>
            <totalSizeCap>10GB</totalSizeCap>
        </rollingPolicy>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    
    <!-- 错误日志单独输出 -->
    <appender name="ERROR_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>/var/log/${APP_NAME}/error.log</file>
        <filter class="ch.qos.logback.classic.filter.LevelFilter">
            <level>ERROR</level>
            <onMatch>ACCEPT</onMatch>
            <onMismatch>DENY</onMismatch>
        </filter>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>/var/log/${APP_NAME}/error.%d{yyyy-MM-dd}.log</fileNamePattern>
            <maxHistory>30</maxHistory>
        </rollingPolicy>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    
    <!-- 日志级别 -->
    <logger name="com.lx.governance" level="INFO"/>
    <logger name="org.springframework.ai" level="WARN"/>
    <logger name="org.springframework.data.redis" level="WARN"/>
    
    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="FILE"/>
        <appender-ref ref="ERROR_FILE"/>
    </root>
</configuration>
```

---

## 8. 告警规则

### 8.1 Prometheus告警

```yaml
groups:
  - name: ai-chat-alerts
    rules:
      # 对话响应时间过长
      - alert: ChatResponseTimeSlow
        expr: histogram_quantile(0.95, ai_chat_response_time_seconds_bucket) > 5
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "对话响应时间P95超过5秒"
          description: "当前P95: {{ $value }}秒"
      
      # 画像缓存命中率低
      - alert: ProfileCacheHitRateLow
        expr: |
          ai_profile_cache_hit_total / 
          (ai_profile_cache_hit_total + ai_profile_cache_miss_total) < 0.8
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: "画像缓存命中率低于80%"
          description: "当前命中率: {{ $value | humanizePercentage }}"
      
      # Redis连接失败
      - alert: RedisConnectionError
        expr: redis_connection_errors_total > 10
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "Redis连接失败次数过多"
          description: "最近5分钟失败{{ $value }}次"
      
      # Milvus检索失败
      - alert: MilvusSearchError
        expr: milvus_search_errors_total > 5
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "Milvus检索失败次数过多"
          description: "最近5分钟失败{{ $value }}次"
      
      # 服务不可用
      - alert: ServiceDown
        expr: up{job="ai-chat-service"} == 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "AI对话服务不可用"
          description: "服务已停止响应"
```

---

## 9. 生产级迁移脚本

### 9.1 ProductionEmbeddingMigration

```java
@Component
@Slf4j
public class ProductionEmbeddingMigration {
    
    @Autowired
    private AiGroupMemoryMapper memoryMapper;
    
    @Autowired
    private EmbeddingService embeddingService;
    
    @Autowired
    private VectorStoreService vectorStoreService;
    
    @Autowired
    private RedisTemplate<String, String> redisTemplate;
    
    private static final String MIGRATION_PROGRESS_KEY = "migration:embedding:v3:progress";
    private static final String MIGRATION_LOCK_KEY = "migration:embedding:v3:lock";
    private static final int BATCH_SIZE = 100;
    
    /**
     * 生产级迁移：支持断点续传、进度追踪、并发控制
     */
    public MigrationResult migrate() {
        // 1. 获取分布式锁（防止多实例同时迁移）
        Boolean lockAcquired = redisTemplate.opsForValue()
            .setIfAbsent(MIGRATION_LOCK_KEY, "locked", 2, TimeUnit.HOURS);
        
        if (Boolean.FALSE.equals(lockAcquired)) {
            throw new IllegalStateException("迁移任务已在运行中");
        }
        
        try {
            return doMigrate();
        } finally {
            redisTemplate.delete(MIGRATION_LOCK_KEY);
        }
    }
    
    private MigrationResult doMigrate() {
        log.info("开始Embedding迁移：v2 -> v3");
        
        MigrationResult result = new MigrationResult();
        result.setStartTime(LocalDateTime.now());
        
        // 2. 获取上次迁移进度
        String progressStr = redisTemplate.opsForValue().get(MIGRATION_PROGRESS_KEY);
        long lastProcessedId = progressStr != null ? Long.parseLong(progressStr) : 0L;
        
        log.info("从ID {} 继续迁移", lastProcessedId);
        
        // 3. 分批查询待迁移记忆
        long currentId = lastProcessedId;
        boolean hasMore = true;
        
        while (hasMore) {
            List<AiGroupMemoryEntity> batch = memoryMapper.selectBatchAfter(currentId, BATCH_SIZE);
            
            if (batch.isEmpty()) {
                hasMore = false;
                break;
            }
            
            // 4. 批量处理
            for (AiGroupMemoryEntity memory : batch) {
                try {
                    migrateOne(memory);
                    result.incrementSuccess();
                    currentId = memory.getId();
                    
                } catch (Exception e) {
                    log.error("迁移失败: memoryId={}", memory.getId(), e);
                    result.incrementFailed();
                    result.addFailedId(memory.getId());
                }
            }
            
            // 5. 保存进度
            redisTemplate.opsForValue().set(MIGRATION_PROGRESS_KEY, String.valueOf(currentId));
            
            // 6. 输出进度
            if (result.getSuccessCount() % 1000 == 0) {
                log.info("迁移进度: 成功={}, 失败={}, 当前ID={}", 
                    result.getSuccessCount(), result.getFailedCount(), currentId);
            }
            
            // 7. 限流（避免打爆API）
            try {
                Thread.sleep(10); // 每条记录间隔10ms
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        result.setEndTime(LocalDateTime.now());
        result.setDuration(Duration.between(result.getStartTime(), result.getEndTime()));
        
        log.info("迁移完成 - 成功: {}, 失败: {}, 耗时: {}", 
            result.getSuccessCount(), 
            result.getFailedCount(),
            result.getDuration());
        
        // 8. 清除进度标记
        redisTemplate.delete(MIGRATION_PROGRESS_KEY);
        
        return result;
    }
    
    private void migrateOne(AiGroupMemoryEntity memory) {
        // 1. 生成新向量
        float[] newEmbedding = embeddingService.generateEmbedding(memory.getContent());
        
        // 2. 保存到Milvus v3 collection
        String newEmbeddingId = vectorStoreService.saveGroupMemory(
            memory.getGroupId(),
            memory.getContent(),
            newEmbedding,
            memory.getId()
        );
        
        // 3. 更新数据库
        memory.setEmbeddingId(newEmbeddingId);
        memory.setEmbeddingVersion("v3");
        memoryMapper.updateById(memory);
    }
    
    @Data
    public static class MigrationResult {
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private Duration duration;
        private int successCount = 0;
        private int failedCount = 0;
        private List<Long> failedIds = new ArrayList<>();
        
        public void incrementSuccess() {
            successCount++;
        }
        
        public void incrementFailed() {
            failedCount++;
        }
        
        public void addFailedId(Long id) {
            failedIds.add(id);
        }
    }
}
```

---

## 10. 常见问题

**Q1: 如何处理Redis故障？**  
A: 自动降级到MySQL，不影响主流程。监控告警会通知运维人员。

**Q2: DeepSeek API限流怎么办？**  
A: 使用默认上下文降级，保证服务可用。同时触发告警，运维人员可以调整限流策略。

**Q3: 如何保证迁移脚本的幂等性？**  
A: 通过Redis分布式锁防止并发执行，通过进度追踪支持断点续传。

**Q4: 监控指标如何接入现有监控系统？**  
A: 通过Prometheus暴露指标，可以接入Grafana、AlertManager等监控系统。
