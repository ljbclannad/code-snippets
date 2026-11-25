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

高风险登记和复核进度查询不回放已完成的流式回复，只合并同一实例中相同请求的在途执行。收到“无法确认登记”后，可以使用原 `requestId`、原会话和原文重试；完成后的每次重试都会重新查询医生服务，远端登记仍按原请求编号去重。普通对话的流式回放规则不变。

同步和流式随访问答均在返回前尝试保存到聊天历史，并直接创建或更新会话摘要，不调用意图模型或长期记忆提取。同一患者、会话和请求编号共用一条历史，确认结果更新先前的“无法确认”，后续临时故障不会覆盖已确认的历史回复；保存首次接收时间以防止旧轮重试覆盖新轮上下文。上下文中的随访任务编号与状态是该轮登记结果快照，实时审批进度仍从医生服务查询。历史保存或 Redis 写入失败会记录日志并继续返回安全指引，原请求重试可再次尝试保存；目前没有持久化后台补偿队列。

已有环境部署本次修复前，需要在 `governance` 库执行[随访问答历史迁移](ai-chat-service/sql/migrations/2026-11-25-follow-up-chat-history.sql)。新环境的 `init-databases.sql` 已包含这些字段与索引。此路径写入数据库历史供列表、详情和后续提示词读取，不触发长期记忆提取或 Elasticsearch 同步。

### 患者聊天入口与会话归属

`POST /api/chat` 和 `POST /api/chat/stream` 使用同一套决策。画像追问适用于需要补全信息的普通对话，知识问答转人工也经过 Agent 放行与待复核检查。两个入口均遵守 `ai-chat.agent.auto-fallback-to-direct-chat`。患者 SSE 每帧为单个 JSON 事件，普通聊天继续流式输出文本。医生助手路径仅输出固定进度、患者安全回复和患者可见证据，完成时返回原聊天会话编号。客户端收到 `ERROR` 即可结束本轮。

患者身份取自可信 Servlet principal 的 `PATIENT` 角色和全局唯一业务 ID。客户端可省略 `customerId`，显式传入时必须与登录身份一致。未登录并声明患者 ID 返回 401，冒用其他患者或会话返回 403。历史列表、消息、搜索、推荐同样校验患者身份。匿名聊天请省略 `customerId`，继续对话携带服务端签发的会话 Cookie 和上一轮的 `conversationId`。多实例部署需支持共享 HTTP 会话或会话亲和。匿名客户端丢失 Cookie 后需新建对话。

启动 `ai-chat-service` 后可访问 `http://localhost:8100/` 打开医疗对话工作台。聊天卡片提供示例问题、消息输入和流式状态。通过顶部导航可跳转到会话参数、会话历史和调试状态，查看会话 ID、Token 统计、Agent 阶段及日志。

助手的同步回复、流式回复和历史回复都支持 Markdown，包括加粗、列表、引用、代码、表格及 HTTP/HTTPS 链接。解析器随聊天服务提供。原始 HTML 会按文本显示，图片只显示替代文字。

工作台的同步和流式聊天请求统一使用默认群组 `health-group-001`。

访客留空患者 ID 即可发送消息，页内续聊会沿用服务端返回的会话 ID 和会话 Cookie。登录后在「会话参数」填写对应的患者 ID，即可在「会话历史」加载记录。发送按钮和 `Ctrl/Cmd + Enter` 在请求处理期间共用同一提交保护。流式界面区分完成、服务报错、手动停止和连接提前结束。

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

本地运行 AI 服务时，中间件主机默认为 `175.27.242.127`。可用 `AI_MIDDLEWARE_HOST` 统一覆盖，Docker 部署继续使用 Compose 注入的各服务地址。

| 中间件 | 端口 |
| --- | --- |
| MySQL | 3306 |
| Redis | 6379 |
| RabbitMQ（AMQP） | 5672 |
| Nacos | 8848（HTTP）、9848（客户端 gRPC） |
| Elasticsearch | 9200 |
| Milvus | 19530 |

本地通过 IDEA、启动脚本或 Maven 启动时，Nacos 服务发现和配置中心默认使用命名空间 ID `ai-local`，可用环境变量 `NACOS_NAMESPACE` 覆盖。服务器 Docker Compose 显式使用 `public`。在 Nacos 控制台的服务列表或配置列表切换命名空间，即可分别查看两个环境。

