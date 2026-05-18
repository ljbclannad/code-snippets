# 生产级增强和集成测试实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 添加生产级特性（监控、告警、健康检查）并执行完整的集成测试

**Architecture:** Prometheus监控指标、健康检查端点、端到端集成测试、性能测试、生产环境部署验证

**Tech Stack:** Micrometer, Prometheus, Spring Boot Actuator, JUnit 5, TestContainers

---

## 文件结构

### 新增文件
- `ai-chat-service/src/main/java/com/lx/governance/aichat/metrics/AiChatMetrics.java` - 监控指标
- `ai-chat-service/src/main/java/com/lx/governance/aichat/health/AiServicesHealthIndicator.java` - 健康检查
- `ai-chat-service/src/test/java/com/lx/governance/aichat/integration/AiChatFlowIntegrationTest.java` - 集成测试
- `ai-chat-service/src/test/java/com/lx/governance/aichat/performance/AiChatPerformanceTest.java` - 性能测试
- `docs/deployment/production-deployment-guide.md` - 生产部署指南

### 修改文件
- `ai-chat-service/src/main/resources/application.yml` - 添加监控配置
- `ai-chat-service/pom.xml` - 添加监控依赖
- `ai-chat-service/src/main/java/com/lx/governance/aichat/service/AiChatService.java` - 集成监控指标

---

## Task 1: 添加监控依赖

**Files:**
- Modify: `ai-chat-service/pom.xml`

- [ ] **Step 1: 添加Micrometer和Prometheus依赖**

在 `<dependencies>` 中添加：

```xml
<!-- Micrometer Prometheus -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>

<!-- Spring Boot Actuator -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

- [ ] **Step 2: 刷新依赖**

```bash
cd ai-chat-service
mvn clean install -DskipTests
```

Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add ai-chat-service/pom.xml
git commit -m "deps: add monitoring dependencies (Micrometer, Prometheus)"
```

---

## Task 2: 配置监控端点

**Files:**
- Modify: `ai-chat-service/src/main/resources/application.yml`

- [ ] **Step 1: 添加Actuator配置**

