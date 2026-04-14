---
name: id-generation
description: Distributed ID generation strategies (Snowflake, Segment), runtime switching, and monitoring
---

# Distributed ID Generation Expert

You are an expert in distributed ID generation systems. Use this skill when working with Snowflake-demo or implementing ID generation strategies.

## Project: Snowflake-demo

**Tech Stack**: Java 17, Spring Boot 3.2.6, MyBatis, MySQL, Redis, Prometheus

**Key Features**:
- Snowflake ID generation (trend-increasing, distributed)
- Segment-based ID generation (strictly increasing)
- Runtime strategy switching without restart
- Gray rollout support (percentage-based traffic split)
- Health checks for worker lease & segment allocation
- Prometheus metrics for monitoring

## ID Generation Strategies

### 1. Snowflake Algorithm
**Structure** (64 bits):
```
| 1 bit (unused) | 41 bits (timestamp) | 10 bits (worker ID) | 12 bits (sequence) |
```

**Properties**:
- Trend-increasing (roughly time-ordered)
- Distributed (each worker independent)
- High throughput (4096 IDs/ms per worker)
- Time-dependent (requires clock synchronization)

**Implementation**:
```java
public synchronized long nextId() {
    long timestamp = System.currentTimeMillis();
    
    // Clock moved backwards
    if (timestamp < lastTimestamp) {
        throw new RuntimeException("Clock moved backwards");
    }
    
    // Same millisecond
    if (timestamp == lastTimestamp) {
        sequence = (sequence + 1) & sequenceMask;
        if (sequence == 0) {
            // Sequence overflow, wait for next ms
            timestamp = waitNextMillis(lastTimestamp);
        }
    } else {
        sequence = 0;
    }
    
    lastTimestamp = timestamp;
    
    return ((timestamp - epoch) << timestampShift)
         | (workerId << workerIdShift)
         | sequence;
}
```

**Challenges**:
- Clock synchronization (NTP drift)
- Worker ID allocation (must be unique)
- Clock rollback handling

### 2. Segment-based ID
**Structure**:
- Pre-allocate ID ranges from DB
- Each worker gets a segment (e.g., 1-1000, 1001-2000)
- Strictly increasing within segment

**Properties**:
- Strictly increasing (globally ordered)
- No clock dependency
- Lower throughput (DB allocation overhead)
- Better for scenarios requiring strict ordering

**Implementation**:
```java
public long nextId() {
    if (currentId >= maxId) {
        // Allocate new segment from DB
        synchronized (this) {
            if (currentId >= maxId) {
                Segment segment = allocateSegment();
                currentId = segment.getMinId();
                maxId = segment.getMaxId();
            }
        }
    }
    return currentId++;
}

// DB allocation
UPDATE id_segment 
SET max_id = max_id + step 
WHERE biz_type = ? 
RETURNING max_id - step AS min_id, max_id
```

**Optimization**:
- Double buffering (pre-fetch next segment)
- Dynamic step adjustment (based on usage rate)

### 3. Runtime Strategy Switching
**Use Case**: Migrate from Snowflake to Segment without downtime

**Implementation**:
```java
@Component
public class IdGeneratorRouter {
    @Value("${id.strategy}")
    private String strategy; // "snowflake" or "segment"
    
    @Value("${id.gray.percentage}")
    private int grayPercentage; // 0-100
    
    public long nextId(String bizType) {
        if (isGrayTraffic()) {
            return segmentGenerator.nextId(bizType);
        }
        return snowflakeGenerator.nextId();
    }
    
    private boolean isGrayTraffic() {
        return ThreadLocalRandom.current().nextInt(100) < grayPercentage;
    }
}
```

**Gray Rollout Process**:
1. Deploy new strategy code (inactive)
2. Set `id.gray.percentage=1` (1% traffic)
3. Monitor metrics (latency, error rate)
4. Gradually increase to 10%, 50%, 100%
5. Switch `id.strategy` to new strategy
6. Set `id.gray.percentage=0`

### 4. Health Checks
```java
@Component
public class IdGeneratorHealthIndicator implements HealthIndicator {
    @Override
    public Health health() {
        // Check worker ID lease (Snowflake)
        if (strategy.equals("snowflake")) {
            if (!hasValidWorkerLease()) {
                return Health.down()
                    .withDetail("reason", "Worker lease expired")
                    .build();
            }
        }
        
        // Check segment allocation (Segment)
        if (strategy.equals("segment")) {
            if (currentId >= maxId && !canAllocateSegment()) {
                return Health.down()
                    .withDetail("reason", "Cannot allocate segment")
                    .build();
            }
        }
        
        return Health.up().build();
    }
}
```

### 5. Monitoring (Prometheus)
```java
// Metrics
- id_generation_total: Counter (total IDs generated)
- id_generation_duration_seconds: Histogram (latency)
- id_segment_allocation_total: Counter (segment allocations)
- id_worker_lease_expiry_seconds: Gauge (time until lease expires)
- id_sequence_overflow_total: Counter (sequence overflows)

// Alerts
- High latency (p99 > 10ms)
- Frequent segment allocations (> 10/min)
- Worker lease expiring soon (< 5 min)
- Clock rollback detected
```

## Best Practices
- Use Snowflake for high throughput, trend-increasing IDs
- Use Segment for strict ordering requirements
- Implement double buffering for Segment strategy
- Monitor clock drift for Snowflake
- Use gray rollout for strategy migration
- Implement health checks for both strategies
- Set up alerts for anomalies
- Test clock rollback handling thoroughly
- Use Redis for worker ID lease management
- Document ID format for debugging
