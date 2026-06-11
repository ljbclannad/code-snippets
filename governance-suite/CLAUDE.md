# CLAUDE.md

This file defines repository-specific rules for agents working in `governance-suite`.

## Purpose

`governance-suite` is a multi-system learning repository. It currently contains four independent tracks:

1. Distributed systems governance
2. AI memory system
3. Doctor assistant agent
4. Hospital appointment scenario

Treat these as separate subprojects that happen to share infrastructure and some common modules.

## Hard Rules

### Scope control

- MUST identify the target subproject before editing code.
- MUST keep searches, edits, builds, and validation scoped to the relevant module whenever possible.
- MUST NOT do repo-wide refactors unless the user explicitly asks for them.
- MUST NOT change unrelated files just because they are nearby.

### File organization

- MUST keep root-level documentation in `docs/`, except `README.md` and `CLAUDE.md`.
- MUST keep root-level scripts in `sh/`.
- MUST classify root-level scripts under `sh/run/`, `sh/test/`, or `sh/verify/` when practical.
- MUST preserve existing subproject-local documentation under each module unless the user explicitly asks to centralize it.
- MUST update references when moving files that are used by root documentation or scripts.

### Editing and safety

- MUST prefer minimal, targeted changes over broad rewrites.
- MUST preserve user changes and in-progress work unless explicitly told to revert them.
- MUST NOT use destructive cleanup commands such as `git reset --hard` or `git checkout --` unless explicitly requested.
- MUST NOT silently “fix” unrelated warnings or style issues while addressing another task.

### Validation

- MUST validate with the smallest command that proves the change.
- MUST prefer module-scoped Maven commands such as `mvn -f <module>/pom.xml ...`.
- MUST report exactly what was validated and what was not validated.
- MUST treat runtime verification as more authoritative than static assumptions for integration issues.

### Debugging discipline

- MUST find the failing layer before changing code for runtime issues.
- MUST verify across configuration, runtime logs, and actual behavior for distributed or infra problems.
- MUST NOT claim a fix works without evidence from logs, API calls, tests, or builds.

## Repo Conventions

### Root layout

- `README.md` is the entry document, not the full design manual.
- `docs/` stores root-level guides, analyses, and implementation notes.
- `sh/` stores root-level utility and verification scripts.
- `sh/run/` is for start/stop scripts.
- `sh/test/` is for scenario and business test scripts.
- `sh/verify/` is for environment or monitoring verification scripts.
- `docker-compose.yml` defines the shared local infrastructure.

### Documentation conventions

- Keep `README.md` concise and navigational.
- Put deep technical explanations, investigations, and implementation notes in `docs/`.
- Prefer adding links from `README.md` to detailed docs instead of expanding the homepage indefinitely.

### Script conventions

- Root scripts should be invoked from `sh/`.
- New root-level operational or verification scripts should be created under `sh/`, not at repository root.
- Prefer `sh/run/`, `sh/test/`, and `sh/verify/` over placing all scripts directly under `sh/`.
- If a script is specific to one subproject and already lives inside that subproject, keep it there unless the user asks otherwise.

## Validation Heuristics

### Maven

- Prefer:
  - `mvn -f common/pom.xml compile`
  - `mvn -f order-service/pom.xml spring-boot:run`
  - `mvn -f payment-service/pom.xml test`
- Avoid broad reactor builds when a single module build is enough.

### Runtime verification

- For service registration issues, check:
  - service config
  - runtime logs
  - registry state
- For Seata issues, check:
  - service logs
  - Seata server logs
  - transaction begin/branch/commit/rollback events
- For Nacos issues, check:
  - client startup logs
  - Nacos API or console
  - actual reachable instances
- For observability issues, verify both:
  - exporter endpoint/protocol configuration
  - actual collector behavior

## Build and Run

### Shared infrastructure

```bash
docker compose up -d
```

### Full build

```bash
mvn -T 1C clean package -DskipTests
```

### Common service runs

```bash
mvn -q -pl gateway spring-boot:run
mvn -q -pl user-service spring-boot:run
mvn -q -pl order-service spring-boot:run
mvn -q -pl payment-service spring-boot:run
mvn -q -pl inventory-service spring-boot:run
mvn -q -pl appointment-service spring-boot:run
mvn -q -pl outbox-consumer spring-boot:run
mvn -q -pl doctor-assistant-agent spring-boot:run
mvn -q -pl knowledge-base-service spring-boot:run
```

### Failure injection

```bash
LAB_FAIL_INVENTORY_DEDUCT=true mvn -q -pl inventory-service spring-boot:run
LAB_FAIL_PAYMENT_PREPARE=true  mvn -q -pl payment-service spring-boot:run
LAB_FAIL_PAYMENT_CONFIRM=true  mvn -q -pl payment-service spring-boot:run
LAB_FAIL_OUTBOX_PUBLISH=true   mvn -q -pl outbox-consumer spring-boot:run
```

## Verification Sources

- Use `README.md` as the top-level operator guide.
- Use `docs/` for detailed system docs and verification guides.
- Use `sh/` for root-level scripted checks.

There are no reliable repo-wide automated tests that prove the whole system. For many changes, verification is done through module compilation, live service startup, logs, and direct HTTP calls.

## Architecture Notes

### Distributed systems governance

- `gateway` provides routing, token validation, and rate limiting.
- `order-service` is the Seata initiator for the order flow.
- `inventory-service` and `payment-service` are transaction participants.
- `outbox-consumer` handles eventual consistency with RabbitMQ.

### Hospital appointment scenario

- `appointment-service` uses Redis Lua for reservation logic.
- Gateway-side rate limiting protects hot appointment flows.

### AI systems

- `ai-chat-service`, `user-profile-service`, and `vector-memory-service` are independent from the order flow.
- `doctor-assistant-agent` is its own system with separate docs and tests.

## Ports

| Service | Port |
|---|---|
| gateway | 8080 |
| order-service | 8081 |
| payment-service | 8082 |
| inventory-service | 8083 |
| user-service | 8084 |
| ai-chat-service | 8085 |
| user-profile-service | 8086 |
| vector-memory-service | 8087 |
| doctor-assistant-agent | 8088 |
| appointment-service | 8089 |
| outbox-consumer | 8090 |
| knowledge-base-service | 8103 |
| Nacos | 8848 |
| Seata | 8091 |
| Sentinel | 8858 |
| RabbitMQ Management | 15672 |
| Redis | 36379 |
| MySQL | 3306 |
| Milvus | 19530 |