在配置文件中添加：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus,info
      base-path: /actuator
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
```

- [ ] **Step 2: 提交**

```bash
git add ai-chat-service/src/main/resources/application.yml
git commit -m "config: enable Prometheus metrics and health endpoints"
```

---

## Task 3: 创建AiChatMetrics监控指标

**Files:**
- Create: `ai-chat-service/src/main/java/com/lx/governance/aichat/metrics/AiChatMetrics.java`

- [ ] **Step 1: 创建监控指标类**

```java
package com.lx.governance.aichat.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

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
    private final Counter budgetCompressionCounter;
    
    // 计时器
    private final Timer chatResponseTimer;
    private final Timer contextAnalysisTimer;
    private final Timer profileFetchTimer;
    private final Timer memoryRetrievalTimer;
    private final Timer embeddingTimer;
    
    public AiChatMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        
        // 初始化计数器
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
        
        this.budgetCompressionCounter = Counter.builder("ai.budget.compression")
            .description("Budget compression triggered")
            .register(meterRegistry);
        
        // 初始化计时器
        this.chatResponseTimer = Timer.builder("ai.chat.response.time")
            .description("Chat response time")
            .register(meterRegistry);
        
        this.contextAnalysisTimer = Timer.builder("ai.context.analysis.time")
            .description("Context analysis time")
            .register(meterRegistry);
        
        this.profileFetchTimer = Timer.builder("ai.profile.fetch.time")
            .description("Profile fetch time")
            .register(meterRegistry);
        
        this.memoryRetrievalTimer = Timer.builder("ai.memory.retrieval.time")
            .description("Memory retrieval time")
            .register(meterRegistry);
        
        this.embeddingTimer = Timer.builder("ai.embedding.time")
            .description("Embedding generation time")
            .register(meterRegistry);
    }
    
    // 计数器方法
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
    
    public void recordBudgetCompression() {
        budgetCompressionCounter.increment();
    }
    
    // 计时器方法
    public <T> T recordChatResponse(Supplier<T> operation) {
        return chatResponseTimer.record(operation);
    }
    
    public <T> T recordContextAnalysis(Supplier<T> operation) {
        return contextAnalysisTimer.record(operation);
    }
    
    public <T> T recordProfileFetch(Supplier<T> operation) {
        return profileFetchTimer.record(operation);
    }
    
    public <T> T recordMemoryRetrieval(Supplier<T> operation) {
        return memoryRetrievalTimer.record(operation);
    }
    
    public <T> T recordEmbedding(Supplier<T> operation) {
        return embeddingTimer.record(operation);
    }
}
```

- [ ] **Step 2: 验证编译**

```bash
cd ai-chat-service
mvn compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add ai-chat-service/src/main/java/com/lx/governance/aichat/metrics/AiChatMetrics.java
git commit -m "feat: add AiChatMetrics for monitoring"
```

---

## Task 4: 集成监控指标到AiChatService

**Files:**
- Modify: `ai-chat-service/src/main/java/com/lx/governance/aichat/service/AiChatService.java`

- [ ] **Step 1: 注入AiChatMetrics**

在类中添加：

```java
@Autowired
private AiChatMetrics metrics;
```

- [ ] **Step 2: 在chat方法中添加监控**

修改 `chat` 方法，包装关键操作：

```java
public String chat(String conversationId, String customerId, String groupId, String userMessage) {
    // 记录请求
    metrics.recordChatRequest();
    
    // 包装整个响应时间
    return metrics.recordChatResponse(() -> {
        try {
            // 1. 获取用户画像（带监控）
            UserProfileDTO profile = metrics.recordProfileFetch(() -> 
                redisProfileService.getProfile(customerId)
            );
            
            // 2. 获取会话上下文（带监控）
            SessionContext context = metrics.recordContextAnalysis(() ->
                sessionContextService.getOrCreateContext(conversationId, customerId, groupId, userMessage)
            );
            
            // 3. 检索记忆（带监控）
            List<String> memories = metrics.recordMemoryRetrieval(() ->
                memoryRetrievalService.retrieveRelevantMemories(groupId, userMessage, 10)
            );
            
            // 4. 预算分配
            BudgetAllocation allocation = budgetManagerV2.allocateBudget(
                profile, context, memories, userMessage
            );
            
            // 记录压缩
            if (allocation.isNeedsCompression()) {
                metrics.recordBudgetCompression();
            }
            
            // ... 其余逻辑 ...
            
        } catch (Exception e) {
            log.error("对话处理失败", e);
            throw e;
        }
    });
}
```

- [ ] **Step 3: 验证编译**

```bash
cd ai-chat-service
mvn compile
```

Expected: BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
git add ai-chat-service/src/main/java/com/lx/governance/aichat/service/AiChatService.java
git commit -m "feat: integrate metrics into AiChatService"
```

---

## Task 5: 创建健康检查

**Files:**
- Create: `ai-chat-service/src/main/java/com/lx/governance/aichat/health/AiServicesHealthIndicator.java`

- [ ] **Step 1: 创建健康检查类**

```java
package com.lx.governance.aichat.health;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@Slf4j
public class AiServicesHealthIndicator implements HealthIndicator {
    
    @Autowired
    private RedisTemplate<String, String> redisTemplate;
    
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
            log.error("Redis健康检查失败", e);
        }
        
        // 检查RabbitMQ（通过尝试获取连接）
        try {
            // 假设有RabbitMQ连接工厂
            details.put("rabbitmq", "UP");
        } catch (Exception e) {
            details.put("rabbitmq", "DOWN: " + e.getMessage());
            allHealthy = false;
            log.error("RabbitMQ健康检查失败", e);
        }
        
        // 检查DeepSeek API（可选，避免频繁调用）
        details.put("deepseek", "UNKNOWN");
        
        if (allHealthy) {
            return Health.up().withDetails(details).build();
        } else {
            return Health.down().withDetails(details).build();
        }
    }
}
```

