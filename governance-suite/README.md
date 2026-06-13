# governance-suite

一个面向学习和实验的多系统仓库，展示微服务架构下的核心技术能力。

## 项目概览

本仓库包含两个独立的技术体系，共享同一套基础设施：

### 🔄 [分布式系统治理](distributed/)

展示微服务架构下的服务治理、分布式事务、流量控制等核心能力。

**核心技术：** Seata、Nacos、Sentinel、RabbitMQ、ShardingSphere

**主要场景：**
- 分布式事务（订单-支付-库存）
- 服务注册与发现
- 流量控制与熔断降级
- 异步消息（Outbox 模式）
- 数据库读写分离
- 医院挂号抢购（Redis Lua）

👉 [查看详细文档](distributed/README.md)

### 🤖 [AI 服务体系](ai/)

包含 AI 记忆系统、医生助手 Agent、知识库服务等。

**核心技术：** Spring AI、Redis、Milvus、RabbitMQ、MyBatis-Plus

**主要场景：**
- 多轮对话管理（SessionContext）
- 用户画像与记忆检索
- 智能降级策略
- 任务型 Agent 协同
- 医疗知识检索

👉 [查看详细文档](ai/README.md)

## 快速开始

### 前置条件

- **JDK 17**
- **Maven 3.9+**
- **Docker / Docker Compose**

### 1. 启动基础设施

```bash
docker compose up -d
```

首次启动或需要重建数据：

```bash
docker compose down -v
docker compose up -d
```

### 2. 编译项目

```bash
mvn clean package -DskipTests
```

### 3. 启动服务

**分布式系统：**

```bash
bash sh/run/start-all-services.sh
```

**AI 服务：**

```bash
# 需要先配置 API Keys
export DASHSCOPE_API_KEY=your-key
export DEEPSEEK_API_KEY=your-key
export DIFY_API_KEY=your-key

bash sh/run/start-ai-services.sh
```

说明：该脚本默认启动 AI 主链路依赖的 `user-profile-service`、`vector-memory-service`、`ai-chat-service` 和 `doctor-assistant-agent`；`knowledge-base-service` 仍需按需单独启动。

### 4. 验证运行

**分布式系统：**

```bash
bash sh/test/test-create-order.sh
```

**AI 服务：**

```bash
bash sh/test/test-03-ai-chat.sh
```

## 目录结构

```
governance-suite/
├── README.md                    # 本文件：项目入口
├── docs/                        # 📚 文档中心
│   ├── ai-services/             # AI 服务体系专题
│   ├── patient-dialog-agent/    # 患者对话 Agent 协同专题
│   ├── architecture/            # 架构文档
│   ├── api/                     # API 文档
│   ├── runbooks/                # 运维手册
│   ├── analysis/                # 技术分析
│   └── reports/                 # 验证报告
├── sh/                          # 脚本工具集
│   ├── run/                     # 启停脚本
│   ├── test/                    # 测试脚本
│   └── verify/                  # 验证脚本
├── docker-compose.yml           # 基础设施编排
├── common/                      # 通用模块
├── distributed/                 # 🔄 分布式系统治理
│   ├── README.md                # 分布式系统详细文档
│   ├── CLAUDE.md                # 分布式系统开发规范
│   ├── gateway/                 # API 网关 (8080)
│   ├── user-service/            # 用户服务 (8084)
│   ├── order-service/           # 订单服务 (8081)
│   ├── payment-service/         # 支付服务 (8082)
│   ├── inventory-service/       # 库存服务 (8083)
│   ├── outbox-consumer/         # Outbox消费者 (8090)
│   └── appointment-service/     # 预约服务 (8089)
└── ai/                          # 🤖 AI 服务体系
    ├── README.md                # AI 服务详细文档
    ├── CLAUDE.md                # AI 服务开发规范
    ├── ai-chat-service/         # AI对话服务 (8100)
    ├── user-profile-service/    # 用户画像服务 (8101)
    ├── vector-memory-service/   # 向量记忆服务 (8102)
    ├── doctor-assistant-agent/  # 医生助理Agent (8088)
    └── knowledge-base-service/  # 知识库服务 (8103)
```

## 基础设施端点

| 组件 | 地址 | 用途 |
|-----|------|-----|
| MySQL | `localhost:3306` | 业务数据存储 |
| Redis | `localhost:36379` | 缓存、会话、分布式锁 |
| RabbitMQ | `http://localhost:15672` | 消息队列 (guest/guest) |
| Nacos | `http://localhost:8848/nacos` | 服务注册与配置中心 |
| Seata Server | `localhost:8091` | 分布式事务协调 |
| Sentinel | `http://localhost:8858` | 流量控制与监控 |
| Milvus | `localhost:19530` | 向量数据库 |
| Prometheus | `http://localhost:9090` | 指标收集 |
| Grafana | `http://localhost:3000` | 监控面板 (admin/admin) |

## 服务端口

### 分布式系统

