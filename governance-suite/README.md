# governance-suite

一个面向学习和实验的多系统仓库，当前包含 4 条相互独立的主线：

1. **分布式系统治理** - Seata分布式事务、Nacos服务发现、Sentinel流控
2. **AI 记忆系统** - 画像、向量记忆、对话服务
3. **医生助手 Agent** - 任务型智能分析与人机协同
4. **医院挂号场景** - 预约系统、限流防刷

这 4 套系统可以单独启动，也可以共享同一套基础设施并并行运行。

## 📖 快速导航

- [📚 完整文档中心](docs/README.md) - 按主题分类的详细文档索引
- [🤖 AI 服务体系](docs/ai-services/README.md) - AI记忆、对话、Agent文档
- [🔄 患者对话 Agent 协同](docs/patient-dialog-agent/README.md) - Agent改造专题
- [🏗️ 架构文档](docs/architecture/README.md) - 系统架构与时序图
- [🔧 运维手册](docs/runbooks/MONITORING.md) - 监控、运维、排障指南
- [📊 技术分析](docs/analysis/) - 专题分析与优化方案

## 目录结构

```text
governance-suite/
├── README.md                    # 本文件：项目入口
├── CLAUDE.md                    # AI Agent 工作规则
├── docs/                        # 📚 文档中心（主要技术文档）
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
├── distributed/                 # 🔄 分布式系统治理服务
│   ├── gateway/                 # API 网关 (8080)
│   ├── user-service/            # 用户服务 (8084)
│   ├── order-service/           # 订单服务 (8081)
│   ├── payment-service/         # 支付服务 (8082)
│   ├── inventory-service/       # 库存服务 (8083)
│   ├── outbox-consumer/         # Outbox消费者 (8090)
│   └── appointment-service/     # 预约服务 (8089)
└── ai/                          # 🤖 AI 服务体系
    ├── ai-chat-service/         # AI对话服务 (8085)
    ├── user-profile-service/    # 用户画像服务 (8086)
    ├── vector-memory-service/   # 向量记忆服务 (8087)
    ├── doctor-assistant-agent/  # 医生助理Agent (8088)
    └── knowledge-base-service/  # 知识库服务 (8103)
```

## 通用前置条件

- **JDK 17**
- **Maven 3.9+**
- **Docker / Docker Compose**
- 可访问本机 `localhost`

### 启动基础设施

```bash
docker compose up -d
```

首次初始化或数据库脚本变化后，如需重建数据：

```bash
docker compose down -v
docker compose up -d
```

### 编译项目

```bash
mvn -T 1C clean package -DskipTests
```

## Part 1: 分布式系统治理

### 核心技术栈

- **Seata** - 分布式事务（AT模式）
- **Nacos** - 服务注册与配置中心
- **Sentinel** - 流量控制与熔断降级
- **RabbitMQ** - 消息队列（Outbox模式）
- **ShardingSphere** - 数据库读写分离

### 模块与端口

| 服务 | 端口 | 说明 |
|-----|------|-----|
| gateway | 8080 | API网关、认证、限流 |
| user-service | 8084 | 用户管理 |
| order-service | 8081 | 订单服务（Seata发起方） |
| payment-service | 8082 | 支付服务（Seata参与者） |
| inventory-service | 8083 | 库存服务（Seata参与者） |
| outbox-consumer | 8090 | 异步消息消费 |

### 基础设施端点

| 组件 | 地址 |
|-----|------|
| MySQL | `localhost:3306` |
| Redis | `localhost:36379` |
| RabbitMQ | `http://localhost:15672` (guest/guest) |
| Nacos | `http://localhost:8848/nacos` |
| Seata Server | `localhost:8091` |
| Sentinel | `http://localhost:8858` |

### 快速启动

最小启动（用户+订单）：

```bash
mvn -q -pl distributed/gateway spring-boot:run
mvn -q -pl distributed/user-service spring-boot:run
mvn -q -pl distributed/order-service spring-boot:run
```

完整启动（包含支付+库存+消息）：

```bash
bash sh/run/start-all-services.sh
```

### 快速验证

```bash
bash sh/test/test-create-order.sh
```