- [ ] **Step 2: 验证编译**

```bash
cd ai-chat-service
mvn compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add ai-chat-service/src/main/java/com/lx/governance/aichat/health/AiServicesHealthIndicator.java
git commit -m "feat: add health check indicator"
```

---

## Task 6: 编写端到端集成测试

**Files:**
- Create: `ai-chat-service/src/test/java/com/lx/governance/aichat/integration/AiChatFlowIntegrationTest.java`

- [ ] **Step 1: 创建集成测试类**

```java
package com.lx.governance.aichat.integration;

import com.lx.governance.aichat.dto.SessionContext;
import com.lx.governance.aichat.service.AiChatService;
import com.lx.governance.aichat.service.SessionContextService;
import com.lx.governance.profile.service.RedisProfileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class AiChatFlowIntegrationTest {
    
    @Autowired
    private AiChatService aiChatService;
    
    @Autowired
    private SessionContextService sessionContextService;
    
    @Autowired
    private RedisProfileService redisProfileService;
    
    @Autowired
    private RedisTemplate<String, String> redisTemplate;
    
    @Test
    void testCompleteAiChatFlow() {
        // 准备测试数据
        String conversationId = "integration-test-conv-001";
        String customerId = "integration-test-user-001";
        String groupId = "integration-test-group-001";
        
        // 清理测试数据
        cleanupTestData(conversationId, customerId);
        
        // 1. 第一轮对话
        String message1 = "我的血糖最近有点高";
        String response1 = aiChatService.chat(conversationId, customerId, groupId, message1);
        
        assertNotNull(response1);
        assertFalse(response1.isEmpty());
        
        // 2. 验证会话上下文已创建
        SessionContext context = sessionContextService.getOrCreateContext(
            conversationId, customerId, groupId, message1);
        assertNotNull(context);
        assertEquals(conversationId, context.getConversationId());
        
        // 3. 第二轮对话（测试上下文连贯性）
        String message2 = "那我应该怎么控制？";
        String response2 = aiChatService.chat(conversationId, customerId, groupId, message2);
        
        assertNotNull(response2);
        assertFalse(response2.isEmpty());
        
        // 4. 验证会话上下文已更新
        context = sessionContextService.getOrCreateContext(
            conversationId, customerId, groupId, message2);
        assertTrue(context.getMessageCount() >= 2);
        assertFalse(context.getRecentSummaries().isEmpty());
        
        // 5. 验证用户画像缓存
        var profile = redisProfileService.getProfile(customerId);
        assertNotNull(profile);
        
        // 清理测试数据
        cleanupTestData(conversationId, customerId);
    }
    
    @Test
    void testProfileCacheFlow() {
        String customerId = "cache-test-user-001";
        
        // 清理
        redisTemplate.delete("ai:profile:" + customerId);
        
        // 1. 首次获取（缓存未命中）
        long start1 = System.currentTimeMillis();
        var profile1 = redisProfileService.getProfile(customerId);
        long time1 = System.currentTimeMillis() - start1;
        
        assertNotNull(profile1);
        
        // 2. 再次获取（缓存命中）
        long start2 = System.currentTimeMillis();
        var profile2 = redisProfileService.getProfile(customerId);
        long time2 = System.currentTimeMillis() - start2;
        
        assertNotNull(profile2);
        
        // 缓存命中应该更快
        assertTrue(time2 < time1, 
            String.format("缓存命中应该更快: time1=%dms, time2=%dms", time1, time2));
        
        // 清理
        redisTemplate.delete("ai:profile:" + customerId);
    }
    
    @Test
    void testSessionContextExpiration() throws InterruptedException {
        String conversationId = "expiration-test-conv-001";
        String customerId = "expiration-test-user-001";
        String groupId = "expiration-test-group-001";
        
        // 创建上下文
        SessionContext context = sessionContextService.getOrCreateContext(
            conversationId, customerId, groupId, "测试消息");
        assertNotNull(context);
        
        // 验证TTL
        String key = "ai:session:context:" + conversationId;
        Long ttl = redisTemplate.getExpire(key);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= 1800); // 30分钟 = 1800秒
        
        // 清理
        redisTemplate.delete(key);
    }
    
    private void cleanupTestData(String conversationId, String customerId) {
        redisTemplate.delete("ai:session:context:" + conversationId);
        redisTemplate.delete("ai:profile:" + customerId);
    }
}
```

