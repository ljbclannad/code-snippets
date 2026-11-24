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
├── evaluation-service/      # 独立评测、报告与版本对比 (8104)
└── knowledge-base-service/  # 知识库服务 (8103)
```

## 高风险随访提醒试点

登录患者在普通或流式聊天中命中已有高风险规则后，可由责任医生复核并发送站内提醒。试点默认关闭。启用配置、医生审批、患者收件箱和验收命令见[医生助手的高风险随访提醒说明](doctor-assistant-agent/README.md#高风险随访提醒)。

### 患者聊天入口与会话归属

`POST /api/chat` 和 `POST /api/chat/stream` 使用同一套决策。画像追问适用于需要补全信息的普通对话，知识问答转人工也经过 Agent 放行与待复核检查。两个入口均遵守 `ai-chat.agent.auto-fallback-to-direct-chat`。患者 SSE 每帧为单个 JSON 事件，普通聊天继续流式输出文本。医生助手路径仅输出固定进度、患者安全回复和患者可见证据，完成时返回原聊天会话编号。客户端收到 `ERROR` 即可结束本轮。

患者身份取自可信 Servlet principal 的 `PATIENT` 角色和全局唯一业务 ID。客户端可省略 `customerId`，显式传入时必须与登录身份一致。未登录并声明患者 ID 返回 401，冒用其他患者或会话返回 403。历史列表、消息、搜索、推荐同样校验患者身份。匿名聊天请省略 `customerId`，继续对话携带服务端签发的会话 Cookie 和上一轮的 `conversationId`。多实例部署需支持共享 HTTP 会话或会话亲和。匿名客户端丢失 Cookie 后需新建对话。

启动 `ai-chat-service` 后可访问 `http://localhost:8100/` 打开对话控制台。访客留空患者 ID 即可发送消息，页内续聊会沿用服务端返回的会话 ID 和会话 Cookie。登录后填写对应的患者 ID 可以加载历史。发送按钮和 `Ctrl/Cmd + Enter` 在请求处理期间共用同一提交保护。流式界面区分完成、服务报错、手动停止和连接提前结束。

已有数据库升级时，先停止旧聊天和医生助手入口写入，依次执行以下迁移，然后部署两个服务。归属回填取自聊天历史和医生端可追溯的任务，同一会话有多个主体时锁定该编号。旧版仅存在于 Redis 的上下文只可由原主体恢复。无法验证归属的旧匿名上下文请重新开始对话。

1. 确认已运行[随访提醒建表迁移](doctor-assistant-agent/sql/migrations/2026-11-23-high-risk-follow-up.sql)。
2. 执行[医生端会话关联迁移](doctor-assistant-agent/sql/migrations/2026-11-23-patient-conversation.sql)。
3. 执行[聊天端会话归属迁移](ai-chat-service/sql/migrations/2026-11-23-conversation-owner.sql)。迁移账号需能读取两个库；运行时聊天服务仅读取本库的归属记录。

查询复核进度时，在原会话发送“刚才那条医生看了吗？”或“随访提醒进度”。两个入口均读取当前患者在该会话的最近一条持久化复核任务，支持待处理、批准、拒绝、过期和查无记录。查询无需新建任务，也无需重用原来的 `requestId`。普通 Agent 分析和进度查询均要求两服务配置相同的 `FOLLOW_UP_SERVICE_TOKEN`，关闭高风险提醒试点后已有任务仍可查询。旧任务缺少可恢复的会话关联时，查询只能返回已关联的记录。


## 可重复评测系统

`evaluation-service` 提供版本化样本集、录制结果导入、确定性规则评分、JSON/Markdown 报告及基线/候选对比。
它默认仅监听本机，不调用模型或执行医疗工具，也不改变患者对话主链路。
现有聊天响应与 Agent 回放可通过导入协议参与评测；`ai-chat-service` 已支持显式测试批次的异步采集，
并提供从聊天入口执行固定问题、等待采集和导出报告的脚本。模型语义评分和自动优化暂未接入。

启动、合成示例和验收边界见 [评测系统说明](evaluation-service/README.md)。
聊天入口接入、脱敏范围与失败处理见 [真实调用采集说明](真实调用采集说明.md)。

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

当前这条链路已经开始平台化收敛：

1. `TaskTriggerRouter` 不只输出 doctor 场景判断，也会输出平台统一 `PlatformTaskRoutingDecision`
2. `AgentDispatchPolicy` 已退化为平台 `PlatformDispatchPolicyEngine` 的入口适配层
3. `doctor-assistant-agent` 内部 `SubAgentCoordinator` 已升级为 registry 驱动 selector，并支持 `SERIAL / PARALLEL`
4. `ReActAgentEngine` 会消费 selector 产出的执行 plan，当前已支持“并行计划、分步串行消费”的多 Agent 协作
5. 审批、恢复、run 快照、replay、tool 治理已经开始沉到 `agent-platform-common`

整合后详细时序图见：

- [AI Chat x Doctor Assistant Agent 整合时序图](智能对话与医生助手整合时序图.md)

## Part 1: AI 记忆系统

### 核心能力

- **多轮对话管理** - SessionContext + Redis持久化
- **用户画像** - 基础档案、健康标签、偏好设置
- **短期记忆** - 最近对话片段，快速检索
- **长期记忆** - 重要健康信息，向量检索
- **智能降级** - 5级降级策略，熔断保护

### 模块与端口

| 服务                  | 端口 | 说明         |
| --------------------- | ---- | ------------ |
| ai-chat-service       | 8100 | AI对话服务   |
| user-profile-service  | 8101 | 用户画像服务 |
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
# 主链路 4 个服务
mvn -q -pl ai/user-profile-service spring-boot:run
mvn -q -pl ai/vector-memory-service spring-boot:run
mvn -q -pl ai/ai-chat-service spring-boot:run
mvn -q -pl ai/doctor-assistant-agent spring-boot:run

