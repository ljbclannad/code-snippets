# 减重管理医生助手 ReAct Agent

面向医生和健康管理团队的减重管理随访 Agent。当前项目聚焦：

- 体重进展分析
- 停滞期识别
- 饮食/运动依从性分析
- 结构化建议输出
- 高风险审批、证据链和降级治理

## 文档入口

- [产品导向说明](docs/减重管理医生助手产品概览.md)
- [工程导向说明](docs/减重管理医生助手工程指南.md)
- [流式链路架构说明](docs/医生助手智能体流式链路架构说明.md)

## 服务分层

- [`service.tool`](src/main/java/com/health/agent/service/tool) 提供具体工具调用的业务能力，包括患者资料查询、健康知识检索、体重停滞期识别、依从性分析和随访任务管理。例如，工具 `get_patient_profile` 通过 `PatientService.getProfile` 查询患者档案。
- [`service.agent`](src/main/java/com/health/agent/service/agent) 负责患者任务编排、会话与上下文管理、审批恢复和审计。从 `PatientTaskAgentService` 可以跟踪一次患者任务，从 `AgentSessionWorkflowService` 可以查看审批与恢复流程。

### 跟踪一次工具调用

以读取患者档案为例。开启下文的模拟模式后，第一轮模型回复会选择 `get_patient_profile`。调试时可以沿着这条路径设置断点。

| 步骤 | 看什么 | 源码入口 |
| --- | --- | --- |
| 1. 解析模型回复 | 完整 JSON 通过协议校验后，`finished=false` 分支将 `action.toolName`、`action.input` 和调用理由组装成 `ActionDecision`。流式入口也要等完整 JSON 到达。 | [ReActResponseParser.parse](src/main/java/com/health/agent/engine/ReActResponseParser.java) |
| 2. 取出动作 | 在引擎中定位 `thought.getAction()`。安全检查和重复动作检查通过后，引擎调用 `actionExecutor.execute(...)`。 | [ReActAgentEngine](src/main/java/com/health/agent/engine/ReActAgentEngine.java) |
| 3. 查找与执行 | 执行器按名称取出工具，检查权限、入参和风险等级。`get_patient_profile` 在启动时已由 `resolveExecutor` 绑定到 `getPatientProfile`；运行时由 `tool.getExecutor().execute(input)` 调用。 | [ActionExecutor](src/main/java/com/health/agent/engine/ActionExecutor.java) · [ToolRegistry](src/main/java/com/health/agent/tool/ToolRegistry.java) |
| 4. 查询与回写 | `PatientService.getProfile(customerId)` 查询档案。工具把业务数据、`patient_profile` 状态更新和来源证据放入 `ToolExecutionOutput`。执行器将其适配为 `PlatformToolOutput`，上下文服务再写入状态和证据。 | [PatientService](src/main/java/com/health/agent/service/tool/PatientService.java) · [输出适配器](src/main/java/com/health/agent/tool/PlatformToolOutputAdapter.java) · [上下文更新](src/main/java/com/health/agent/service/agent/AgentContextMutationService.java) |

改模型回复格式时看 `ReActResponseParserTest`，改工具绑定或业务返回值时看 `ToolRegistryTest`，改执行策略时看 `ActionExecutorTest`，改输出字段映射时看 `PlatformToolOutputAdapterTest`。从 `governance-suite` 根目录运行这四组测试：