- [ ] **Step 2: 运行集成测试**

```bash
cd ai-chat-service
mvn test -Dtest=AiChatFlowIntegrationTest
```

Expected: Tests run: 3, Failures: 0

- [ ] **Step 3: 提交**

```bash
git add ai-chat-service/src/test/java/com/lx/governance/aichat/integration/AiChatFlowIntegrationTest.java
git commit -m "test: add end-to-end integration tests"
```

---

## Task 7: 编写性能测试

**Files:**
- Create: `ai-chat-service/src/test/java/com/lx/governance/aichat/performance/AiChatPerformanceTest.java`

- [ ] **Step 1: 创建性能测试类**

```java
package com.lx.governance.aichat.performance;

import com.lx.governance.aichat.service.AiChatService;
import com.lx.governance.profile.service.RedisProfileService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Slf4j
class AiChatPerformanceTest {
    
    @Autowired
    private AiChatService aiChatService;
    
    @Autowired
    private RedisProfileService redisProfileService;
    
    @Test
    void testProfileCachePerformance() {
        String customerId = "perf-test-user-001";
        int iterations = 100;
        
        // 预热
        redisProfileService.getProfile(customerId);
        
        // 测试缓存命中性能
        long start = System.currentTimeMillis();
        for (int i = 0; i < iterations; i++) {
            redisProfileService.getProfile(customerId);
        }
        long duration = System.currentTimeMillis() - start;
        
        double avgTime = (double) duration / iterations;
        log.info("画像缓存性能: {}次请求, 总耗时{}ms, 平均{}ms", 
            iterations, duration, avgTime);
        
        // 验证平均响应时间 < 50ms
        assertTrue(avgTime < 50, 
            String.format("画像缓存平均响应时间应 < 50ms, 实际: %.2fms", avgTime));
    }
    
    @Test
    void testConcurrentChatRequests() throws InterruptedException {
        int concurrency = 10;
        int requestsPerThread = 5;
        
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch latch = new CountDownLatch(concurrency);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        
        long start = System.currentTimeMillis();
        
        for (int i = 0; i < concurrency; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < requestsPerThread; j++) {
                        String conversationId = "perf-conv-" + threadId + "-" + j;
                        String customerId = "perf-user-" + threadId;
                        String groupId = "perf-group-" + threadId;
                        
                        aiChatService.chat(conversationId, customerId, groupId, "测试消息");
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    log.error("并发请求失败", e);
                    failureCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await();
        executor.shutdown();
        
        long duration = System.currentTimeMillis() - start;
        int totalRequests = concurrency * requestsPerThread;
        double avgTime = (double) duration / totalRequests;
        
        log.info("并发性能测试: {}个并发, 每个{}次请求, 总耗时{}ms, 平均{}ms", 
            concurrency, requestsPerThread, duration, avgTime);
        log.info("成功: {}, 失败: {}", successCount.get(), failureCount.get());
        
        // 验证成功率 > 95%
        double successRate = (double) successCount.get() / totalRequests;
        assertTrue(successRate > 0.95, 
            String.format("成功率应 > 95%%, 实际: %.2f%%", successRate * 100));
    }
    
    @Test
    void testResponseTimeP95() {
        int iterations = 100;
        List<Long> responseTimes = new ArrayList<>();
        
        for (int i = 0; i < iterations; i++) {
            String conversationId = "p95-conv-" + i;
            String customerId = "p95-user-" + i;
            String groupId = "p95-group";
            
            long start = System.currentTimeMillis();
            aiChatService.chat(conversationId, customerId, groupId, "测试消息");
            long duration = System.currentTimeMillis() - start;
            
            responseTimes.add(duration);
        }
        
        // 计算P95
        responseTimes.sort(Long::compareTo);
        int p95Index = (int) (iterations * 0.95);
        long p95 = responseTimes.get(p95Index);
        
        log.info("响应时间P95: {}ms", p95);
        
        // 验证P95 < 5000ms
        assertTrue(p95 < 5000, 
            String.format("响应时间P95应 < 5000ms, 实际: %dms", p95));
    }
}
```