# 如需启用 KB QA / 知识检索链路，再单独启动知识库服务
mvn -q -pl ai/knowledge-base-service spring-boot:run
```

说明：

- 当前仓库内未包含统一启动脚本，以上命令以各模块 `pom.xml` 和 `application.yml` 为准
- 默认对话主链路依赖：
  - `user-profile-service`
  - `vector-memory-service`
  - `ai-chat-service`
  - `doctor-assistant-agent`
- `knowledge-base-service` 可独立启动，按需支撑 KB QA 和 Agent 证据检索

### 环境变量

启动前需要配置 API Keys：

```bash
export DASHSCOPE_API_KEY=your-key
export DEEPSEEK_API_KEY=your-key
export DIFY_API_KEY=your-key
```

### 快速验证

当前仓库内未包含统一验证脚本，建议按模块执行最小验证：

```bash
# 编译单模块
mvn -f ai/user-profile-service/pom.xml test
mvn -f ai/vector-memory-service/pom.xml test
mvn -f ai/ai-chat-service/pom.xml test
mvn -f ai/doctor-assistant-agent/pom.xml test

# 健康检查
curl http://localhost:8101/actuator/health
curl http://localhost:8102/actuator/health
curl http://localhost:8100/actuator/health
curl http://localhost:8088/actuator/health
```

### 主链路说明

普通对话链路当前是：

1. 用户消息进入 `ai-chat-service`
2. `SessionContextService` 从 Redis 读取或重建会话上下文
3. `user-profile-service` 通过 RabbitMQ RPC 返回画像快照
4. `LayeredMemoryOrchestrator` 提取短期记忆，并按需向 `vector-memory-service` 检索长期记忆
5. 未命中升级条件时，直接调用大模型生成回复
6. 对话完成后异步执行历史落库、索引同步和记忆提取

**普通对话的模型调用**

- 同步与流式入口在新建会话或命中重判条件时分析本轮意图。会话写回保存摘要、计数和更新时间，复用入口已经取得的意图结果。首次普通对话在没有检索改写时需要两次前台生成模型调用。稳定会话复用上下文且未触发改写时只需一次。
- 长期记忆检索默认用规则生成查询。最近两条摘要提供不同于本轮的历史消息，且本轮有明确引用或短追问信号时，才尝试模型改写，例如在刚讨论体重变化后追问“还是没怎么掉”。命中此条件的简单追问也可进入长期记忆检索。模型结果缺失或未通过校验时使用规则查询。
- 通过 `ai.context.memory.rewrite.enabled=false` 对比规则基线。`complex-only` 默认关闭，设为 `true` 可额外按复杂度触发。`contextual-signals-enabled` 默认开启。改写开启且两个触发开关都关闭时，符合长期记忆检索条件的请求沿用全量改写模式。
- 评估调用收益时，按同一组合成问题比较每轮调用数和端到端耗时。规则检索和模型改写的候选集需要使用相同的数据与筛选条件，便于比较召回命中率。

升级到 Agent 的链路在上面的基础上多出：

1. `TaskTriggerRouter` 做任务升级判定
2. `AgentDispatchPolicy` 做灰度、熔断和放行判断
3. `doctor-assistant-agent` 返回患者安全回复、结构化分析结果和工作台载荷
4. Agent 成功后，`patientSafeReply` 会继续进入患者对话主链路，参与上下文更新、历史落库和记忆提取
5. 高风险场景进入人工复核闭环

路由器优先检查高风险医疗关键词，再处理画像补全和闲聊信号。例如，“你好，我胸痛，喘不上气”仍判定为风险升级；待补全画像或已有闲聊意图不会覆盖该判定。实际 Agent 调用仍受入口身份与运行策略约束。

同步聊天携带 `requestId` 时，同一实例内相同会话、相同请求 ID 的在途请求共享执行结果；成功结果写入 Redis 后供后续重试回放。立即完成、异步失败或启动异常均释放在途记录，失败请求可以重试。此机制不提供跨实例执行互斥，流式入口的保护仍需单独完善。


**降级策略（5级）：**

- L0: 完整画像 + 长期记忆 + 短期记忆
- L1: 核心画像 + 长期记忆 + 短期记忆
- L2: 核心画像 + 短期记忆
- L3: 仅短期记忆
- L4: 仅系统提示词

### 相关文档

- [AI Chat x Doctor Assistant Agent 整合时序图](智能对话与医生助手整合时序图.md)
- [Vector Memory Service 说明](vector-memory-service/README.md)

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

| 服务                   | 端口 | 说明          |
| ---------------------- | ---- | ------------- |
| doctor-assistant-agent | 8088 | 医生助理Agent |
| knowledge-base-service | 8103 | 知识库服务    |

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

- [AI Chat x Doctor Assistant Agent 整合时序图](智能对话与医生助手整合时序图.md)
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

该脚本会同时停止：

- `user-profile-service`
- `vector-memory-service`
- `ai-chat-service`
- `doctor-assistant-agent`

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

# 对话控制台回归（Node.js 18+）
node --test ai/ai-chat-service/src/test/js/chat-console.test.cjs

# 集成测试（需要基础设施运行）
bash sh/test/test-03-ai-chat.sh
```

## 贡献指南

请参考 [ai/AGENTS.md](AGENTS.md) 了解开发规范。

## 固定业务回归

通过 `python3 ai/evaluation-service/run_regression.py`（从 `governance-suite` 目录）运行固定离线回归，覆盖路由、请求幂等、依赖失败和现有审批能力。报告同时显示尚未补齐的场景，详见 [固定业务回归说明](evaluation-service/regression/README.md)。
