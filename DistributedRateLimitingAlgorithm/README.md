# 分布式限流算法实现

## 项目简介

本项目提供了四种经典限流算法的 Java 实现，包括计数器、滑动窗口、令牌桶和漏桶算法。每个算法都包含完整的实现代码和可运行的测试示例，适合学习限流原理和实际应用。

## 技术栈

- **语言**: Java
- **依赖**: 仅使用 JDK 标准库
- **适用场景**: API 限流、流量控制、系统保护

## 算法实现

### 1. 计数器算法 (Counter)

**文件**: `RateLimiter.java`

**原理**: 在固定时间窗口内统计请求数量，超过阈值则拒绝请求。

**特点**:
- ✅ 实现简单，性能高
- ❌ 存在临界问题（窗口边界流量突刺）

**适用场景**: 对精度要求不高的简单限流

**核心代码**:
```java
public class RateLimiter {
    private final int limit;           // 请求上限
    private final long timeWindow;     // 时间窗口（毫秒）
    private int counter;               // 当前计数
    private long startTimeMillis;      // 窗口开始时间
    
    public boolean isAllowed() {
        long currentTimeMillis = System.currentTimeMillis();
        // 窗口过期，重置计数器
        if (currentTimeMillis - startTimeMillis > timeWindow) {
            counter = 0;
            startTimeMillis = currentTimeMillis;
        }
        // 判断是否超限
        if (counter < limit) {
            counter++;
            return true;
        }
        return false;
    }
}
```

**运行示例**:
```bash
javac RateLimiter.java
java RateLimiter
```

---

### 2. 滑动窗口算法 (Sliding Window)

#### 2.1 计数版本

**文件**: `SlidingWindowCounter.java`

**原理**: 将时间窗口划分为多个小格子，每个格子记录该时段的请求数，通过滑动窗口统计总请求数。

**特点**:
- ✅ 解决了固定窗口的临界问题
- ✅ 限流更加平滑
- ⚠️ 需要维护多个时间格子

**适用场景**: 需要较高精度的限流场景

**核心数据结构**:
```java
private final int[] buckets;          // 时间格子数组
private final long bucketDuration;    // 每个格子的时长
private long lastUpdateTime;          // 最后更新时间
```

#### 2.2 时间戳版本

**文件**: `SlidingTimeWindowTimeStamp.java`

**原理**: 记录每个请求的时间戳，滑动窗口内统计有效请求数。

**特点**:
- ✅ 精度最高
- ❌ 内存占用较大（需存储所有时间戳）

**适用场景**: 请求量不大但需要精确限流的场景

**核心数据结构**:
```java
private final Queue<Long> timestamps;  // 请求时间戳队列
```

**运行示例**:
```bash
javac SlidingWindowCounter.java
java SlidingWindowCounter

javac SlidingTimeWindowTimeStamp.java
java SlidingTimeWindowTimeStamp
```

---

### 3. 令牌桶算法 (Token Bucket)

**文件**: `TokenBucket.java`

**原理**: 以固定速率向桶中添加令牌，请求需要消耗令牌，桶满时令牌溢出。

**特点**:
- ✅ 允许一定程度的突发流量
- ✅ 平滑限流，适应性强
- ⚠️ 需要定期补充令牌

**适用场景**: 需要应对突发流量的场景（如秒杀、促销）

**核心代码**:
```java
public class TokenBucket {
    private final int capacity;           // 桶容量
    private final int refillRate;         // 令牌补充速率（个/秒）
    private int tokens;                   // 当前令牌数
    private long lastRefillTime;          // 上次补充时间
    
    public synchronized boolean isAllowed() {
        refill();  // 先补充令牌
        if (tokens > 0) {
            tokens--;
            return true;
        }
        return false;
    }
    
    private void refill() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastRefillTime;
        int tokensToAdd = (int) (elapsed * refillRate / 1000);
        tokens = Math.min(capacity, tokens + tokensToAdd);
        lastRefillTime = now;
    }
}
```

**运行示例**:
```bash
javac TokenBucket.java
java TokenBucket
```

---

### 4. 漏桶算法 (Leaky Bucket)

**文件**: `LeakyBucket.java`

**原理**: 请求进入桶中排队，以固定速率从桶中流出处理，桶满时拒绝新请求。

**特点**:
- ✅ 强制限制流出速率，流量绝对平滑
- ❌ 无法应对突发流量
- ⚠️ 可能导致请求排队延迟

**适用场景**: 需要严格控制处理速率的场景（如消息队列、数据库写入）

**核心代码**:
```java
public class LeakyBucket {
    private final int capacity;           // 桶容量
    private final int leakRate;           // 漏出速率（个/秒）
    private int water;                    // 当前水量
    private long lastLeakTime;            // 上次漏水时间
    
    public synchronized boolean isAllowed() {
        leak();  // 先漏水
        if (water < capacity) {
            water++;
            return true;
        }
        return false;
    }
    
    private void leak() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastLeakTime;
        int leaked = (int) (elapsed * leakRate / 1000);
        water = Math.max(0, water - leaked);
        lastLeakTime = now;
    }
}
```