空 MySQL 数据卷首次初始化时会创建 `ai-local`。在已有服务器上，开机执行 `/home/ubuntu/governance-ai/start-all.sh` 会自动补建缺失的空间；Nacos 已运行时，也可以单独执行 `/home/ubuntu/governance-ai/start-all.sh --namespace-only`。此操作恢复命名空间 ID，空间内曾发布的配置需从 Nacos 导出文件或数据库备份还原。

聊天、向量记忆、知识库和医生助手配置了 Nacos 服务注册。画像服务关闭了服务发现，评测服务独立运行。MySQL、Redis、RabbitMQ、Elasticsearch 和 Milvus 由本地与服务器环境共享。

对话自动升级默认启用，可用 `AI_CHAT_AGENT_ENABLED=false` 关闭。聊天服务按 `ai-chat.agent.service-id`（默认 `doctor-assistant-agent`）从 Nacos 选择实例，通过 HTTP 调用同步分析、流式分析及复核接口。两服务必须使用同一个命名空间和分组（默认 `DEFAULT_GROUP`），并且配置相同的 `FOLLOW_UP_SERVICE_TOKEN`。部署到 Compose 时，为两个服务设置 `SPRING_CLOUD_NACOS_DISCOVERY_SERVER_ADDR=nacos:8848` 和 `NACOS_NAMESPACE=public`。固定地址的外部集成测试可使用 `ai-chat.agent.base-url` 覆盖服务发现。

在本地终端或 IDE 运行配置中注入服务器对应的环境变量。业务库使用 `GOVERNANCE_DB_PASSWORD`，医生助手库使用 `DOCTOR_DB_PASSWORD`，Redis 和 RabbitMQ 分别使用 `REDIS_PASSWORD` 和 `RABBITMQ_PASSWORD`。数据库账号默认分别为 `governance_app` 和 `doctor_app`，RabbitMQ 默认使用 `governance_app`。

一键启动脚本优先使用当前环境已有的凭据；连接默认服务器时，它会通过 SSH 从部署目录加载缺少的中间件密码。使用 SSH 密码登录时，在终端按提示输入。单独运行 Maven 时，可以先将四项凭据载入当前终端：

```bash
while IFS= read -r setting; do
  export "$setting"
done < <(ssh ubuntu@175.27.242.127 "grep -E '^(GOVERNANCE_DB_PASSWORD|DOCTOR_DB_PASSWORD|REDIS_PASSWORD|RABBITMQ_PASSWORD)=' /home/ubuntu/governance-ai/.env")
```

本地实例与服务器实例会访问同一套数据和消息队列；启动同一队列的消费者后，两边都有可能收到消息。外部集成测试请使用独立的 `AI_TEST_*` 配置。

画像与向量记忆 RPC 的回复队列由每个聊天实例独立创建，名称分别为 `ai.chat.user.profile.reply.<随机后缀>` 和 `ai.chat.vector.memory.reply.<随机后缀>`。队列非持久化、连接独占并自动清理；请求的 `replyTo` 与本实例监听队列保持一致，避免多实例竞争消费回复。画像和记忆服务继续按 `replyTo` 回传，无需同时升级。连接中断时在途请求仍受原有 5 秒超时约束，重新连接后由 RabbitAdmin 重新声明队列；不保证跨进程重启恢复在途 RPC。

RabbitMQ 账号需要具备这些动态队列的声明、读取及回复写入权限。积压监控查询本实例真实队列，指标仍使用 `user_profile_reply`、`vector_memory_reply` 两个稳定标签。滚动升级期间旧实例继续使用原固定队列；旧实例之间的竞争问题会持续到全部升级完成。旧固定队列不自动删除。该改动只隔离 RPC 回复，共享请求及异步队列仍需通过不同 vhost 等方式实现环境隔离。

双实例 Broker 回归测试使用临时独占请求队列和合成响应，不读取患者数据、不调用模型、不消费现有业务队列。在 `governance-suite` 目录执行：

```bash
# 先通过环境变量注入测试 Broker 凭据，勿将密码写入文件。
export AI_TEST_RABBITMQ_HOST=175.27.242.127
export AI_TEST_RABBITMQ_USERNAME=governance_app
export AI_TEST_RABBITMQ_PASSWORD="$RABBITMQ_PASSWORD"
# 可选：AI_TEST_RABBITMQ_PORT（默认 5672）、AI_TEST_RABBITMQ_VHOST（默认 /）
mvn -f ai/ai-chat-service/pom.xml -Dtest=RpcReplyIsolationTest,RpcReplyBrokerIntegrationTest,AiAsyncBacklogMetricsTest test
```

未配置 `AI_TEST_RABBITMQ_HOST` 时仅跳过真实 Broker 测试；无需外部依赖的双实例乱序回复回归测试照常运行。

