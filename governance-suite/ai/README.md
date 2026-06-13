# AI 服务体系

AI 服务体系包含三个独立但相互协作的系统：

1. **AI 记忆系统** - 对话管理、用户画像、向量记忆
2. **医生助手 Agent** - 任务型智能分析与人机协同
3. **知识库服务** - 医疗知识检索与管理

## 目录结构

```
ai/
├── ai-chat-service/         # AI对话服务 (8085)
├── user-profile-service/    # 用户画像服务 (8086)
├── vector-memory-service/   # 向量记忆服务 (8087)
├── doctor-assistant-agent/  # 医生助理Agent (8088)
└── knowledge-base-service/  # 知识库服务 (8103)
```

## Part 1: AI 记忆系统

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

### 基础设施依赖

- MySQL (`localhost:3306`)
- Redis (`localhost:36379`)
- RabbitMQ (`http://localhost:15672`)
- Nacos (`http://localhost:8848`)
- Milvus (`localhost:19530`)

### 快速启动

从项目根目录执行：

```bash
# 方式 1: 使用脚本（推荐）
bash sh/run/start-ai-services.sh

# 方式 2: 手动启动各服务
mvn -q -pl ai/user-profile-service spring-boot:run
mvn -q -pl ai/vector-memory-service spring-boot:run
mvn -q -pl ai/ai-chat-service spring-boot:run
```

### 环境变量

启动前需要配置 API Keys：

```bash
export DASHSCOPE_API_KEY=your-key
export DEEPSEEK_API_KEY=your-key
export DIFY_API_KEY=your-key
```

### 快速验证

```bash
bash sh/test/test-01-user-profile.sh
bash sh/test/test-02-vector-memory.sh
bash sh/test/test-03-ai-chat.sh
```

### 架构说明

**对话流程：**

1. 用户消息 → ai-chat-service
2. 加载用户画像（user-profile-service）
3. 检索长期记忆（vector-memory-service）
4. 构建上下文 → 调用大模型
5. 返回响应 + 更新记忆

**降级策略（5级）：**

- L0: 完整画像 + 长期记忆 + 短期记忆
- L1: 核心画像 + 长期记忆 + 短期记忆
- L2: 核心画像 + 短期记忆
- L3: 仅短期记忆
- L4: 仅系统提示词

### 相关文档

- [AI 服务体系文档](../docs/ai-services/README.md)
- [AI Chat Flow 详解](ai-chat-service/README-CHAT-FLOW.md)
- [降级指南](../docs/ai-services/guides/degradation-guide.md)
- [动态 Token Budget](../docs/analysis/dynamic-token-budget.md)
- [记忆检索重排序](../docs/analysis/memory-retrieval-rerank.md)

## Part 2: 医生助手 Agent

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

从项目根目录执行：

```bash
mvn -q -pl ai/doctor-assistant-agent spring-boot:run
mvn -q -pl ai/knowledge-base-service spring-boot:run
```

### 相关文档

- [患者对话 Agent 协同系统](../docs/patient-dialog-agent/README.md)
- [演进计划完整文档](../docs/patient-dialog-agent/evolution-plan.md)
- [Doctor Assistant Agent 说明](doctor-assistant-agent/README.md)

## 监控与可观测性

### Prometheus 指标

所有 AI 服务暴露以下指标：

- JVM 指标（堆内存、GC、线程）
- HTTP 请求指标（QPS、延迟、错误率）
- 业务指标：
  - `ai_chat_requests_total` - 对话请求总数
  - `ai_chat_degradation_level` - 当前降级等级
  - `memory_retrieval_duration` - 记忆检索耗时
  - `memory_retrieval_results` - 记忆检索结果数

### 访问监控

- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000` (admin/admin)
  - Dashboard: "AI Services Metrics"

### 日志

启动脚本会将日志输出到：

```
logs/ai-services/
├── ai-chat-service.log
├── user-profile-service.log
└── vector-memory-service.log
```

查看日志：

```bash
tail -f logs/ai-services/*.log
```

## 停止服务

```bash
bash sh/run/stop-ai-services.sh
```

## 技术栈

### AI 记忆系统

- **Spring Boot 3.2.5**
- **Spring AI 1.0.0-M3** - OpenAI 兼容接口
- **Spring Cloud Alibaba 2022.0.0.0** - Nacos 服务发现
- **Redis** - 会话存储、短期记忆
- **Milvus** - 向量数据库
- **RabbitMQ** - 异步消息
- **Elasticsearch** - 对话历史搜索

### 医生助手 Agent

- **Spring Boot 3.2.5**
- **MyBatis-Plus 3.5.7** - 数据访问
- **Spring AI 1.0.0-M3** - Agent 编排
- **MySQL** - 任务和知识存储

## 常见问题

### 1. 服务启动失败

**检查基础设施：**

```bash
docker compose ps
```

确保以下容器运行：
- gov-mysql
- gov-redis
- gov-rabbitmq
- gov-nacos
- gov-milvus

**检查端口占用：**

```bash
lsof -i :8085
lsof -i :8086
lsof -i :8087
```

### 2. 服务注册到 Nacos 失败

访问 Nacos 控制台检查：`http://localhost:8848/nacos`

检查服务配置中的 Nacos 地址：
- `application.yml` 中的 `spring.cloud.nacos.discovery.server-addr`

### 3. AI 服务响应慢或降级

检查大模型 API 是否可用：

```bash
# 测试 DeepSeek API
curl https://api.deepseek.com/v1/models \
  -H "Authorization: Bearer $DEEPSEEK_API_KEY"
```

查看降级指标：

```bash
curl http://localhost:8085/actuator/prometheus | grep degradation
```

### 4. 向量检索无结果

检查 Milvus 连接：

```bash
docker logs gov-milvus
```

检查向量数据是否已导入：

```bash
curl http://localhost:8087/actuator/health
```

## 开发指南

### 本地开发

1. 启动基础设施：`docker compose up -d`
2. 编译：`mvn clean compile`
3. 启动单个服务：`mvn -q -pl ai/ai-chat-service spring-boot:run`
4. 修改代码后重启服务

### 调试

使用 IDE 调试模式启动：

```bash
# IDEA: Run → Debug → Edit Configurations
# 设置 Main class 和 Module classpath
```

或使用 Maven 调试参数：

```bash
mvn -q -pl ai/ai-chat-service spring-boot:run \
  -Dspring-boot.run.jvmArguments="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"
```

### 测试

```bash
# 单元测试
mvn -f ai/ai-chat-service/pom.xml test

# 集成测试（需要基础设施运行）
bash sh/test/test-03-ai-chat.sh
```

## 贡献指南

请参考项目根目录的 [CLAUDE.md](../CLAUDE.md) 和 [ai/CLAUDE.md](CLAUDE.md) 了解开发规范。
