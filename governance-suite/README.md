# governance-suite

一个面向学习和实验的多系统仓库，当前包含 4 条相互独立的主线：

1. 分布式系统治理
2. AI 记忆系统
3. 医生助手 Agent
4. 医院挂号场景

这 4 套系统可以单独启动，也可以共享同一套基础设施并并行运行。

## 目录结构

```text
governance-suite/
├── README.md
├── CLAUDE.md
├── docker-compose.yml
├── docs/                     # 根目录文档
├── sh/                       # 根目录脚本
├── common/
├── gateway/
├── user-service/
├── order-service/
├── payment-service/
├── inventory-service/
├── outbox-consumer/
├── appointment-service/
├── ai-chat-service/
├── user-profile-service/
├── vector-memory-service/
└── doctor-assistant-agent/
```

## 根目录文档

- [监控总览](docs/runbooks/MONITORING.md)
- [Prometheus 实现说明](docs/runbooks/PROMETHEUS-IMPLEMENTATION.md)
- [API 文档](docs/api/README.md)
- [架构文档](docs/architecture/README.md)
- [时序图](docs/architecture/sequence-diagrams.md)

## 根目录脚本

所有根目录脚本已统一放到 `sh/`，并按用途分类：

- `sh/run/`
  - `start-all-services.sh`
  - `stop-all-services.sh`
  - `start-ai-services.sh`
  - `stop-ai-services.sh`
- `sh/test/`
  - `test-create-order.sh`
  - `test-appointment.sh`
  - `test-01-user-profile.sh`
  - `test-02-vector-memory.sh`
  - `test-03-ai-chat.sh`
  - `test-grab-flow.sh`
  - `test-schedule-query.sh`
- `sh/verify/`
  - `verify-monitoring.sh`

## 通用前置条件

- JDK 17
- Maven 3.9+
- Docker / Docker Compose
- 可访问本机 `localhost`

启动基础设施：

```bash
docker compose up -d
```

首次初始化或数据库脚本变化后，如需重建数据：

```bash
docker compose down -v
docker compose up -d
```

编译项目：

```bash
mvn -T 1C clean package -DskipTests
```

## Part 1: 分布式系统治理

### 模块

- `gateway` - 8080
- `user-service` - 8084
- `order-service` - 8081
- `payment-service` - 8082
- `inventory-service` - 8083
- `outbox-consumer` - 8090

### 基础设施

- MySQL: `localhost:3306`
- Redis: `localhost:36379`
- RabbitMQ: `http://localhost:15672`
- Nacos: `http://localhost:8848/nacos`
- Seata: `localhost:8091`
- Sentinel: `http://localhost:8858`

### 最小启动

```bash
mvn -q -pl gateway spring-boot:run
mvn -q -pl user-service spring-boot:run
mvn -q -pl order-service spring-boot:run
```

### 完整启动

```bash
mvn -q -pl gateway spring-boot:run
mvn -q -pl user-service spring-boot:run
mvn -q -pl order-service spring-boot:run
mvn -q -pl payment-service spring-boot:run
mvn -q -pl inventory-service spring-boot:run
mvn -q -pl outbox-consumer spring-boot:run
```

### 快速验证

手工验证：

```bash
curl -X POST http://localhost:8080/api/user/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"demo","password":"123456","displayName":"Demo User"}'

TOKEN=$(curl -s -X POST http://localhost:8080/api/user/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"demo","password":"123456"}' | jq -r '.data.token')

curl -X POST http://localhost:8080/api/order/order \
  -H "Authorization: $TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-1' \
  -d '{"productId":"p1","quantity":1,"amountCents":100}'
```

脚本验证：

```bash
bash sh/test/test-create-order.sh
```

### 更多文档

- [API 文档](docs/api/README.md)
- [架构文档](docs/architecture/README.md)
- [时序图](docs/architecture/sequence-diagrams.md)
- [读写分离分析](docs/analysis/数据库读写分离配置分析.md)
- [读写分离验证](docs/runbooks/MYSQL_READ_WRITE_SPLIT_VERIFICATION.md)

## Part 2: AI 记忆系统

### 模块

- `ai-chat-service` - 8085
- `user-profile-service` - 8086
- `vector-memory-service` - 8087

### 基础设施

- MySQL: `localhost:3306`
- RabbitMQ: `http://localhost:15672`
- Elasticsearch: `localhost:9200`

### 启动

```bash
mvn -q -pl user-profile-service spring-boot:run
mvn -q -pl vector-memory-service spring-boot:run
mvn -q -pl ai-chat-service spring-boot:run
```

### 快速验证

```bash
bash sh/test/test-01-user-profile.sh
bash sh/test/test-02-vector-memory.sh
bash sh/test/test-03-ai-chat.sh
```

### 更多文档

- [AI Chat Flow 文档](ai-chat-service/README-CHAT-FLOW.md)
- [动态 Token Budget](docs/analysis/dynamic-token-budget.md)
- [记忆检索设计](docs/analysis/memory-retrieval-rerank.md)
- [记忆检索总结](docs/reports/memory-retrieval-rerank-summary.md)

## Part 3: 医生助手 Agent

### 模块

- `doctor-assistant-agent` - 8088

### 启动

```bash
mvn -q -pl doctor-assistant-agent spring-boot:run
```

### 更多文档

- [医生助手 Agent README](doctor-assistant-agent/README.md)
- `doctor-assistant-agent/docs/`
- `doctor-assistant-agent/test/`

## Part 4: 医院挂号场景

### 模块

- `gateway` - 8080
- `user-service` - 8084
- `appointment-service` - 8089

### 启动

```bash
mvn -q -pl gateway spring-boot:run
mvn -q -pl user-service spring-boot:run
mvn -q -pl appointment-service spring-boot:run
```

### 快速验证

```bash
bash sh/test/test-schedule-query.sh
bash sh/test/test-grab-flow.sh
bash sh/test/test-appointment.sh
```

### 更多文档

- [挂号限流优化总结](docs/analysis/AppointmentRateLimitFilter优化总结.md)
- [挂号缓存维护方案](docs/analysis/热门科室缓存维护方案.md)
- [挂号缓存实施验证](docs/reports/热门科室缓存维护实施验证报告.md)
- `appointment-service/REDIS-TEST-REPORT.md`

## 监控与可观测性

### 基础设施

- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000`
- Jaeger: `http://localhost:16686`
- Kibana: `http://localhost:5601`

### 验证脚本

```bash
bash sh/verify/verify-monitoring.sh
```

### 监控文档

- [监控总览](docs/runbooks/MONITORING.md)
- [Prometheus 实现说明](docs/runbooks/PROMETHEUS-IMPLEMENTATION.md)
- [Kibana 看板指南](docs/runbooks/kibana-dashboard-guide.md)

## 常用命令

启动基础设施：

```bash
docker compose up -d
```

停止基础设施：

```bash
docker compose down
```

查看 Seata：

```bash
docker logs gov-seata
```

查看 Nacos：

```bash
docker logs gov-nacos
```

查看 RabbitMQ：

```bash
docker logs gov-rabbitmq
```

## 说明

- `README.md` 现在只做项目入口和快速导航。
- 设计说明、专题分析、实现记录统一放在 `docs/` 或子模块自己的 `docs/` 中。
- 根目录脚本统一放在 `sh/` 中，并按 `run/`、`test/`、`verify/` 分类。