### 快速启动

在 IntelliJ IDEA 中打开 `governance-suite`，从右上角运行配置选择 **AI 服务（全部启动）**，点击 ▶。脚本在 IDEA 终端中运行，需要能找到 `mvn` 和下文的两个模型密钥。需要 SSH 密码时，直接在该终端输入。启动脚本完成健康检查后退出，六个服务在后台运行；结束开发时运行 **AI 服务（全部停止）**。

在 `governance-suite` 目录配置好环境变量后执行：

```bash
bash sh/run/start-ai-services.sh --all
```

脚本依次启动画像、向量记忆、知识库、评测、医生助手、聊天服务，并检查各服务的 HTTP 健康端点。聊天页面为 `http://localhost:8100/`，每个服务的日志位于 `logs/ai-services/<服务名>.log`。停止整套应用可执行 `bash sh/run/stop-ai-services.sh --all`。

省略 `--all` 可仅启动四个主链路服务。中间件默认连接服务器；需要本地 Compose 基础设施时可设置 `AI_MIDDLEWARE_HOST=localhost`。

### 环境变量

启动前需要配置 API Keys：

```bash
export DASHSCOPE_API_KEY=your-key
export DEEPSEEK_API_KEY=your-key
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

普通同步对话进入执行保护后，同一实例内相同会话、相同 `requestId` 的在途请求共享执行结果，成功结果写入 Redis 供后续重试回放。同一实例内，同一会话的实际执行任务结束后，下一轮才可取得执行权。调用方取消等待时，在途任务仍持有执行权。跨实例和流式入口的互斥保护仍需单独完善。

流式入口按同一会话的 `requestId` 共享在途执行，并从 `START` 回放已经产生的事件。成功事件在本地及 Redis 保留 `ai-chat.idempotency.response-ttl-minutes`（默认 10 分钟），重试期间模型调用、历史提交和配额结算各执行一次。客户端重连时应替换本轮展示内容，并沿用原 `requestId`。带请求 ID 的连接取消后，服务端继续完成该轮生成和结算；未携带请求 ID 的调用仍在断连时取消上游。失败或缺失 `DONE` 的请求可以重试。当前在途共享限于同一实例和流式入口，多实例同时首次请求、同步与流式互相切换，以及不同请求 ID 的会话互斥仍需后续完善。

同步与流式入口在接收请求时生成同一实例内单调递增的服务端时间，替换客户端的 `messageTimestamp`，该轮的路由、摘要和 Agent 结果写回共用这个值。上下文复用意图时也登记新轮次；读取、顺序校验和写入通过会话分段锁保护，旧轮次不能覆盖新轮次的待复核状态。此保护作用于单实例，多实例共享 Redis 时仍需要分布式原子版本校验。

后处理线程池拒绝任务时，调用线程尝试保存聊天历史并同步索引，继续返回已生成回复和执行配额结算。此降级分支跳过长期记忆提取。数据库或消息服务失败会记录日志，当前历史写入仍缺少持久化重试机制。

同步和流式生成结果均携带结构化 Token 用量，历史写入、历史回放及 Elasticsearch 同步保留 `promptTokens`、`completionTokens` 和 `totalTokens`。兼容旧结果中的 `metadata.usage` 文本；已知零值保留为 `0`，模型未提供或异常字段保留 `null`，不估算或补造用量。数据库已有字段无需迁移；先前丢失的历史用量无法自动回填。

配额结算以进程内账户存储中的最新值为准，账户初始化、重置、修改和结算共用存储锁，Redis 缓存快照不参与读改写。账户快照与存储对象隔离，按患者、会话和消息 ID 检查重复流水；同步与流式请求优先用 `requestId` 作为结算消息 ID。缓存清理失败不会撤销已完成结算。账户和流水仍为内存实现，保证范围为单实例，重启后数据持久化及多实例统一账户需要另行建设。

意图分析使用 Spring AI 流式模型接口收集完整结果，整次模型调用执行 5 秒时限。超时会取消上游订阅；超时、调用失败、结果解析失败或无效意图均保留 `unknown`（未确定）状态，本轮跳过画像加载和自动画像补全，继续根据原始消息路由并回答，不再将概念问答改为年龄／性别追问。高风险关键词和明确转人工信号仍优先生效；下一轮会重新分析未确定的意图。持续输出也受该总时限约束。`ai_intent_analysis_fallbacks_total` 记录意图分析降级次数。

画像加载失败与成功取得空画像分别处理：服务调用失败时本轮跳过自动画像补全，模型仍可围绕当前问题请求必要信息；成功加载后确认缺失的字段继续按原有画像补全策略处理。同步和流式入口采用相同规则。

普通对话的 DeepSeek 流式调用使用 `spring.ai.openai.read-timeout`（默认 `60s`）。等待首个模型响应或相邻模型响应之间超过该时限时，上游订阅被取消，聊天入口返回 `ERROR` 结束本轮。

`ai.context.total-budget` 默认每轮 `8192` Token，用户消息按字符规则估算用量，并至少预留 `1024` Token。系统提示词可用预算为总预算减去上述预留，固定指令、区块标题及换行也计入其中。用户消息超过预留时自动压缩其他上下文；用户消息加固定指令已经超出预算时，返回分段发送提示。该预算按本地估算约束输入文本，模型输出仍由 `max_tokens` 单独控制，实际 Token 用量以提供商返回值为准。中等复杂度的相关记忆和会话历史基础预算分别为 `3000` 和 `2000` Token，其余复杂度按策略调整，受单轮总预算约束。

历史按最近问答轮次保留。最新回复超过历史预算时，优先保留用户问题，再保留带省略标记的回复片段，防止整段历史为空。同步与流式入口在路由确定后按需加载记忆；直接返回知识答案、等待复核和终止错误分支跳过记忆检索，知识问答升级 Agent 时继续加载所需记忆。

开启 `ai-chat.quota.enabled` 后，普通流式对话按 DeepSeek 回传的输入和输出 Token 结算，结算安排在 `DONE` 发出前。`DONE.tokenUsage` 同时供工作台显示。上游超时、未携带请求 ID 的调用提前取消或上游缺少有效用量时，无实际用量可供结算。带请求 ID 的连接断开后，继续完成的上游调用仍按实际用量结算。单轮上下文预算和账户周期配额分别配置。


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
  - `ai_degradation_level` - 当前降级等级
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
ssh ubuntu@175.27.242.127 'cd /home/ubuntu/governance-ai && sudo docker compose ps'
```