```bash
mvn -pl ai/doctor-assistant-agent -am \
  -Dtest=ReActResponseParserTest,ToolRegistryTest,ActionExecutorTest,PlatformToolOutputAdapterTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

## 模型输入与调用成本

- 本版输入记为 `doctor-react-prompt-v2`，模型输出按 JSON 协议 `v1` 解析。运行快照记录实际使用的提示词版本。
- 患者任务首轮消息传任务标识与分析重点。患者原话、画像和记忆由每步的可见上下文统一提供。
- 模型只看到有执行器且通过当前用户、群组和租户访问检查的工具。角色首选工具从这个目录中选择。高风险工具的执行仍需审批。
- 发给模型的较早工具结果会把重复载荷改成同一结果内的引用。最新观察和独有事实原样保留，审批恢复与审计继续读取完整历史。
- 对比同一批任务时，可以用 `platform.tokens.prompt` 查看模型输入 Token，用 `agent.execution.steps` 和 `agent.execution.duration` 查看步数与执行耗时。

## 快速开始

### 环境要求

- Java 17+
- Maven 3.6+
- MySQL 8.0+
- Redis 7.0+

### 数据库初始化

```bash
mysql -u root -p < sql/schema.sql
```

如果本地数据库已是旧表结构，还需要执行迁移：

```bash
mysql -u root -p < sql/migrations/2026-05-30-add-agent-audit-columns.sql
```

### 编译运行

```bash
mvn -f pom.xml clean package
mvn -f pom.xml spring-boot:run
```

## 高风险随访提醒

普通聊天和 SSE 聊天共用现有的高风险关键词规则。试点开启时，命中的消息优先走随访复核，患者收到固定的安全指引。聊天服务仅在医生助手返回持久化任务编号后确认登记。匿名访客、责任医生缺失、限流、超时或灰度策略拦截时，患者会看到“当前无法确认随访复核已登记”。超时后可用相同请求编号查询同一轮结果。该场景不需要大模型，原文保存在医生复核记录中，收件箱只展示医生审核过的固定提醒。

### 患者任务的责任医生与查询契约

同步和流式分析入口均按服务端 `agent.follow-up-reminder.doctors` 的患者映射绑定责任医生，调用方可以省略 `doctorId`。普通任务缺少映射时返回 `SKIPPED`、`fallbackReason=DOCTOR_NOT_ASSIGNED` 和患者可见说明。上线普通 Agent 前也需配置该映射。两个分析接口仅供服务间调用，和进度接口共用 `FOLLOW_UP_SERVICE_TOKEN`。缺失或错误凭据返回 403，原始 Agent 事件仅供内部消费。

`GET /internal/patient-tasks/review-status` 接收 `customerId` 与 `conversationId`，使用与随访登记相同的 `X-Follow-Up-Token` 服务认证。返回值示例为 `{"status":"APPROVED","delivered":false}`。查询按最新审批任务编号排序，状态来自审批表，投递来自站内收件箱回执。普通复核获批可以没有投递。旧环境的迁移顺序见[患者聊天入口与会话归属](../README.md#患者聊天入口与会话归属)。

### 开启试点

1. 在业务库已有医生助手表的环境，向 `doctor_assistant` 库应用 [随访提醒迁移](sql/migrations/2026-11-23-high-risk-follow-up.sql)。新环境使用本模块的 `sql/schema.sql`。
2. 为聊天和医生助手两个服务注入相同的 `FOLLOW_UP_SERVICE_TOKEN`，仅允许服务端通过受保护的网络访问 `/internal/patient-tasks/high-risk-follow-up`。聊天服务通过 `ai-chat.agent.service-id` 在 Nacos 定位医生助手实例，沿用 `ai-chat.agent.timeout-ms`。两服务使用同一 Nacos 命名空间和分组，服务间经过网络时使用 HTTPS。
3. 在医生助手配置中按患者 ID 分配责任医生。示例配置如下，替换为试点身份系统中的实际标识。

   ```yaml
   agent:
     follow-up-reminder:
       doctors:
         "[patient-demo]": doctor-demo
   ```

4. 将两侧 HTTP 入口接入已认证的 Servlet 身份。患者请求要求 `PATIENT` 角色，医生请求要求 `DOCTOR` 角色，`getUserPrincipal().getName()` 返回对应业务 ID。聊天请求中的 `customerId` 必须与登录患者一致。仅转发自定义身份请求头不能建立这个身份。仓库默认启动配置没有安装医疗身份提供方。
5. 在聊天服务设置 `HIGH_RISK_FOLLOW_UP_ENABLED=true`。试点复用现有 Agent 的任务类型、灰度与熔断策略，任务类型应放行 `RISK_STRATIFICATION_AND_ESCALATION`。该分支有独立开关，关闭后聊天恢复原有高风险路由；已创建的任务仍可处理。

### 从聊天到收件箱

| 操作 | 接口 | 约束与结果 |
| --- | --- | --- |
| 患者发起对话 | `POST /api/chat` 或 `POST /api/chat/stream` | 传 `customerId`、`groupId`、`requestId`、`message`，已有会话继续传 `conversationId`。每一轮分配独立的 `requestId`，网络重试沿用原编号和原文。 |
| 患者追问进度 | 在原聊天入口发送“刚才那条医生看了吗？” | 普通任务和高风险提醒均可查询，状态查询不调用模型。 |
| 医生查看依据 | `GET /api/agent/pending-tasks?doctorId=…`，或 `GET /api/agent/pending-tasks/{taskId}` | 工具名为 `high_risk_follow_up_reminder`。责任医生可见触发原文、会话 ID 和拟发送的提醒。 |
| 医生作出决定 | `POST /api/agent/pending-tasks/{taskId}/approve` 或 `/reject` | 请求体示例为 `{"reviewComment":"已核实本轮随访请求"}`。批准即完成站内投递；拒绝即结案。重复相同决定返回成功，反向决定返回 409。此场景无需调用 `/resume`。 |
| 患者读取提醒 | `GET /api/patient/follow-up-reminders`（医生助手服务） | 按登录患者返回最近 50 条已投递记录，字段为 `receiptId`、`message`、`deliveredAt`。翻页时将末条的 `receiptId` 作为 `beforeId`。 |

收件箱中的一条记录即站内投递回执，不能据此推断患者已阅读。工作台与患者端沿用同一个任务编号对账。聊天服务通过 `ai.agent.fallbacks` 的 `reason=high_risk_follow_up_unconfirmed` 统计无法确认登记的次数。

独立医生页 `/doctor.html` 使用 `GET /api/doctor/follow-up-tasks` 查询待办，使用 `GET /api/doctor/follow-up-tasks/{id}` 查询详情。提交到 `POST /api/doctor/follow-up-tasks/{id}/approve` 或 `/reject`，请求体为 `{"comment":"复核意见"}`。服务端从登录身份确认责任医生，拒绝待办时要求提供理由。

患者页面从聊天服务同源调用 `GET /api/patient/follow-up-progress?conversationId=…` 和 `GET /api/patient/follow-up-reminders`。聊天服务核对患者身份和会话归属后，使用服务间令牌查询医生服务的内部随访进度和收件箱。随访进度从该会话最近一条高风险随访读取，审批状态和投递标记分别返回。

### 验证

从 `governance-suite` 根目录运行以下命令，使用当前源码中的平台依赖构建两个模块。

```bash
mvn -pl ai/ai-chat-service,ai/doctor-assistant-agent -am \
  -Dtest=HighRiskFollowUpChatTest,HighRiskFollowUpIntegrationTest,AiChatServiceConcurrencyTest,AiChatStreamServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

集成测试默认用 H2 的 MySQL 模式，覆盖工作台审批、患者收件箱、并发创建和批准、拒绝与事务回滚。可用一个全新、隔离的 MySQL 测试库运行相同测试，传入 `-DfollowUpTest.jdbcUrl=jdbc:mysql://127.0.0.1:端口/测试库` 及 `-DfollowUpTest.user=测试用户`。测试会建表，使用合成身份与消息。测试账户密码可通过 `FOLLOW_UP_TEST_DB_PASSWORD` 注入。

## 重要补充文档

- 患者任务升级与 Agent 增强事项当前已收敛在仓库级平台/运行时设计文档中，当前目录下未单独维护 `PATIENT_TASK_AGENT_EXECUTION_TODO.md`