| 服务 | 端口 |
|-----|------|
| gateway | 8080 |
| user-service | 8084 |
| order-service | 8081 |
| payment-service | 8082 |
| inventory-service | 8083 |
| outbox-consumer | 8090 |
| appointment-service | 8089 |

### AI 服务

| 服务 | 端口 |
|-----|------|
| ai-chat-service | 8100 |
| user-profile-service | 8101 |
| vector-memory-service | 8102 |
| doctor-assistant-agent | 8088 |
| knowledge-base-service | 8103 |

## 文档导航

### 📚 核心文档

- [分布式系统治理文档](distributed/README.md) - Seata、Nacos、Sentinel、Outbox
- [AI 服务体系文档](ai/README.md) - 对话管理、记忆检索、Agent 协同
- [完整文档中心](docs/README.md) - 按主题分类的详细文档索引

### 🏗️ 架构与设计

- [架构文档](docs/architecture/README.md) - 系统架构与时序图
- [患者对话 Agent 协同](docs/patient-dialog-agent/README.md) - Agent 改造专题
- [技术分析](docs/analysis/) - 专题分析与优化方案

### 🔧 运维与监控

- [监控手册](docs/runbooks/MONITORING.md) - Prometheus + Grafana
- [读写分离验证](docs/runbooks/MYSQL_READ_WRITE_SPLIT_VERIFICATION.md)
- [验证脚本](sh/verify/) - 环境和监控验证

### 📖 API 文档

- [API 文档中心](docs/api/) - 各服务 API 说明

## 开发指南

### 本地开发

1. 克隆仓库
2. 启动基础设施：`docker compose up -d`
3. 编译项目：`mvn clean compile`
4. 启动目标服务：`mvn -q -pl distributed/order-service spring-boot:run`

### 开发规范

- **分布式系统**：参考 [distributed/CLAUDE.md](distributed/CLAUDE.md)
- **AI 服务**：参考 [ai/CLAUDE.md](ai/CLAUDE.md)

### 常用命令

```bash
# 编译特定模块
mvn -f distributed/order-service/pom.xml compile
mvn -f ai/ai-chat-service/pom.xml compile

# 启动特定服务
mvn -q -pl distributed/order-service spring-boot:run
mvn -q -pl ai/ai-chat-service spring-boot:run

# 运行测试
bash sh/test/test-create-order.sh
bash sh/test/test-03-ai-chat.sh

# 验证监控
bash sh/verify/verify-monitoring.sh
```

## 监控与可观测性

### Prometheus 指标

所有服务暴露 `/actuator/prometheus` 端点，包含：
- JVM 指标（堆内存、GC、线程）
- HTTP 请求指标（QPS、延迟、错误率）
- 业务指标（订单、支付、对话等）

### Grafana Dashboard

访问 `http://localhost:3000`（admin/admin）查看预配置的 Dashboard：
- **JVM Metrics** - 堆内存、GC、线程
- **AI Services** - 对话请求、降级、记忆检索
- **Appointment Services** - 预约成功率、限流

### 日志

启动脚本会将日志输出到 `logs/` 目录：

```bash
# 分布式系统日志
tail -f logs/order-service.log

# AI 服务日志
tail -f logs/ai-services/ai-chat-service.log
```

## 常见问题

### 基础设施启动失败

```bash
# 检查容器状态
docker compose ps

# 查看日志
docker compose logs <service-name>

# 重建
docker compose down -v
docker compose up -d
```

### 服务启动失败

1. 检查端口占用：`lsof -i :<port>`
2. 检查基础设施是否运行：`docker compose ps`
3. 检查日志：`tail -f logs/<service>.log`
4. 检查 Nacos 注册：访问 `http://localhost:8848/nacos`

### 分布式事务失败

1. 检查 Seata Server：`docker logs gov-seata-server`
2. 检查服务日志中的 XID：`grep "xid" logs/order-service.log`
3. 检查数据库 `undo_log` 表是否存在

### AI 服务调用失败

1. 检查 API Key 是否配置：`echo $DEEPSEEK_API_KEY`
2. 检查大模型 API 是否可用
3. 查看降级指标：`curl http://localhost:8100/actuator/prometheus | grep degradation`

## 技术栈总览

### 分布式系统

- Spring Boot 3.2.5
- Spring Cloud 2023.0.1
- Spring Cloud Alibaba 2022.0.0.0
- Seata 2.0.0
- ShardingSphere 5.5.0
- Sentinel
- RabbitMQ

### AI 服务

- Spring Boot 3.2.5
- Spring AI 1.0.0-M3
- MyBatis-Plus 3.5.7
- Redis
- Milvus
- Elasticsearch

### 公共组件

- MySQL 8.0
- Redis 7.x
- RabbitMQ 3.x
- Nacos 2.x
- Prometheus + Grafana

## 贡献与反馈

这是一个学习和实验项目，欢迎提出改进建议。

## 许可证

MIT License