**检查端口占用：**

```bash
lsof -i :8100
lsof -i :8101
lsof -i :8102
```

### 2. 服务注册到 Nacos 失败

访问 Nacos 控制台检查：`http://175.27.242.127:8848/nacos`

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
ssh ubuntu@175.27.242.127 'cd /home/ubuntu/governance-ai && sudo docker compose logs --tail 100 milvus'
```

检查向量数据是否已导入：

```bash
curl http://localhost:8102/actuator/health
```

## 开发指南

### 本地开发

1. 确认服务器中间件已启动，并按上文配置连接凭据。
2. 编译：`mvn clean compile`
3. 启动单个服务：`mvn -q -pl ai/ai-chat-service spring-boot:run`
4. 修改代码后重启服务

### 调试

在 IntelliJ IDEA 中打开 `governance-suite`：

- **单独启动或调试**：在 `AiChatServiceApplication.main()` 左侧点击运行图标，选择 **Run** 或 **Debug**，即可只启动聊天服务。也可以在「AI 单服务调试」目录中选择同名配置。其他五个服务同样使用各自的启动类名。
- **同时调试六个服务**：选择 **AI 服务（全部调试）** 并点击 **Debug**。该组合复用上述六个单服务配置，每个 JVM 都有独立的调试会话和控制台。

`ai-chat-service` 的服务级降级总开关 `ai-chat.degradation.enabled` 默认关闭，IDEA、脚本和 Maven 共用此设置。按默认配置启动时，服务级别为 `NORMAL`，负载检查不会触发降级，`POST /api/degradation/manual` 对有效的手动降级请求返回 HTTP 409。可通过 `GET /api/degradation/status` 查看级别，指标 `ai_degradation_level` 的正常值为 0。需要演练降级时，设置 `AI_CHAT_DEGRADATION_ENABLED=true` 并重新启动聊天服务。

如果已经用「AI 服务（全部启动）」运行了服务，先运行「AI 服务（全部停止）」释放本地端口。模型密钥沿用本机环境变量。使用默认服务器且本机缺少中间件密码时，IDEA 通过 SSH 加载凭据。macOS 使用密码登录时会弹出 SSH 密码输入框，多个服务可以复用这次连接。评测服务独立启动时无需加载中间件密码。首次连接新服务器前先在终端确认其 SSH 主机指纹。

可在 `AiChatController.health()` 的返回语句打断点，待聊天服务启动后访问 `http://localhost:8100/api/chat/health`。调试多个服务时，在 IDEA 的 Debug 窗口切换对应会话；结束时在 IDEA 中停止这些调试进程。

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