手动验证：

```bash
# 注册用户
curl -X POST http://localhost:8080/api/user/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"demo","password":"123456","displayName":"Demo User"}'

# 登录获取Token
TOKEN=$(curl -s -X POST http://localhost:8080/api/user/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"demo","password":"123456"}' | jq -r '.data.token')

# 创建订单
curl -X POST http://localhost:8080/api/order/order \
  -H "Authorization: $TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-1' \
  -d '{"productId":"p1","quantity":1,"amountCents":100}'
```

### 相关文档

- [架构文档](docs/architecture/README.md)
- [数据库读写分离分析](docs/analysis/数据库读写分离配置分析.md)
- [读写分离验证手册](docs/runbooks/MYSQL_READ_WRITE_SPLIT_VERIFICATION.md)

## Part 2: AI 记忆系统

### 核心能力

- **多轮对话管理** - SessionContext + Redis持久化
- **用户画像** - 基础档案、健康标签、偏好设置
- **短期记忆** - 最近对话片段，快速检索
- **长期记忆** - 重要健康信息，向量检索
- **智能降级** - 5级降级策略，熔断保护

### 模块与端口

| 服务 | 端口 | 说明 |
|-----|------|-----|
| ai-chat-service | 8085 | AI对话服务 |
| user-profile-service | 8086 | 用户画像服务 |
| vector-memory-service | 8087 | 向量记忆服务 |

### 基础设施

- MySQL (`localhost:3306`)
- Redis (`localhost:36379`)
- RabbitMQ (`http://localhost:15672`)
- Elasticsearch (`localhost:9200`)

### 快速启动

```bash
mvn -q -pl ai/user-profile-service spring-boot:run
mvn -q -pl ai/vector-memory-service spring-boot:run
mvn -q -pl ai/ai-chat-service spring-boot:run
```

或使用脚本：

```bash
bash sh/run/start-ai-services.sh
```

### 快速验证

```bash
bash sh/test/test-01-user-profile.sh
bash sh/test/test-02-vector-memory.sh
bash sh/test/test-03-ai-chat.sh
```

### 相关文档

- [AI 服务体系文档](docs/ai-services/README.md)
- [AI Chat Flow 详解](ai-chat-service/README-CHAT-FLOW.md)
- [降级指南](docs/ai-services/guides/degradation-guide.md)
- [动态 Token Budget](docs/analysis/dynamic-token-budget.md)
- [记忆检索重排序](docs/analysis/memory-retrieval-rerank.md)

## Part 3: 医生助手 Agent

### 核心理念

**"患者对话系统 + 按需触发 Agent + 人工协同闭环"**

- **ai-chat-service**: 负责"聊"（对话编排）
- **doctor-assistant-agent**: 负责"办事"（任务分析）
- **医生工作台**: 负责"治理与执行"

### 模块与端口

| 服务 | 端口 | 说明 |
|-----|------|-----|
| doctor-assistant-agent | 8088 | 医生助理Agent |
| knowledge-base-service | 8103 | 知识库服务 |

### 首批场景

1. **PATIENT_FOLLOW_UP_ANALYSIS** - 患者随访分析
2. **RISK_STRATIFICATION_AND_ESCALATION** - 风险分层与转人工
3. **NEXT_FOLLOW_UP_ACTION_RECOMMENDATION** - 下一步动作建议

### 快速启动

```bash
mvn -q -pl ai/doctor-assistant-agent spring-boot:run
mvn -q -pl ai/knowledge-base-service spring-boot:run
```

### 相关文档

- [患者对话 Agent 协同系统](docs/patient-dialog-agent/README.md)
- [演进计划完整文档](docs/patient-dialog-agent/evolution-plan.md)
- [Doctor Assistant Agent 说明](doctor-assistant-agent/README.md)

## Part 4: 医院挂号场景

### 核心能力

- Redis Lua 原子化预约
- Gateway 限流防刷
- 热门科室缓存优化

### 模块与端口

| 服务 | 端口 | 说明 |
|-----|------|-----|
| appointment-service | 8089 | 预约服务 |

### 快速启动

