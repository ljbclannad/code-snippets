---
name: redis-internals
description: Redis implementation patterns, data structures, NIO networking, and caching strategies
---

# Redis Internals Expert

You are an expert in Redis internals and implementation patterns. Use this skill when working with Redis-related code or implementing Redis-like systems.

## Key Implementations (redis/)

### 1. MiniRedisServer.java
**Pattern**: Single-threaded Reactor with NIO Selector

**Supported Commands**:
- Basic: PING, ECHO, GET, SET, DEL, EXISTS
- Numeric: INCR
- Expiration: TTL, EXPIRE
- Meta: INFO, QUIT

**Architecture**:
```java
// Event loop
while (true) {
    selector.select();
    for (SelectionKey key : selector.selectedKeys()) {
        if (key.isAcceptable()) handleAccept();
        if (key.isReadable()) handleRead();
        if (key.isWritable()) handleWrite();
    }
}
```

**RESP Protocol Parsing**:
- Simple strings: `+OK\r\n`
- Errors: `-Error message\r\n`
- Integers: `:1000\r\n`
- Bulk strings: `$6\r\nfoobar\r\n`
- Arrays: `*2\r\n$3\r\nGET\r\n$3\r\nkey\r\n`

**Expiration Strategy**:
- Lazy delete: Check on access
- Active expire cycle: Periodic scan (100ms interval)
  - Sample 20 random keys with TTL
  - Delete expired keys
  - Repeat if >25% expired

### 2. RedisZSetSkipList.java
**Data Structure**: Skip list for sorted sets

**Operations**:
- `zadd(key, score, member)`: O(log N)
- `zrange(key, start, stop)`: O(log N + M)
- `zrem(key, member)`: O(log N)
- `zscore(key, member)`: O(log N)

**Skip List Properties**:
- Multiple levels (typically log N)
- Level 0: All elements (sorted linked list)
- Higher levels: Express lanes for faster search
- Probabilistic balancing (p=0.25)

### 3. LogicalExpireCache.java
**Pattern**: Logical expiration for cache stampede prevention

**Key Idea**:
```java
class CacheEntry {
    Object data;
    long logicalExpireTime;
}

// On cache hit
if (entry.logicalExpireTime < now) {
    // Return stale data immediately
    // Async refresh in background
    executor.submit(() -> refreshCache(key));
    return entry.data;
}
```

**Benefits**:
- No cache stampede
- Always returns data (stale acceptable)
- Background refresh

### 4. PayCallbackDemo.java
**Pattern**: Payment callback handling

**Key Points**:
- Idempotency check (order_id + transaction_id)
- Status validation (only process PENDING orders)
- Atomic state transition
- Retry mechanism for failures

## Common Patterns

### 1. NIO Reactor Pattern
```java
// Single-threaded
Selector selector = Selector.open();
ServerSocketChannel server = ServerSocketChannel.open();
server.configureBlocking(false);
server.register(selector, SelectionKey.OP_ACCEPT);

// Multi-threaded (Boss-Worker)
// Boss: Accept connections
// Workers: Handle I/O
```

### 2. RESP Protocol Implementation
```java
// Parse command
String[] parts = parseRESP(buffer);
String command = parts[0].toUpperCase();

// Execute
Object result = switch (command) {
    case "GET" -> handleGet(parts[1]);
    case "SET" -> handleSet(parts[1], parts[2]);
    // ...
};

// Encode response
ByteBuffer response = encodeRESP(result);
```

### 3. Expiration Strategies
```java
// Lazy delete
public String get(String key) {
    Entry entry = store.get(key);
    if (entry != null && entry.isExpired()) {
        store.remove(key);
        return null;
    }
    return entry != null ? entry.value : null;
}

// Active expire
ScheduledExecutorService.scheduleAtFixedRate(() -> {
    int sampled = 0, expired = 0;
    for (String key : randomSample(20)) {
        sampled++;
        if (isExpired(key)) {
            store.remove(key);
            expired++;
        }
    }
    // Repeat if >25% expired
    if (expired > sampled * 0.25) {
        // Continue scanning
    }
}, 0, 100, MILLISECONDS);
```

### 4. Cache Stampede Prevention
```java
// Problem: Multiple threads refresh expired cache simultaneously
// Solution 1: Logical expiration (return stale, refresh async)
// Solution 2: Mutex (first thread refreshes, others wait)
// Solution 3: Probabilistic early expiration

// Probabilistic early expiration
double beta = 1.0;
double delta = currentTime - entry.createdTime;
double xfetch = delta * beta * Math.log(Math.random());
if (xfetch >= entry.ttl) {
    // Refresh cache
}
```

## Best Practices
- Use single-threaded Reactor for simplicity (like Redis)
- Implement both lazy and active expiration
- Use skip list for sorted sets (better than balanced trees for range queries)
- Prevent cache stampede with logical expiration
- Parse RESP protocol carefully (handle partial reads)
- Use ByteBuffer for efficient I/O
- Implement proper error handling and connection cleanup
