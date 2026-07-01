# message-guard

`message-guard` is an open-source oriented message consumption governance framework for Spring Boot 3 applications.

## Modules

- `message-guard-api`: public annotations and SPI contracts
- `message-guard-core`: lifecycle model, retry policy, orchestration primitives
- `message-guard-spring-boot-starter`: Spring Boot auto-configuration and developer entry points
- `message-guard-adapter-rabbitmq`: RabbitMQ integration
- `message-guard-adapter-kafka`: Kafka integration
- `message-guard-store-mysql`: MySQL governance persistence
- `message-guard-store-redis`: Redis assistive storage features
- `message-guard-alert-webhook`: webhook-based alert channel
- `message-guard-ops-api`: governance operations backend API
- `message-guard-ops-web`: lightweight admin web application
- `message-guard-demo`: runnable sample application

## First Milestone

The scaffold establishes a multi-module build, stable package boundaries, minimal public interfaces, and baseline tests. The next implementation slice is `RabbitMQ + MySQL` as the first complete governance path.
