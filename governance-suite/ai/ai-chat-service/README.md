# AI 对话服务

负责患者入口、身份与会话归属、普通对话、知识问答、任务升级，以及上下文和历史编排。服务端口为 `8100`，遵循[单实例规范](../AGENTS.md#部署范围单实例)。

跨服务调用顺序见[整合时序](../智能对话与医生助手整合时序图.md)，启动与调试见[本地运行与排障](../本地运行与排障.md)。本文维护患者入口契约和聊天服务内部行为。

## 患者聊天入口与会话归属

`POST /api/chat` 和 `POST /api/chat/stream` 使用同一套决策。画像追问适用于需要补全信息的普通对话，知识问答转人工也经过 Agent 放行与待复核检查。两个入口均遵守 `ai-chat.agent.auto-fallback-to-direct-chat`。患者 SSE 每帧为单个 JSON 事件，普通聊天继续流式输出文本。医生助手路径仅输出固定进度、患者安全回复和患者可见证据，完成时返回原聊天会话编号。客户端收到 `ERROR` 即可结束本轮。

患者身份取自可信 Servlet principal 的 `PATIENT` 角色和全局唯一业务 ID。客户端可省略 `customerId`，显式传入时必须与登录身份一致。未登录并声明患者 ID 返回 401，冒用其他患者或会话返回 403。历史列表、消息、搜索、推荐同样校验患者身份。匿名聊天请省略 `customerId`，继续对话携带服务端签发的会话 Cookie 和上一轮的 `conversationId`。匿名 HTTP 会话由当前聊天进程维护，进程重启后需重新建立匿名会话。匿名客户端丢失 Cookie 后需新建对话。

启动 `ai-chat-service` 后可访问 `http://localhost:8100/` 打开医疗对话工作台。桌面端左侧显示会话历史，窄屏可通过顶部「会话历史」打开。顶部「会话参数」和「调试状态」分别打开参数与调试抽屉，可查看会话 ID、Token 统计、Agent 阶段及日志。

对话区占满工作台的剩余空间，输入框固定在对话区底部。「放大对话」可让对话区占满视窗，点击「退出放大」即可恢复工作台。

会话列表顶部的「新建对话」会打开空白聊天，首条消息由服务端分配新会话 ID。点击历史条目可以回到旧会话。登录患者使用服务端历史，访客使用当前页面内的会话历史，刷新页面后清空。

「清空对话」只清空当前消息视图，不删除服务端历史或取消已登记的随访。同步请求和登记重试等待期间禁用清空，成功或失败后恢复，避免迟到回复形成孤立消息。流式输出期间清空会同时停止客户端接收。

页面使用服务内置的 `/favicon.svg` 图标。缺失静态资源返回 HTTP 404，不计为服务端未知异常；真正的未处理异常仍返回 HTTP 500。

助手的同步回复、流式回复和历史回复都支持 Markdown，包括加粗、列表、引用、代码、表格及 HTTP/HTTPS 链接。解析器随聊天服务提供。原始 HTML 会按文本显示，图片只显示替代文字。

工作台的同步和流式聊天请求统一使用默认群组 `health-group-001`。

访客留空患者 ID 即可发送消息，页内续聊会沿用服务端返回的会话 ID 和会话 Cookie。登录后在「会话参数」填写对应的患者 ID，即可在「会话历史」加载记录。发送按钮和 `Ctrl/Cmd + Enter` 在请求处理期间共用同一提交保护。流式界面区分完成、服务报错、手动停止和连接提前结束。

已有数据库升级的执行顺序见[数据库初始化说明](../数据库初始化说明.md#已有环境升级)。

查询复核进度时，在原会话发送“刚才那条医生看了吗？”或“随访提醒进度”。两个入口均读取当前患者在该会话的最近一条持久化复核任务，支持待处理、批准、拒绝、过期和查无记录。查询无需新建任务，也无需重用原来的 `requestId`。普通 Agent 分析和进度查询均要求两服务配置相同的 `FOLLOW_UP_SERVICE_TOKEN`，关闭高风险提醒试点后已有任务仍可查询。旧任务缺少可恢复的会话关联时，查询只能返回已关联的记录。

## 模型调用与上下文预算

- 同步与流式入口在新建会话或命中重判条件时分析本轮意图。会话写回保存摘要、计数和更新时间，复用入口已经取得的意图结果。首次普通对话在没有检索改写时需要两次前台生成模型调用。稳定会话复用上下文且未触发改写时只需一次。
- 长期记忆检索默认用规则生成查询。最近两条摘要提供不同于本轮的历史消息，且本轮有明确引用或短追问信号时，才尝试模型改写，例如在刚讨论体重变化后追问“还是没怎么掉”。命中此条件的简单追问也可进入长期记忆检索。模型结果缺失或未通过校验时使用规则查询。
- 通过 `ai.context.memory.rewrite.enabled=false` 对比规则基线。`complex-only` 默认关闭，设为 `true` 可额外按复杂度触发。`contextual-signals-enabled` 默认开启。改写开启且两个触发开关都关闭时，符合长期记忆检索条件的请求沿用全量改写模式。
- 评估调用收益时，按同一组合成问题比较每轮调用数和端到端耗时。规则检索和模型改写的候选集需要使用相同的数据与筛选条件，便于比较召回命中率。

意图分析使用 Spring AI 流式模型接口收集完整结果，整次模型调用执行 5 秒时限。超时会取消上游订阅；超时、调用失败、结果解析失败或无效意图均保留 `unknown`（未确定）状态，本轮跳过画像加载和自动画像补全，继续根据原始消息路由并回答，不再将概念问答改为年龄／性别追问。高风险关键词和明确转人工信号仍优先生效；下一轮会重新分析未确定的意图。持续输出也受该总时限约束。`ai_intent_analysis_fallbacks_total` 记录意图分析降级次数。

画像加载失败与成功取得空画像分别处理：服务调用失败时本轮跳过自动画像补全，模型仍可围绕当前问题请求必要信息；成功加载后确认缺失的字段继续按原有画像补全策略处理。同步和流式入口采用相同规则。

普通对话的 DeepSeek 流式调用使用 `spring.ai.openai.read-timeout`（默认 `60s`）。等待首个模型响应或相邻模型响应之间超过该时限时，上游订阅被取消，聊天入口返回 `ERROR` 结束本轮。

`ai.context.total-budget` 默认每轮 `8192` Token，用户消息按字符规则估算用量，并至少预留 `1024` Token。系统提示词可用预算为总预算减去上述预留，固定指令、区块标题及换行也计入其中。用户消息超过预留时自动压缩其他上下文；用户消息加固定指令已经超出预算时，返回分段发送提示。该预算按本地估算约束输入文本，模型输出仍由 `max_tokens` 单独控制，实际 Token 用量以提供商返回值为准。中等复杂度的相关记忆和会话历史基础预算分别为 `3000` 和 `2000` Token，其余复杂度按策略调整，受单轮总预算约束。

历史按最近问答轮次保留。最新回复超过历史预算时，优先保留用户问题，再保留带省略标记的回复片段，防止整段历史为空。同步与流式入口在路由确定后按需加载记忆；直接返回知识答案、等待复核和终止错误分支跳过记忆检索，知识问答升级 Agent 时继续加载所需记忆。

## 幂等、并发与后处理

普通同步对话进入执行保护后，同一实例内相同会话、相同 `requestId` 的在途请求共享执行结果，成功结果写入 Redis 供后续重试回放。同一实例内，同一会话的实际执行任务结束后，下一轮才可取得执行权；默认锁等待为 0，竞争失败返回会话忙，不是无界排队。调用方取消等待时，在途任务仍持有执行权。同步与流式入口之间的互斥保护仍需单独完善。

流式入口按同一会话的 `requestId` 共享在途执行，并从 `START` 回放已经产生的事件。成功事件在本地及 Redis 保留 `ai-chat.idempotency.response-ttl-minutes`（默认 10 分钟），重试期间模型调用、历史提交和配额结算各执行一次。客户端重连时应替换本轮展示内容，并沿用原 `requestId`。带请求 ID 的连接取消后，服务端继续完成该轮生成和结算；未携带请求 ID 的调用仍在断连时取消上游。失败或缺失 `DONE` 的请求可以重试。当前在途共享限于同一实例和流式入口，同步与流式互相切换，以及不同请求 ID 的会话互斥仍需后续完善。

同步与流式入口在接收请求时生成同一实例内单调递增的服务端时间，替换客户端的 `messageTimestamp`，该轮的路由、摘要和 Agent 结果写回共用这个值。上下文复用意图时也登记新轮次；读取、顺序校验和写入通过会话分段锁保护，旧轮次不能覆盖新轮次的待复核状态。此保护符合项目单实例部署范围。

后处理线程池拒绝任务时，调用线程尝试保存聊天历史并同步索引，继续返回已生成回复和执行配额结算。此降级分支跳过长期记忆提取。数据库或消息服务失败会记录日志，当前历史写入仍缺少持久化重试机制。

高风险随访与复核进度查询只合并流式在途执行，完成后重试重新查询医生服务；普通流式成功结果回放规则不适用于该分支。随访历史保存规则见[随访演示](../高风险随访演示.md)。

## Token 与配额

同步和流式生成结果均携带结构化 Token 用量，历史写入、历史回放及 Elasticsearch 同步保留 `promptTokens`、`completionTokens` 和 `totalTokens`。兼容旧结果中的 `metadata.usage` 文本；已知零值保留为 `0`，模型未提供或异常字段保留 `null`，不估算或补造用量。数据库已有字段无需迁移；先前丢失的历史用量无法自动回填。

配额结算以进程内账户存储中的最新值为准，账户初始化、重置、修改和结算共用存储锁，Redis 缓存快照不参与读改写。账户快照与存储对象隔离，按患者、会话和消息 ID 检查重复流水；同步与流式请求优先用 `requestId` 作为结算消息 ID。缓存清理失败不会撤销已完成结算。账户和流水仍为内存实现，保证范围为单实例，进程重启后的账户和流水恢复仍需补充持久化。

开启 `ai-chat.quota.enabled` 后，普通流式对话按 DeepSeek 回传的输入和输出 Token 结算，结算安排在 `DONE` 发出前。`DONE.tokenUsage` 同时供工作台显示。上游超时、未携带请求 ID 的调用提前取消或上游缺少有效用量时，无实际用量可供结算。带请求 ID 的连接断开后，继续完成的上游调用仍按实际用量结算。单轮上下文预算和账户周期配额分别配置。

## 服务级降级

服务级总开关 `ai-chat.degradation.enabled` 默认关闭，可用 `AI_CHAT_DEGRADATION_ENABLED=true` 开启。关闭时级别为 `NORMAL`，有效的手动降级请求返回 HTTP 409；依赖调用自身的超时和失败处理仍保留。

当前级别为 `NORMAL(0)`、`LIGHT(1)`、`MEDIUM(2)`、`HEAVY(3)`、`CRITICAL(4)`、`CIRCUIT_BREAK(5)`。以 [DegradationLevel](src/main/java/com/lx/governance/aichat/degradation/DegradationLevel.java)、[DegradationStrategyExecutor](src/main/java/com/lx/governance/aichat/degradation/DegradationStrategyExecutor.java) 和 [ChatTurnPolicy](src/main/java/com/lx/governance/aichat/service/ChatTurnPolicy.java) 为准，不能再用旧的 L0～L4 画像裁剪表解释服务级行为。

`NORMAL` 与 `LIGHT` 的记忆检索数量分别为 10 和 5，其他级别为 0；缓存回答和熔断返回还有独立条件。当前状态通过 `GET /api/degradation/status` 和 `ai_degradation_level` 观察。各级实际效果需分别验证，不能仅凭枚举描述认为所有链路都已覆盖。

## RabbitMQ RPC

画像与向量记忆 RPC 的回复队列由每个聊天实例独立创建，名称分别为 `ai.chat.user.profile.reply.<随机后缀>` 和 `ai.chat.vector.memory.reply.<随机后缀>`。队列非持久化、连接独占并自动清理；请求的 `replyTo` 与本实例监听队列保持一致，确保回复由发起请求的聊天进程接收。画像和记忆服务继续按 `replyTo` 回传，无需同时升级。连接中断时在途请求仍受原有 5 秒超时约束，重新连接后由 RabbitAdmin 重新声明队列；不保证跨进程重启恢复在途 RPC。

RabbitMQ 账号需要具备这些动态队列的声明、读取及回复写入权限。积压监控查询本实例真实队列，指标仍使用 `user_profile_reply`、`vector_memory_reply` 两个稳定标签。升级时先停止旧聊天进程，再启动新进程；旧固定队列不自动删除。该改动只隔离 RPC 回复，共享请求及异步队列仍需通过不同 vhost 等方式实现环境隔离。

已有双实例 Broker 测试仅作为回复队列隔离的兼容性回归保留，不作为多实例部署验收要求。测试使用临时独占请求队列和合成响应，不读取患者数据、不调用模型、不消费现有业务队列。在 `governance-suite` 目录执行：

```bash
# 先通过环境变量注入测试 Broker 凭据，勿将密码写入文件。
export AI_TEST_RABBITMQ_HOST=175.27.242.127
export AI_TEST_RABBITMQ_USERNAME=governance_app
export AI_TEST_RABBITMQ_PASSWORD="$RABBITMQ_PASSWORD"
# 可选：AI_TEST_RABBITMQ_PORT（默认 5672）、AI_TEST_RABBITMQ_VHOST（默认 /）
mvn -f ai/ai-chat-service/pom.xml -Dtest=RpcReplyIsolationTest,RpcReplyBrokerIntegrationTest,AiAsyncBacklogMetricsTest test
```

未配置 `AI_TEST_RABBITMQ_HOST` 时仅跳过真实 Broker 测试；无需外部依赖的双实例乱序回复回归测试照常运行。

## 验证与代码入口

所有命令从 `governance-suite` 根目录执行：

```bash
mvn -f ai/ai-chat-service/pom.xml test
node --test ai/ai-chat-service/src/test/js/chat-console.test.cjs
```

重点入口为 [AiChatService](src/main/java/com/lx/governance/aichat/service/AiChatService.java)、[AiChatStreamService](src/main/java/com/lx/governance/aichat/service/AiChatStreamService.java)、[ChatRequestIdempotencyService](src/main/java/com/lx/governance/aichat/service/ChatRequestIdempotencyService.java) 和 [HighRiskFollowUpChat](src/main/java/com/lx/governance/aichat/service/HighRiskFollowUpChat.java)。固定回归与外部依赖测试的边界见[测试分层](../人工智能服务测试分层.md)，评测采集见[真实调用采集说明](../真实调用采集说明.md)。