```bash
mvn -q -pl distributed/gateway spring-boot:run
mvn -q -pl distributed/appointment-service spring-boot:run
```

### 快速验证

```bash
bash sh/test/test-appointment.sh
bash sh/test/test-grab-flow.sh
```

### 相关文档

- [热门科室缓存维护方案](docs/analysis/热门科室缓存维护方案.md)
- [限流过滤器优化总结](docs/analysis/AppointmentRateLimitFilter优化总结.md)

## 监控与可观测性

### Prometheus + Grafana

访问 Grafana: `http://localhost:3000` (admin/admin)

预配置的 Dashboard：

- **JVM Metrics** - 堆内存、GC、线程
- **AI Services** - 对话请求、降级、记忆检索
- **Appointment Services** - 预约成功率、限流

### 日志聚合 (ELK)

- **Kibana**: `http://localhost:5601`
- **Elasticsearch**: `http://localhost:9200`
- **Logstash**: `localhost:5044`

### 相关文档

- [监控总览](docs/runbooks/MONITORING.md)
- [Prometheus 实现说明](docs/runbooks/PROMETHEUS-IMPLEMENTATION.md)
- [Kibana Dashboard 使用指南](docs/runbooks/kibana-dashboard-guide.md)

## 脚本工具集

所有根目录脚本统一放在 `sh/`，按用途分类：

### 启停脚本 (`sh/run/`)

- `start-all-services.sh` - 启动所有分布式治理服务
- `stop-all-services.sh` - 停止所有服务
- `start-ai-services.sh` - 启动AI服务组
- `stop-ai-services.sh` - 停止AI服务组

### 测试脚本 (`sh/test/`)

- `test-create-order.sh` - 测试订单创建流程
- `test-appointment.sh` - 测试预约流程
- `test-01-user-profile.sh` - 测试用户画像
- `test-02-vector-memory.sh` - 测试向量记忆
- `test-03-ai-chat.sh` - 测试AI对话
- `test-grab-flow.sh` - 测试抢号流程
- `test-schedule-query.sh` - 测试排班查询

### 验证脚本 (`sh/verify/`)

- `verify-monitoring.sh` - 验证监控指标

## 端口总览

| 服务 | 端口 | 分组 |
|-----|------|------|
| gateway | 8080 | 网关 |
| user-service | 8084 | 业务 |
| order-service | 8081 | 业务 |
| payment-service | 8082 | 业务 |
| inventory-service | 8083 | 业务 |
| appointment-service | 8089 | 业务 |
| outbox-consumer | 8090 | 业务 |
| ai-chat-service | 8085 | AI |
| user-profile-service | 8086 | AI |
| vector-memory-service | 8087 | AI |
| doctor-assistant-agent | 8088 | AI |
| knowledge-base-service | 8103 | AI |
| Nacos | 8848 | 基础设施 |
| Seata | 8091 | 基础设施 |
| Sentinel | 8858 | 基础设施 |
| MySQL | 3306 | 基础设施 |
| Redis | 36379 | 基础设施 |
| RabbitMQ | 15672 | 基础设施 |
| Elasticsearch | 9200 | 基础设施 |
| Kibana | 5601 | 基础设施 |
| Prometheus | 9090 | 基础设施 |
| Grafana | 3000 | 基础设施 |

## 常见问题

### 服务启动失败

1. 检查基础设施是否启动：`docker compose ps`
2. 检查端口占用：`lsof -i :8080`
3. 查看日志排查具体错误

### 分布式事务回滚

查看 Seata 日志：`docker compose logs seata-server`

### 监控数据缺失

运行验证脚本：`bash sh/verify/verify-monitoring.sh`

### AI 服务降级

查看降级级别：访问 `http://localhost:8085/actuator/health`

参考：[降级指南](docs/ai-services/guides/degradation-guide.md)

## 贡献与协作

本项目遵循 [CLAUDE.md](CLAUDE.md) 中定义的开发规则：

- 保持模块独立性，避免跨系统修改
- 文档优先放在 `docs/`，保持根目录简洁
- 脚本统一放在 `sh/`，按用途分类
- 验证优先用模块级 Maven 命令

## License

MIT License
