# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Purpose

A Spring Cloud microservices **learning project** demonstrating distributed systems patterns through a runnable order-fulfillment flow. It is intentionally experimental — not a production template.

## Build & Run

```bash
# Start all infrastructure first
docker compose up -d

# Build all modules (skip tests)
mvn -T 1C clean package -DskipTests

# Run individual services (each in its own terminal)
mvn -q -pl gateway spring-boot:run          # port 8080
mvn -q -pl order-service spring-boot:run    # port 8081
mvn -q -pl payment-service spring-boot:run  # port 8082
mvn -q -pl inventory-service spring-boot:run # port 8083
mvn -q -pl user-service spring-boot:run     # port 8084
mvn -q -pl outbox-consumer spring-boot:run  # port 8090

# Failure injection for lab experiments (run instead of normal start)
LAB_FAIL_INVENTORY_DEDUCT=true mvn -q -pl inventory-service spring-boot:run
LAB_FAIL_PAYMENT_PREPARE=true  mvn -q -pl payment-service spring-boot:run
LAB_FAIL_PAYMENT_CONFIRM=true  mvn -q -pl payment-service spring-boot:run
LAB_FAIL_OUTBOX_PUBLISH=true   mvn -q -pl outbox-consumer spring-boot:run
```

There are no automated tests to run. Verification is done via curl against the live services (see README.md for examples).

## Module Architecture

```
client → gateway(8080) → user-service(8084)
                       → order-service(8081) → inventory-service(8083)
                                             → payment-service(8082)
                                             → outbox-consumer(8090) → RabbitMQ
```

| Module | Role |
|---|---|
| `common` | Shared utilities: ApiResponse, tracing, auth token, Snowflake ID, exception handling |
| `gateway` | Token validation filter, IP rate limiting (Redis), Sentinel circuit breaking, route to services via Nacos |
| `user-service` | Register/login, HMAC token issuance, aggregates payment history via OpenFeign |
| `order-service` | `@GlobalTransactional` (Seata) spanning inventory+payment; outbox write; Redis Lua seckill |
| `payment-service` | Sharded by `user_id`; two-phase prepare/confirm; publishes `payment.confirmed` to RabbitMQ |
| `inventory-service` | Optimistic-lock stock deduction with retry loop (5 attempts) |
| `outbox-consumer` | Polls `t_outbox` every 1 s, publishes to RabbitMQ, exponential backoff on failure (max 5 retries → DEAD) |

## Key Patterns

### Cross-cutting (common module)
- **`ApiResponse<T>`** — every endpoint returns `{success, code, message, traceId, data}`.
- **`Trace` / `TraceIdFilter`** — MDC-propagated `traceId` injected into every request and response.
- **`AuthTokenService`** — stateless HMAC-SHA256 token (2-part Base64, not JWT). TTL 2 hours.
- **`SnowflakeIdGenerator`** — lock-free CAS, custom epoch 2024-01-01. Each service uses a different `workerId` (configured in `application.yml`).
- **`GlobalExceptionHandler`** — maps all exceptions to `ApiResponse`.

### Gateway auth flow
`AuthTokenGlobalFilter` (order=-90) validates the token, then injects `X-User-Id` / `X-Username` headers for downstream services. Public paths (`/api/user/register`, `/api/user/login`, `/actuator`) bypass validation.

### Distributed transaction (order-service)
`OrderAppService.createOrder()` is annotated `@GlobalTransactional`. It calls inventory-service (`InventoryClient.deduct`) and payment-service (`PaymentClient.prepare`) via OpenFeign. Seata AT mode provides automatic rollback across all three services if anything fails. An `t_outbox` row is written in the same local transaction.

### Outbox status machine
`NEW → SENT` (success) or `NEW → FAILED → ... → DEAD` (after 5 retries). The `outbox-consumer` polls in a scheduled task; no message broker needed for durability.

### Seckill (flash sale) — order-service v2 endpoint
A Redis Lua script atomically: checks stock, deduplicates the buyer, decrements stock, appends to `seckill:stream:orders`. A scheduled consumer reads the stream and writes to the DB. DB-level guard: `t_seckill_order_guard` unique constraint on `(user_id, sku_id)`.

### Idempotency
Every mutating operation uses `IdempotencyService.tryLock(key)` backed by Redis `SET NX` (1-hour TTL). Key formats: `idempotency:{requestId}` (orders), `idem:pay:prepare:{userId}:{orderNo}` (payments), `idem:stock:{orderNo}` (inventory).

### Data sharding (ShardingSphere JDBC)
- `t_order_{0,1}` — sharded by `order_no` hash mod 2.
- `t_payment_{0,1}` — sharded by `user_id` hash mod 2 (query locality for user payment history).

## Infrastructure Ports

| Service | Port |
|---|---|
| Nacos | 8848 |
| Seata | 8091 |
| Sentinel Dashboard | 8858 |
| RabbitMQ management | 15672 |
| Redis | 36379 |
| MySQL | 3306 |
