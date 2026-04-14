---
name: rate-limiting
description: Rate limiting algorithms (Token Bucket, Leaky Bucket, Sliding Window) and implementation patterns
---

# Rate Limiting Expert

You are an expert in rate limiting algorithms and implementations. Use this skill when working with DistributedRateLimitingAlgorithm or implementing rate limiting.

## Algorithms (DistributedRateLimitingAlgorithm/)

### 1. Token Bucket (TokenBucket.java)
**Concept**: Tokens added at fixed rate, requests consume tokens

**Properties**:
- Allows bursts (up to bucket capacity)
- Smooth rate limiting over time
- Most commonly used (AWS, Nginx)

**Implementation**:
```java
public class TokenBucket {
    private final long capacity;      // Max tokens
    private final long refillRate;    // Tokens per second
    private long tokens;
    private long lastRefillTime;
    
    public synchronized boolean allowRequest() {
        refill();
        if (tokens > 0) {
            tokens--;
            return true;
        }
        return false;
    }
    
    private void refill() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastRefillTime;
        long tokensToAdd = (elapsed * refillRate) / 1000;
        tokens = Math.min(capacity, tokens + tokensToAdd);
        lastRefillTime = now;
    }
}
```

**Use Cases**:
- API rate limiting (allow bursts)
- Traffic shaping
- Request throttling

### 2. Leaky Bucket (LeakyBucket.java)
**Concept**: Requests enter bucket, processed at fixed rate

**Properties**:
- Smooth output rate (no bursts)
- Queue-based (requests wait)
- Good for traffic shaping

**Implementation**:
```java
public class LeakyBucket {
    private final Queue<Request> queue;
    private final long capacity;
    private final long leakRate;  // Requests per second
    
    public boolean allowRequest(Request request) {
        if (queue.size() >= capacity) {
            return false;  // Bucket full
        }
        queue.offer(request);
        return true;
    }
    
    // Background thread
    private void leak() {
        ScheduledExecutorService.scheduleAtFixedRate(() -> {
            Request request = queue.poll();
            if (request != null) {
                processRequest(request);
            }
        }, 0, 1000 / leakRate, MILLISECONDS);
    }
}
```

**Use Cases**:
- Network traffic shaping
- Message queue rate limiting
- Smooth request processing

### 3. Sliding Window Counter (SlidingWindowCounter.java)
**Concept**: Count requests in sliding time window

**Properties**:
- Accurate rate limiting
- No burst allowance
- Memory efficient

**Implementation**:
```java
public class SlidingWindowCounter {
    private final long windowSize;  // milliseconds
    private final long maxRequests;
    private final Map<Long, AtomicLong> windows;
    
    public boolean allowRequest() {
        long now = System.currentTimeMillis();
        long windowKey = now / windowSize;
        
        // Clean old windows
        windows.keySet().removeIf(key -> key < windowKey - 1);
        
        // Calculate current window count
        long currentCount = windows.computeIfAbsent(windowKey, 
            k -> new AtomicLong(0)).get();
        
        // Calculate previous window contribution
        long prevWindowKey = windowKey - 1;
        long prevCount = windows.getOrDefault(prevWindowKey, 
            new AtomicLong(0)).get();
        
        // Weighted count
        double prevWeight = 1.0 - ((now % windowSize) / (double) windowSize);
        long totalCount = currentCount + (long)(prevCount * prevWeight);
        
        if (totalCount < maxRequests) {
            windows.get(windowKey).incrementAndGet();
            return true;
        }
        return false;
    }
}
```

**Use Cases**:
- Precise rate limiting
- Analytics rate limiting
- API quota enforcement

### 4. Sliding Window with Timestamps (SlidingTimeWindowTimeStamp.java)
**Concept**: Store timestamps of requests, count in sliding window

**Properties**:
- Most accurate
- Higher memory usage
- Good for small request counts

**Implementation**:
```java
public class SlidingTimeWindowTimeStamp {
    private final long windowSize;
    private final long maxRequests;
    private final Queue<Long> timestamps;
    
    public synchronized boolean allowRequest() {
        long now = System.currentTimeMillis();
        
        // Remove old timestamps
        while (!timestamps.isEmpty() && 
               timestamps.peek() <= now - windowSize) {
            timestamps.poll();
        }
        
        if (timestamps.size() < maxRequests) {
            timestamps.offer(now);
            return true;
        }
        return false;
    }
}
```

**Use Cases**:
- Low-volume rate limiting
- Precise quota tracking
- User-specific rate limits

## Distributed Rate Limiting

### Redis-based Token Bucket
```lua
-- Lua script for atomic token bucket
local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local rate = tonumber(ARGV[2])
local now = tonumber(ARGV[3])

local bucket = redis.call('HMGET', key, 'tokens', 'last_refill')
local tokens = tonumber(bucket[1]) or capacity
local last_refill = tonumber(bucket[2]) or now

-- Refill
local elapsed = now - last_refill
local tokens_to_add = elapsed * rate / 1000
tokens = math.min(capacity, tokens + tokens_to_add)

-- Consume
if tokens >= 1 then
    tokens = tokens - 1
    redis.call('HMSET', key, 'tokens', tokens, 'last_refill', now)
    redis.call('EXPIRE', key, 60)
    return 1
else
    return 0
end
```

### Redis-based Sliding Window
```lua
-- Lua script for sliding window
local key = KEYS[1]
local window = tonumber(ARGV[1])
local limit = tonumber(ARGV[2])
local now = tonumber(ARGV[3])

-- Remove old entries
redis.call('ZREMRANGEBYSCORE', key, 0, now - window)

-- Count current window
local count = redis.call('ZCARD', key)

if count < limit then
    redis.call('ZADD', key, now, now)
    redis.call('EXPIRE', key, window / 1000)
    return 1
else
    return 0
end
```

## Algorithm Comparison

| Algorithm | Burst | Accuracy | Memory | Distributed |
|-----------|-------|----------|--------|-------------|
| Token Bucket | Yes | Good | Low | Easy (Redis) |
| Leaky Bucket | No | Excellent | Medium | Medium |
| Sliding Window Counter | Limited | Good | Low | Easy (Redis) |
| Sliding Window Timestamp | No | Excellent | High | Medium |

## Best Practices
- Use Token Bucket for most API rate limiting (allows bursts)
- Use Leaky Bucket for traffic shaping (smooth output)
- Use Sliding Window for precise quota enforcement
- Implement distributed rate limiting with Redis + Lua
- Add monitoring for rate limit hits
- Return 429 (Too Many Requests) with Retry-After header
- Consider per-user and global rate limits
- Use exponential backoff for retries
- Test under high concurrency
- Document rate limits in API documentation