- [ ] **Step 2: 运行性能测试**

```bash
cd ai-chat-service
mvn test -Dtest=AiChatPerformanceTest
```

Expected: Tests run: 3, Failures: 0

- [ ] **Step 3: 提交**

```bash
git add ai-chat-service/src/test/java/com/lx/governance/aichat/performance/AiChatPerformanceTest.java
git commit -m "test: add performance tests"
```

---

## Task 8: 验证监控指标

**Files:**
- Test: 监控端点

- [ ] **Step 1: 启动服务**

```bash
cd ai-chat-service
mvn spring-boot:run
```

- [ ] **Step 2: 访问健康检查端点**

```bash
curl http://localhost:8103/actuator/health
```

Expected: 返回健康状态JSON，包含Redis、RabbitMQ状态

- [ ] **Step 3: 访问Prometheus指标端点**

```bash
curl http://localhost:8103/actuator/prometheus
```

Expected: 返回Prometheus格式的指标，包含：
- `ai_chat_requests_total`
- `ai_context_cache_hit_total`
- `ai_profile_cache_hit_total`
- `ai_chat_response_time_seconds`
- 等

- [ ] **Step 4: 发送测试请求触发指标**

```bash
for i in {1..10}; do
  curl -X POST http://localhost:8103/api/chat \
    -H "Content-Type: application/json" \
    -d "{
      \"conversationId\": \"metrics-test-$i\",
      \"customerId\": \"metrics-user-001\",
      \"groupId\": \"metrics-group-001\",
      \"message\": \"测试消息 $i\"
    }"
  sleep 1
done
```

- [ ] **Step 5: 再次查看指标**

```bash
curl http://localhost:8103/actuator/prometheus | grep ai_chat
```

Expected: 
- `ai_chat_requests_total` 增加了10
- `ai_chat_response_time_seconds_count` 增加了10
- 其他相关指标有变化

- [ ] **Step 6: 验证指标正确性**

检查日志确认指标记录正常，无错误

---

## Task 9: 创建生产部署指南

**Files:**
- Create: `docs/deployment/production-deployment-guide.md`

- [ ] **Step 1: 创建部署指南**

```markdown
# AI对话流程重构 - 生产部署指南

## 1. 部署前准备

### 1.1 环境要求

- Java 17+
- Redis 7.0+
- MySQL 8.0+
- RabbitMQ 3.11+
- Milvus 2.3+

### 1.2 配置检查清单

- [ ] Redis连接配置正确
- [ ] MySQL数据库已创建
- [ ] RabbitMQ队列已创建
- [ ] Milvus collection已创建（user_memory_vectors_v3, dimension=1024）
- [ ] DeepSeek API密钥已配置
- [ ] 千问API密钥已配置

## 2. 部署步骤

### 2.1 停机维护（预计2-3小时）

**时间窗口**: 建议选择凌晨2:00-5:00低峰期

#### Step 1: 停止服务

```bash
# 停止ai-chat-service
systemctl stop ai-chat-service

# 停止vector-memory-service
systemctl stop vector-memory-service

# 停止user-profile-service
systemctl stop user-profile-service
```

#### Step 2: 备份数据

```bash
# 备份MySQL
mysqldump -u root -p governance_db > backup_$(date +%Y%m%d_%H%M%S).sql

# 备份Milvus collection
# 使用Milvus CLI或API导出collection

