# AI 服务体系

AI 子目录当前承载三类能力，但运行主线已经演进为一条整合链路：

1. **AI 记忆系统**：负责患者对话、会话上下文、用户画像、短期/长期记忆
2. **医生助手 Agent**：负责对高价值患者对话做任务升级、结构化分析和人工复核承接
3. **知识库服务**：负责独立的医疗知识管理与语义检索

如果你是第一次进入这个目录，建议先看“整合后的主链路”，再按模块深入。

## 目录结构

```
ai/
├── ai-chat-service/         # AI对话服务 (8100)
├── user-profile-service/    # 用户画像服务 (8101)
├── vector-memory-service/   # 向量记忆服务 (8102)
├── doctor-assistant-agent/  # 医生助理Agent (8088)
└── knowledge-base-service/  # 知识库服务 (8103)
```

## 当前整合重点

### ai-chat-service x doctor-assistant-agent

当前最重要的链路不是单个模块，而是 `ai-chat-service` 与 `doctor-assistant-agent` 的协同：

1. `ai-chat-service` 先做会话上下文分析、画像装载、短期/长期记忆装载
2. `TaskTriggerRouter` 判断当前消息是否需要从“普通对话”升级到“任务型 Agent”
3. 命中升级条件后，`DoctorAssistantTaskRequestFactory` 会把上下文、画像、记忆快照一起打包
4. `doctor-assistant-agent` 输出三层结果：
   - `patientSafeReply`
   - `structuredResult`
   - `doctorWorkbenchPayload`
5. 如果 Agent 不可用、灰度未放行或响应异常，则自动回退普通对话

整合后详细时序图见：

- [AI Chat x Doctor Assistant Agent 整合时序图](AI_CHAT_AGENT_INTEGRATION_SEQUENCE.md)
- [患者对话 Agent 协同系统](../docs/patient-dialog-agent/README.md)

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
| ai-chat-service | 8100 | AI对话服务 |
| user-profile-service | 8101 | 用户画像服务 |
| vector-memory-service | 8102 | 向量记忆服务 |

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

### 主链路说明

普通对话链路当前是：

1. 用户消息进入 `ai-chat-service`
2. `SessionContextService` 从 Redis 读取或重建会话上下文
3. `user-profile-service` 通过 RabbitMQ RPC 返回画像快照
4. `LayeredMemoryOrchestrator` 提取短期记忆，并按需向 `vector-memory-service` 检索长期记忆
5. 未命中升级条件时，直接调用大模型生成回复
6. 对话完成后异步执行历史落库、索引同步和记忆提取

升级到 Agent 的链路在上面的基础上多出：

1. `TaskTriggerRouter` 做任务升级判定
2. `AgentDispatchPolicy` 做灰度、熔断和放行判断
3. `doctor-assistant-agent` 返回患者安全回复、结构化分析结果和工作台载荷
4. Agent 成功后，`patientSafeReply` 会继续进入患者对话主链路，参与上下文更新、历史落库和记忆提取
5. 高风险场景进入人工复核闭环

**降级策略（5级）：**

- L0: 完整画像 + 长期记忆 + 短期记忆
- L1: 核心画像 + 长期记忆 + 短期记忆
- L2: 核心画像 + 短期记忆
- L3: 仅短期记忆
- L4: 仅系统提示词

### 相关文档

- [AI 服务体系文档](../docs/ai-services/README.md)
- [AI Chat x Doctor Assistant Agent 整合时序图](AI_CHAT_AGENT_INTEGRATION_SEQUENCE.md)
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

### 当前协同边界

- `ai-chat-service` 保留患者入口、上下文、画像、记忆和普通对话主链路
- `doctor-assistant-agent` 只接收已经判定需要升级的任务型请求
- 患者侧只看 `patientSafeReply`，并且只有这部分会写回患者上下文、历史和记忆链路
- 医生侧和工作台消费 `structuredResult` 与 `doctorWorkbenchPayload`
- Agent 失败时默认回退普通对话，避免患者链路中断

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

- [AI Chat x Doctor Assistant Agent 整合时序图](AI_CHAT_AGENT_INTEGRATION_SEQUENCE.md)
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
lsof -i :8100
lsof -i :8101
lsof -i :8102
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
curl http://localhost:8100/actuator/prometheus | grep degradation
```

### 4. 向量检索无结果

检查 Milvus 连接：

```bash
docker logs gov-milvus
```

检查向量数据是否已导入：

```bash
curl http://localhost:8102/actuator/health
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
