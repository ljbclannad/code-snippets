---
name: distributed-systems
description: Distributed transaction patterns, event sourcing, microservice architecture with Seata, Nacos, and Spring Cloud
---

# Distributed Systems Expert

You are an expert in distributed system patterns, particularly with Spring Cloud ecosystem. Use this skill when working with governance-suite or similar microservice projects.

## Architecture Overview (governance-suite)

### Services
- **gateway**: API Gateway with rate limiting, token validation
- **user-service**: Authentication, token management
- **order-service**: Order creation, Seata coordinator, Outbox publisher
- **inventory-service**: Stock deduction with optimistic locking
- **payment-service**: Payment processing
- **outbox-consumer**: Reliable message delivery to RabbitMQ

### Tech Stack
- Spring Boot 2.7.18, Spring Cloud 2021.0.9
- Nacos (service discovery)
- Seata (distributed transactions)
- RabbitMQ (event bus)
- Redis + Sentinel
- ShardingSphere-JDBC (database sharding)

## Key Patterns

### 1. Distributed Transaction (Seata AT Mode)
```java
@GlobalTransactional(rollbackFor = Exception.class)
public void createOrder(OrderRequest request) {
    // Order creation
    // Inventory deduction (remote call)
    // Payment preparation (remote call)
}
```

### 2. Outbox Pattern
- Store events in local DB within same transaction
- Separate consumer polls outbox table
- Publishes to RabbitMQ with at-least-once guarantee
- Status machine: PENDING → PUBLISHED → CONFIRMED

### 3. Idempotency & Anti-Replay
- Token-based idempotency (pre-allocated tokens)
- Timestamp + nonce for replay prevention
- Redis-based deduplication

### 4. Flash Sale with Redis Lua
```lua
-- Atomic stock check and deduction
local stock = redis.call('GET', KEYS[1])
if tonumber(stock) >= tonumber(ARGV[1]) then
    redis.call('DECRBY', KEYS[1], ARGV[1])
    return 1
end
return 0
```

### 5. Database Sharding
- ShardingSphere-JDBC with user_id as sharding key
- Consistent hashing for data distribution

### 6. Gateway Patterns
- Rate limiting (token bucket)
- HMAC token validation
- Request/response logging

## Common Issues
- **Seata timeout**: Adjust `seata.client.tm.default-global-transaction-timeout`
- **Outbox lag**: Monitor consumer processing rate
- **Sharding key missing**: Ensure user_id in all queries
- **Circuit breaker**: Configure Sentinel rules properly

## Best Practices
- Always use @GlobalTransactional for cross-service operations
- Implement compensation logic for business rollback
- Use Outbox pattern for eventual consistency
- Monitor Seata global transaction status
- Test idempotency thoroughly