**运行示例**:
```bash
javac LeakyBucket.java
java LeakyBucket
```

## 算法对比

| 算法 | 实现复杂度 | 内存占用 | 精度 | 突发流量 | 适用场景 |
|------|-----------|---------|------|---------|---------|
| 计数器 | ⭐ | 低 | 低 | ❌ | 简单限流 |
| 滑动窗口（计数） | ⭐⭐ | 中 | 中 | ⚠️ | 通用限流 |
| 滑动窗口（时间戳） | ⭐⭐ | 高 | 高 | ⚠️ | 精确限流 |
| 令牌桶 | ⭐⭐⭐ | 低 | 高 | ✅ | 突发流量 |
| 漏桶 | ⭐⭐⭐ | 低 | 高 | ❌ | 平滑限流 |

## 快速开始

### 编译所有文件

```bash
cd DistributedRateLimitingAlgorithm
javac *.java
```

### 运行单个算法

```bash
# 计数器算法
java RateLimiter

# 滑动窗口（计数版）
java SlidingWindowCounter

# 滑动窗口（时间戳版）
java SlidingTimeWindowTimeStamp

# 令牌桶算法
java TokenBucket

# 漏桶算法
java LeakyBucket
```

每个类都包含 `main` 方法，可以直接运行查看效果。

## 使用示例

### 示例 1: API 接口限流

```java
// 限制每秒最多 100 个请求
TokenBucket limiter = new TokenBucket(100, 100);

@GetMapping("/api/data")
public ResponseEntity<?> getData() {
    if (!limiter.isAllowed()) {
        return ResponseEntity.status(429).body("Too Many Requests");
    }
    // 处理业务逻辑
    return ResponseEntity.ok(data);
}
```

### 示例 2: 用户级别限流

```java
// 每个用户每分钟最多 10 个请求
Map<String, RateLimiter> userLimiters = new ConcurrentHashMap<>();

public boolean checkUserLimit(String userId) {
    RateLimiter limiter = userLimiters.computeIfAbsent(
        userId, 
        k -> new RateLimiter(10, 60000)
    );
    return limiter.isAllowed();
}
```

### 示例 3: 秒杀场景

```java
// 令牌桶：容量 1000，每秒补充 100 个令牌
TokenBucket seckillLimiter = new TokenBucket(1000, 100);

@PostMapping("/seckill")
public ResponseEntity<?> seckill() {
    if (!seckillLimiter.isAllowed()) {
        return ResponseEntity.status(429).body("系统繁忙，请稍后再试");
    }
    // 秒杀逻辑
    return ResponseEntity.ok("秒杀成功");
}
```

## 分布式扩展

当前实现为单机版本，如需分布式限流，可以考虑：

### 1. Redis 实现

使用 Redis 的原子操作实现分布式限流：

```java
// 基于 Redis 的计数器限流
public boolean isAllowed(String key) {
    String redisKey = "rate_limit:" + key;
    Long count = redisTemplate.opsForValue().increment(redisKey);
    if (count == 1) {
        redisTemplate.expire(redisKey, 1, TimeUnit.SECONDS);
    }
    return count <= limit;
}
```

### 2. Lua 脚本

使用 Lua 脚本保证原子性：

```lua
-- 令牌桶 Lua 脚本
local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local rate = tonumber(ARGV[2])
local now = tonumber(ARGV[3])

local bucket = redis.call('HMGET', key, 'tokens', 'last_time')
local tokens = tonumber(bucket[1]) or capacity
local last_time = tonumber(bucket[2]) or now

local elapsed = now - last_time
local new_tokens = math.min(capacity, tokens + elapsed * rate / 1000)

if new_tokens >= 1 then
    redis.call('HMSET', key, 'tokens', new_tokens - 1, 'last_time', now)
    redis.call('EXPIRE', key, 10)
    return 1
else
    return 0
end
```

### 3. 网关层限流

在 API 网关（如 Spring Cloud Gateway、Kong）中配置限流策略。

## 性能优化建议

1. **使用无锁数据结构**: 对于高并发场景，考虑使用 `AtomicInteger`、`LongAdder` 等无锁结构
2. **本地缓存**: 结合本地缓存减少分布式调用开销
3. **异步处理**: 限流检查与业务处理分离，避免阻塞
4. **预热机制**: 系统启动时预先填充令牌桶

## 注意事项

1. **时钟回拨**: 生产环境需要处理系统时钟回拨问题
2. **线程安全**: 多线程环境下需要加锁或使用原子操作
3. **内存泄漏**: 使用 Map 存储限流器时注意及时清理过期数据
4. **精度权衡**: 根据实际需求选择合适的算法，不要过度追求精度

## 扩展阅读

- [Google Guava RateLimiter 源码分析](https://github.com/google/guava/wiki/RateLimiterExplained)
- [Redis 限流实现方案](https://redis.io/commands/incr#pattern-rate-limiter)
- [分布式限流设计与实现](https://www.infoq.cn/article/distributed-rate-limiting)

## 许可证

本项目仅供学习和演示使用。