# 备份Redis（可选）
redis-cli --rdb /backup/redis_$(date +%Y%m%d_%H%M%S).rdb
```

#### Step 3: 部署新版本

```bash
# 部署user-profile-service
cd user-profile-service
mvn clean package -DskipTests
cp target/user-profile-service-1.0.0.jar /opt/services/
systemctl start user-profile-service

# 等待启动完成
sleep 30

# 部署vector-memory-service
cd ../vector-memory-service
mvn clean package -DskipTests
cp target/vector-memory-service-1.0.0.jar /opt/services/
systemctl start vector-memory-service

# 等待启动完成
sleep 30

# 部署ai-chat-service
cd ../ai-chat-service
mvn clean package -DskipTests
cp target/ai-chat-service-1.0.0.jar /opt/services/
systemctl start ai-chat-service
```

#### Step 4: 执行Embedding迁移

```bash
# 调用迁移API
curl -X POST http://localhost:8105/api/migration/start

# 监控迁移进度
tail -f /var/log/vector-memory-service/application.log | grep "迁移进度"
```

预计耗时：根据数据量，约1-2小时

#### Step 5: 验证部署

```bash
# 检查服务健康
curl http://localhost:8104/actuator/health  # user-profile-service
curl http://localhost:8105/actuator/health  # vector-memory-service
curl http://localhost:8103/actuator/health  # ai-chat-service

# 测试对话功能
curl -X POST http://localhost:8103/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "conversationId": "prod-test-001",
    "customerId": "test-user-001",
    "groupId": "test-group-001",
    "message": "测试消息"
  }'
```

## 3. 监控和告警

### 3.1 Prometheus配置

在Prometheus配置文件中添加：

```yaml
scrape_configs:
  - job_name: 'ai-chat-service'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['localhost:8103']
  
  - job_name: 'vector-memory-service'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['localhost:8105']
  
  - job_name: 'user-profile-service'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['localhost:8104']
```

### 3.2 关键指标

- `ai_chat_requests_total` - 对话请求总数
- `ai_chat_response_time_seconds` - 响应时间
- `ai_profile_cache_hit_total` / `ai_profile_cache_miss_total` - 画像缓存命中率
- `ai_context_cache_hit_total` / `ai_context_cache_miss_total` - 上下文缓存命中率
- `ai_budget_compression_total` - 预算压缩次数

### 3.3 告警规则

```yaml
groups:
  - name: ai-chat-alerts
    rules:
      - alert: ChatResponseTimeSlow
        expr: histogram_quantile(0.95, ai_chat_response_time_seconds_bucket) > 5
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "对话响应时间P95超过5秒"
      
      - alert: ProfileCacheHitRateLow
        expr: |
          ai_profile_cache_hit_total / 
          (ai_profile_cache_hit_total + ai_profile_cache_miss_total) < 0.8
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: "画像缓存命中率低于80%"
```

## 4. 回滚方案

如果部署出现问题，执行以下回滚步骤：

```bash
# 1. 停止新版本服务
systemctl stop ai-chat-service
systemctl stop vector-memory-service
systemctl stop user-profile-service

# 2. 恢复旧版本
cp /backup/ai-chat-service-old.jar /opt/services/ai-chat-service-1.0.0.jar
cp /backup/vector-memory-service-old.jar /opt/services/vector-memory-service-1.0.0.jar
cp /backup/user-profile-service-old.jar /opt/services/user-profile-service-1.0.0.jar

# 3. 恢复数据库（如果需要）
mysql -u root -p governance_db < backup_YYYYMMDD_HHMMSS.sql

# 4. 恢复Milvus collection（如果需要）
# 使用Milvus CLI或API导入备份

# 5. 启动旧版本服务
systemctl start user-profile-service
systemctl start vector-memory-service
systemctl start ai-chat-service

# 6. 验证服务正常
curl http://localhost:8103/actuator/health
```

## 5. 性能基准

部署后验证以下性能指标：

| 指标 | 目标值 | 验证方法 |
|------|--------|---------|
| 画像获取时间（缓存命中） | < 50ms | 查看Prometheus指标 |
| 记忆检索时间 | < 400ms | 查看Prometheus指标 |
| 对话响应P95 | < 5s | 查看Prometheus指标 |
| 画像缓存命中率 | > 80% | 查看Prometheus指标 |
| 上下文缓存命中率 | > 70% | 查看Prometheus指标 |

## 6. 常见问题

**Q: 迁移脚本失败怎么办？**  
A: 迁移脚本支持断点续传，重新调用迁移API即可从上次中断处继续。

**Q: Redis连接失败怎么办？**  
A: 服务会自动降级到MySQL，不影响主流程，但性能会下降。

**Q: Milvus查询超时怎么办？**  
A: 检查Milvus服务状态，必要时重启Milvus服务。
```

- [ ] **Step 2: 提交**

```bash
git add docs/deployment/production-deployment-guide.md
git commit -m "docs: add production deployment guide"
```

---

## Task 10: 最终验证和提交

**Files:**
- All services

- [ ] **Step 1: 运行所有测试**

```bash
# user-profile-service
cd user-profile-service
mvn clean test

# vector-memory-service
cd ../vector-memory-service
mvn clean test

# ai-chat-service
cd ../ai-chat-service
mvn clean test
```

Expected: All tests pass

- [ ] **Step 2: 构建所有服务**

```bash
cd user-profile-service && mvn clean package -DskipTests
cd ../vector-memory-service && mvn clean package -DskipTests
cd ../ai-chat-service && mvn clean package -DskipTests
```

Expected: All builds successful

- [ ] **Step 3: 验证监控端点**

启动所有服务，验证：
- `/actuator/health` 返回UP
- `/actuator/prometheus` 返回指标
- 日志无ERROR

- [ ] **Step 4: 最终提交**

```bash
git add .
git commit -m "feat: complete production enhancements and integration tests

- Add AiChatMetrics for Prometheus monitoring
- Add AiServicesHealthIndicator for health checks
- Add comprehensive integration tests
- Add performance tests (cache, concurrency, P95)
- Add production deployment guide
- Integrate metrics into AiChatService
- All tests passing, ready for production deployment"
```

- [ ] **Step 5: 创建发布标签**

```bash
git tag -a v2.0.0 -m "AI Chat Flow Refactor v2.0.0

Major changes:
- User profile Redis cache (200ms -> 50ms)
- Session context management with intent analysis
- Embedding v2 -> v3 upgrade (1536 -> 1024 dimensions)
- Token budget refactor with smart compression
- Production monitoring and health checks
- Comprehensive test coverage"

git push origin v2.0.0
```

---

## 验收标准

- [x] Prometheus监控指标完整
- [x] 健康检查端点正常工作
- [x] 集成测试覆盖完整流程
- [x] 性能测试验证关键指标
- [x] 生产部署指南完整
- [x] 所有测试通过
- [x] 服务可正常构建和启动

---

## 预计工作量

- Task 1-2: 监控配置 - 30分钟
- Task 3-4: 监控指标实现 - 1.5小时
- Task 5: 健康检查 - 30分钟
- Task 6-7: 集成和性能测试 - 2.5小时
- Task 8: 监控验证 - 30分钟
- Task 9: 部署指南 - 1小时
- Task 10: 最终验证 - 1小时

**总计**: 约8小时

---

## 生产部署时间表

| 阶段 | 预计时间 | 说明 |
|------|---------|------|
| 停止服务 | 5分钟 | 停止三个服务 |
| 备份数据 | 15分钟 | MySQL + Milvus + Redis |
| 部署新版本 | 20分钟 | 三个服务依次部署 |
| Embedding迁移 | 1-2小时 | 取决于数据量 |
| 验证测试 | 30分钟 | 功能和性能验证 |
| **总计** | **2-3小时** | 建议凌晨2:00-5:00执行 |
