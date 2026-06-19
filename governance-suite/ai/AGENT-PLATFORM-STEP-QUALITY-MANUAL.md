# Agent Platform 完整监控与步骤质量判断说明书

## 1. 文档目的

本文档用于说明当前 `governance-suite/ai` 多 Agent 平台链路中，如何对一次用户请求的各个步骤进行：

- 运行监控
- 质量判断
- 问题定位
- 回放复盘
- 面试表达

本文档**严格基于当前代码现状**整理，不要求额外修改业务代码。

适用链路：

- `ai-chat-service`
- `doctor-assistant-agent`
- `agent-platform-common`
- `vector-memory-service`

相关代码入口：

- [AiChatService.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/ai-chat-service/src/main/java/com/lx/governance/aichat/service/AiChatService.java)
- [TaskTriggerRouter.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/ai-chat-service/src/main/java/com/lx/governance/aichat/service/TaskTriggerRouter.java)
- [DoctorAssistantTaskRequestFactory.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/ai-chat-service/src/main/java/com/lx/governance/aichat/service/DoctorAssistantTaskRequestFactory.java)
- [ReActAgentEngine.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/doctor-assistant-agent/src/main/java/com/health/agent/engine/ReActAgentEngine.java)
- [SubAgentCoordinator.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/doctor-assistant-agent/src/main/java/com/health/agent/engine/subagent/SubAgentCoordinator.java)
- [ActionExecutor.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/doctor-assistant-agent/src/main/java/com/health/agent/engine/ActionExecutor.java)
- [PlatformMetricsService.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/agent-platform-common/src/main/java/com/lx/governance/agentplatform/runtime/PlatformMetricsService.java)
- [RunReplayService.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/agent-platform-common/src/main/java/com/lx/governance/agentplatform/runtime/RunReplayService.java)
- [MemoryRetrievalService.java](/Users/lejinbo/LLM/code-snippets/governance-suite/vector-memory-service/src/main/java/com/lx/governance/vectormemory/service/MemoryRetrievalService.java)
- [LayeredMemoryOrchestrator.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/ai-chat-service/src/main/java/com/lx/governance/aichat/service/LayeredMemoryOrchestrator.java)

---

## 2. 当前平台可观测性基础

当前链路已经具备的统一观测基础：

- 统一追踪上下文：`PlatformTraceContext`
- 统一运行态模型：`Run / RunStep / RunEvent`
- 统一回放视图：`RunReplayView`
- 统一审计事件：`PlatformAuditEvent`
- 统一指标服务：`PlatformMetricsService`
- 统一审批模型：`ApprovalTask`

对应代码：

- [PlatformTraceContext.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/agent-platform-common/src/main/java/com/lx/governance/agentplatform/model/PlatformTraceContext.java)
- [Run.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/agent-platform-common/src/main/java/com/lx/governance/agentplatform/model/Run.java)
- [RunEvent.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/agent-platform-common/src/main/java/com/lx/governance/agentplatform/model/RunEvent.java)
- [RunReplayView.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/agent-platform-common/src/main/java/com/lx/governance/agentplatform/model/RunReplayView.java)
- [PlatformAuditEvent.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/agent-platform-common/src/main/java/com/lx/governance/agentplatform/model/PlatformAuditEvent.java)
- [ApprovalTask.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/agent-platform-common/src/main/java/com/lx/governance/agentplatform/model/ApprovalTask.java)

面试时可直接归纳为一句话：

`当前平台已经具备 trace + metrics + replay + audit 四套基础骨架，因此可以做到“知道发生了什么、为什么发生、出了问题如何复盘”。`

---

## 3. 说明书使用方式

对于每一个步骤，都从 5 个维度判断：

1. `步骤目标`
2. `当前代码中能看到什么`
3. `如何判断质量`
4. `常见异常信号`
5. `面试时怎么讲`

注意：

- 本文档中的“质量判断”分为两类
- `代码内已有信号`
- `基于当前输出可人工判断的信号`

也就是说，本文档可以直接用于面试说明，即使个别质量项当前还不是一个独立的数值指标。

---

## 4. 全链路步骤质量说明

### Step 1. 入口请求接入

步骤目标：

- 接收用户请求
- 分配 `conversationId / correlationId`
- 装配基础上下文

当前代码位置：

- [AiChatService.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/ai-chat-service/src/main/java/com/lx/governance/aichat/service/AiChatService.java)
- [ChatRequest.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/ai-chat-service/src/main/java/com/lx/governance/aichat/dto/ChatRequest.java)

当前能监控的内容：

- 请求进入次数
- 请求基础身份信息：`customerId / userId / tenantId / groupId`
- 会话维度：`conversationId`
- 关联维度：`correlationId`

质量判断：

- 是否成功建立唯一链路标识
- 是否把用户与租户维度正确带入后续链路
- 是否存在请求参数缺失或冲突

常见异常信号：

- 同一请求缺失 `conversationId`
- `customerId` 和 `sessionContext` 主体不一致
- 用户身份维度未透传，导致后续灰度与可见性判断失真

面试表达：

`入口层的关键不是模型效果，而是把一次请求变成可追踪、可治理的运行实体。`

### Step 2. Session Context 装载

步骤目标：

- 读取或创建当前会话上下文
- 识别当前话题、意图、复杂度、待追问项

当前代码位置：

- [SessionContextService.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/ai-chat-service/src/main/java/com/lx/governance/aichat/service/SessionContextService.java)
- [SessionContext.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/ai-chat-service/src/main/java/com/lx/governance/aichat/dto/SessionContext.java)

当前能监控的内容：

- 是否命中 Redis 中的会话上下文
- 是否触发意图重分析
- `currentTopic / userIntent / complexity / pendingQuestions`

质量判断：

- 会话状态是否连续
- 当前消息是否被正确理解为延续、追问还是切题
- `pendingQuestions` 是否过多残留，导致后续任务升级误判

常见异常信号：

- 新话题仍沿用旧意图
- 多轮后 `recentSummaries` 失真
- 复杂度长期偏高但上下文没有被压缩

面试表达：

`SessionContext 决定的是“这轮消息放在什么会话语境里理解”，它是入口质量的第一层基础。`

### Step 3. 用户画像装载

步骤目标：

- 获取稳定的用户长期画像

当前代码位置：

- [UserProfileClientFixed.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/ai-chat-service/src/main/java/com/lx/governance/aichat/client/UserProfileClientFixed.java)
- [ProfileDiagnosisService.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/ai-chat-service/src/main/java/com/lx/governance/aichat/service/ProfileDiagnosisService.java)

当前能监控的内容：

- 画像是否获取成功
- 画像是否不足以支撑当前回答
- 是否触发追问补全

质量判断：

- 当前画像字段是否足够支撑本轮问题
- 是否因为画像缺失导致模型编造
- 是否因为画像缺失而正确转入追问链路

常见异常信号：

- 画像为空但仍直接生成确定性回答
- 画像过旧且未触发补全
- 追问逻辑频繁触发，造成用户体验抖动

面试表达：

`画像层回答的是“这个人长期是什么样的人”，它不是对话上下文，而是长期背景。`

### Step 4. 记忆检索决策

步骤目标：

- 判断本轮是否需要长期记忆检索

当前代码位置：

- [LayeredMemoryOrchestrator.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/ai-chat-service/src/main/java/com/lx/governance/aichat/service/LayeredMemoryOrchestrator.java)
- [README-CHAT-FLOW.md](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/ai-chat-service/README-CHAT-FLOW.md)

当前能监控的内容：

- 是否触发检索
- 当前降级等级
- 是否只走短期记忆

质量判断：

- 该检索时有没有检索
- 不该检索时有没有过度检索
- 检索决策是否和 `complexity / topic / recent summaries` 一致

常见异常信号：

- 简单问候语仍触发长期记忆检索
- 强上下文依赖问题却没有检索
- 记忆服务降级后仍把回答说得非常确定

面试表达：

`记忆检索决策的核心不是“检索越多越好”，而是“只在需要的时候取对的记忆”。`

### Step 5. Query Rewrite 与 Self-Query 规划

步骤目标：

- 把用户口语问题改写成适合长期记忆召回的检索计划

当前代码位置：

- [RetrievalQueryRewriteService.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/ai-chat-service/src/main/java/com/lx/governance/aichat/service/RetrievalQueryRewriteService.java)
- [RetrievalRewriteClient.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/ai-chat-service/src/main/java/com/lx/governance/aichat/client/RetrievalRewriteClient.java)
- [README-CHAT-FLOW.md](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/ai-chat-service/README-CHAT-FLOW.md)

当前能监控的内容：

- `rewriteSource`
- `rewriteReason`
- `rewrittenQuery`
- `keywords`
- `filterExpression`
- `contextDependent`

质量判断：

- 改写后 query 是否更接近用户真实检索意图
- 是否保留了话题核心词
- `filterExpression` 是否合理，是否过度收窄

常见异常信号：

- LLM rewrite 生成空 query
- rule fallback 频繁触发
- filter 过窄导致召回数量过少
- filter 过宽导致召回噪声过大

面试表达：

`Rewrite 层的质量关键不在于语言是否漂亮，而在于能不能把自然语言问题收敛成一个可执行、可控的检索计划。`

### Step 6. 分层记忆编排

步骤目标：

- 组合短期记忆与长期记忆，形成最终可注入模型的记忆视图

当前代码位置：

- [LayeredMemoryOrchestrator.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/ai-chat-service/src/main/java/com/lx/governance/aichat/service/LayeredMemoryOrchestrator.java)
- [MemorySnapshot.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/doctor-assistant-agent/src/main/java/com/health/agent/dto/patient/MemorySnapshot.java)

当前能监控的内容：

- 短期记忆来源
- 长期记忆返回数量
- 记忆快照是否注入任务请求

质量判断：

- 短期记忆是否抓住本轮最相关上下文
- 长期记忆是否补足“近期发生过什么”
- 两层记忆是否互补，而不是重复堆砌

常见异常信号：

- 短期记忆与长期记忆重复率很高
- 注入的长期记忆与当前问题明显无关
- 记忆快照过大导致 prompt 膨胀

面试表达：

`分层记忆不是把所有记忆都塞给模型，而是把 session 相关性和跨时间相关性分开处理。`

### Step 7. 长期记忆检索质量

步骤目标：

- 从 `vector-memory-service` 中召回与当前 query 语义最相关的长期记忆

当前代码位置：

- [MemoryRetrievalService.java](/Users/lejinbo/LLM/code-snippets/governance-suite/vector-memory-service/src/main/java/com/lx/governance/vectormemory/service/MemoryRetrievalService.java)
- [MemoryRerankService.java](/Users/lejinbo/LLM/code-snippets/governance-suite/vector-memory-service/src/main/java/com/lx/governance/vectormemory/service/MemoryRerankService.java)
- [memory-retrieval-rerank.md](/Users/lejinbo/LLM/code-snippets/governance-suite/docs/analysis/memory-retrieval-rerank.md)

当前能监控的内容：

- Redis 候选数量
- Milvus 候选数量
- merge 后候选数
- rerank 是否开启
- rerank 策略：`partial / full`
- 相似度排序结果

质量判断：

- topK 结果是否语义相关
- Redis 最近记忆是否被无脑置顶
- rerank 后是否明显提升相关性
- 跨时间但高相关的记忆是否能被召回

常见异常信号：

- 结果高度偏向最近消息，而不是语义相关消息
- topK 很满，但答案依然没有引用有效历史
- rerank 前后排序几乎不变，说明检索链路未真正发挥作用

面试表达：

`记忆检索的关键质量不是召回了多少条，而是 topK 里有多少条真正对当前回答有帮助。`

### Step 8. 任务升级路由

步骤目标：

- 判断当前请求是 `direct chat` 还是升级为任务型 Agent

当前代码位置：

- [TaskTriggerRouter.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/ai-chat-service/src/main/java/com/lx/governance/aichat/service/TaskTriggerRouter.java)
- [PlatformTaskRoutingDecision.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/agent-platform-common/src/main/java/com/lx/governance/agentplatform/model/PlatformTaskRoutingDecision.java)

当前能监控的内容：

- `routeType`
- `taskType`
- `targetAgentId`
- `workflowCode`
- `reasons`

质量判断：

- 任务升级是否命中真正需要结构化处理的场景
- 是否出现误升级或漏升级
- 路由原因是否足够解释

常见异常信号：

- 普通闲聊被错误升级为 task
- 风险场景仍停留在 direct chat
- 路由 reasons 过于单薄，难以复盘

面试表达：

`路由层的价值在于把“会聊天”和“会办事”两种链路清晰分开。`

### Step 9. 分发与治理放行

步骤目标：

- 根据 group、user、tenant、version 等规则决定是否放行 Agent

当前代码位置：

- [AgentDispatchPolicy.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/ai-chat-service/src/main/java/com/lx/governance/aichat/service/AgentDispatchPolicy.java)
- [PlatformDispatchPolicyEngine.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/agent-platform-common/src/main/java/com/lx/governance/agentplatform/runtime/PlatformDispatchPolicyEngine.java)

当前能监控的内容：

- 是否放行
- 放行原因或拦截原因
- fallback 是否触发

质量判断：

- 治理规则是否按预期生效
- 是否把不可见对象错误暴露给用户
- 是否在治理阻断后正确降级

常见异常信号：

- 版本切换后用户命中异常
- tenant 维度没带上导致越权放行
- fallback 过多说明治理策略过紧或能力未准备好

面试表达：

`治理层解决的是“能不能跑”，而不是“跑得好不好”。`

### Step 10. 平台上下文构造与投影

步骤目标：

- 把对话入口上下文投影为可跨服务传递的 `PlatformContext`

当前代码位置：

- [DoctorAssistantTaskRequestFactory.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/ai-chat-service/src/main/java/com/lx/governance/aichat/service/DoctorAssistantTaskRequestFactory.java)
- [PlatformContext.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/agent-platform-common/src/main/java/com/lx/governance/agentplatform/model/PlatformContext.java)
- [PlatformContextField.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/agent-platform-common/src/main/java/com/lx/governance/agentplatform/model/PlatformContextField.java)

当前能监控的内容：

- `session_context / profile_snapshot / memory_snapshot / trigger_decision / patient_message`
- `domain`
- `visibility`
- channel 与 budget 投影

质量判断：

- 是否把该带的上下文都带进去了
- 是否有无关上下文泄漏
- 是否因 budget 裁剪丢失关键字段

常见异常信号：

- `trigger_decision` 被错误暴露给全部 subAgent
- 关键 profile 字段被裁掉
- platformContext 过大导致下游 prompt 失控

面试表达：

`PlatformContext 是多 Agent 平台的上下文总线，核心不是传得多，而是传得准、传得可治理。`

### Step 11. SubAgent 选择

步骤目标：

- 根据 taskType、context slots、risk signals、tool availability 选择最合适的执行主体

当前代码位置：

- [SubAgentCoordinator.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/doctor-assistant-agent/src/main/java/com/health/agent/engine/subagent/SubAgentCoordinator.java)
- [AgentSelectionDecision.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/agent-platform-common/src/main/java/com/lx/governance/agentplatform/model/AgentSelectionDecision.java)

当前能监控的内容：

- `mode`
- `selectedAgentIds`
- `reasons`
- `sub_agent_selection_decision / sub_agent_pending_plan / sub_agent_executed_plan`

质量判断：

- 是否选中了具备对应能力的 subAgent
- 是否因为风险信号过宽而误选高风险角色
- 并行计划是否真正被运行时消费

常见异常信号：

- `selectedAgentIds` 合理但实际执行主体不一致
- 风险任务却优先选了资料采集
- 上下文不足却跳过资料收集角色

面试表达：

`Selector 的价值不是只给一个名字，而是解释为什么此时应该由这个 Agent 来接棒。`

### Step 12. ReAct Loop 执行

步骤目标：

- 在统一 loop 骨架中完成多步 thought、action、observation、final answer 收敛

当前代码位置：

- [ReActAgentEngine.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/doctor-assistant-agent/src/main/java/com/health/agent/engine/ReActAgentEngine.java)
- [ReActLoopState.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/doctor-assistant-agent/src/main/java/com/health/agent/engine/ReActLoopState.java)

当前能监控的内容：

- step 数
- `THOUGHT / ACTION / OBSERVATION / FINAL_ANSWER`
- `MAX_STEPS / WAITING_APPROVAL / FAILED / COMPLETED`
- 流式事件链

质量判断：

- 是否在合理步数内完成
- 是否出现重复工具调用
- 是否能基于 observation 持续收敛

常见异常信号：

- 连续重复 action
- 协议纠偏频繁触发
- 过早结束导致证据不足
- 过晚结束导致 loop 发散

面试表达：

`运行时质量的核心不是 step 越多越聪明，而是每一步都能把不确定性往下压。`

### Step 13. Tool 调用质量

步骤目标：

- 安全、可治理地执行工具，并把结果标准化回灌

当前代码位置：

- [ActionExecutor.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/doctor-assistant-agent/src/main/java/com/health/agent/engine/ActionExecutor.java)
- [PlatformToolOutput.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/agent-platform-common/src/main/java/com/lx/governance/agentplatform/model/PlatformToolOutput.java)

当前能监控的内容：

- toolName
- riskLevel
- timeout / retry / qps / circuit breaker
- 审批要求
- output summary

质量判断：

- 工具有没有成功执行
- 这次调用是否真正产生了有效 evidence 或 state update
- 是否出现超时、限流、熔断、重复调用

常见异常信号：

- 工具执行成功但没有任何状态更新
- 工具输出对当前问题无帮助
- tool latency 明显飙升

面试表达：

`平台视角下，工具不是黑盒函数，而是可审计、可限流、可回放的高风险执行单元。`

### Step 14. 上下文回灌与证据链

步骤目标：

- 把 tool output、observation、evidence 写回显式工作内存

当前代码位置：

- [AgentContextMutationService.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/doctor-assistant-agent/src/main/java/com/health/agent/service/AgentContextMutationService.java)
- [AgentContext.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/doctor-assistant-agent/src/main/java/com/health/agent/model/AgentContext.java)

当前能监控的内容：

- slot 更新
- evidence 新增
- visibility
- `last_tool_result`

质量判断：

- 回灌后的结构化状态是否可被后续 Agent 复用
- evidence 是否可追溯
- 上下文是否明显膨胀

常见异常信号：

- 工具结果只留在 observation 文本，没进入结构化 slots
- evidence 太多但总结很弱
- 敏感信息暴露级别不正确

面试表达：

`没有上下文回灌，多 Agent 协作就只能靠自然语言接力，稳定性会很差。`

### Step 15. 审批暂停与恢复

步骤目标：

- 对高风险动作进行人工审核
- 审核通过后恢复原运行链路

当前代码位置：

- [AgentSessionWorkflowService.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/doctor-assistant-agent/src/main/java/com/health/agent/service/AgentSessionWorkflowService.java)
- [DoctorRunResumeService.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/doctor-assistant-agent/src/main/java/com/health/agent/service/DoctorRunResumeService.java)
- [DoctorAgentApprovalResumeExecutor.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/doctor-assistant-agent/src/main/java/com/health/agent/service/DoctorAgentApprovalResumeExecutor.java)

当前能监控的内容：

- `ApprovalTask`
- 审批状态
- resume 前校验
- resume 后上下文恢复

质量判断：

- 审批是否只拦高风险动作
- resume 是否恢复到了正确 step
- 恢复后是否还能延续原证据链

常见异常信号：

- 审批任务归属错乱
- resume 后上下文丢失
- 批准后直接失败，说明恢复链路不完整

面试表达：

`审批链路的关键不是停下来，而是能不能在人工决策后无缝回到原运行态。`

### Step 16. 最终答案收敛

步骤目标：

- 生成患者可见答复
- 生成结构化结果
- 生成工作台载荷

当前代码位置：

- [DoctorAssistantTaskResponse.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/doctor-assistant-agent/src/main/java/com/health/agent/dto/patient/DoctorAssistantTaskResponse.java)
- [PatientTaskAgentResultMapper.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/doctor-assistant-agent/src/main/java/com/health/agent/service/PatientTaskAgentResultMapper.java)

当前能监控的内容：

- `patientSafeReply`
- `structuredResult`
- `doctorWorkbenchPayload`
- `platformWorkbenchPayload`

质量判断：

- 患者可见输出是否安全、可理解
- 结构化输出是否完整
- 是否带有支持证据与不确定性说明

常见异常信号：

- `patientSafeReply` 缺失
- 患者可见回答与内部结构化结论矛盾
- 高风险输出没有进入人工闭环

面试表达：

`最终答案不是一个字符串，而是一组面向不同消费方的多视图结果。`

### Step 17. Fallback 与降级

步骤目标：

- 在 Agent 不可用、治理不放行、结果不可消费时回退 direct chat

当前代码位置：

- [AgentConversationRouteRuntime.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/ai-chat-service/src/main/java/com/lx/governance/aichat/service/AgentConversationRouteRuntime.java)
- [PlatformRouteRuntime.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/agent-platform-common/src/main/java/com/lx/governance/agentplatform/runtime/PlatformRouteRuntime.java)

当前能监控的内容：

- 是否 fallback
- fallback reason
- pending review short-circuit

质量判断：

- fallback 是否及时
- fallback 后用户体验是否仍连续
- fallback 是否掩盖了真实系统缺陷

常见异常信号：

- fallback 率高
- fallback 原因长期集中在某一个 tool 或某一个 agent
- fallback 后回答质量显著下降

面试表达：

`平台稳定性的关键不是从不失败，而是在失败时还能把用户带回可用链路。`

### Step 18. Trace、Replay、Audit、Metrics

步骤目标：

- 支撑定位、回放、审计与成本分析

当前代码位置：

- [PlatformMetricsService.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/agent-platform-common/src/main/java/com/lx/governance/agentplatform/runtime/PlatformMetricsService.java)
- [RunReplayService.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/agent-platform-common/src/main/java/com/lx/governance/agentplatform/runtime/RunReplayService.java)
- [RunReplayController.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/doctor-assistant-agent/src/main/java/com/health/agent/controller/RunReplayController.java)

当前能监控的内容：

- `platform.run.total`
- `platform.run.outcome`
- `platform.run.status`
- `platform.run.approval.required`
- `platform.run.fallback`
- `platform.run.latency`
- `platform.tool.latency`
- `platform.tokens.*`
- `platform.cost.estimated`

质量判断：

- 是否能从 `runId` 回放完整链路
- 是否能定位 tool、route、approval、final answer 的关键节点
- 是否能区分 `DIRECT_CHAT` 和 `AGENT_WORKFLOW` 成本

常见异常信号：

- 回放视图缺少关键事件
- audit 只记结果不记原因
- token 成本异常但没有分 scope

面试表达：

`可观测性的终点不是看 dashboard，而是能用一次 run 回答：发生了什么、为什么、代价多少、是否值得。`

---

## 5. 记忆检索专项质量说明

如果面试官重点问记忆检索，可以单独按下面讲：

1. `是否需要检索`
- 由 `LayeredMemoryOrchestrator` 决定
- 关注的是检索触发率是否合理

2. `检索 query 是否正确`
- 由 `RetrievalQueryRewriteService` 负责
- 看 `rewriteSource / rewriteReason / filterExpression`

3. `候选记忆是否覆盖语义相关样本`
- 由 `MemoryRetrievalService` 负责
- 看 Redis 候选、Milvus 候选、merge 后候选数

4. `排序是否真正提升质量`
- 由 `MemoryRerankService` 负责
- 看 rerank 前后排序变化、topK 相关性

5. `最终注入是否克制`
- 由 `LayeredMemoryOrchestrator` 与 prompt budget 共同控制
- 看是否过长、是否重复、是否污染当前回答

一句话总结：

`记忆检索质量不是单看召回率，而是看“是否在正确的问题上，取回了真正对当前回答有帮助的少量历史信息”。`

---

## 6. 面试时建议强调的 6 个平台亮点

1. 当前平台不是只有 Agent 执行，而是已经有 `route + selector + tool governance + approval + replay + audit` 一整套骨架。
2. 每个关键步骤都能说清输入、输出、决策原因和失败后的治理动作。
3. 记忆检索不是简单查向量库，而是 `rewrite + self-query + layered memory + rerank` 的组合链路。
4. 多 Agent 协作不是写死角色切换，而是 registry 驱动 selector。
5. 高风险动作不是直接执行，而是可暂停、可审批、可恢复。
6. 失败不是链路终止，而是可 fallback 到 direct chat，保证患者侧稳定性。

---

## 7. 当前仍需诚实说明的边界

基于当前代码，面试时建议主动说明以下边界：

- `PARALLEL` 目前是并行计划、串行消费，不是真正并发执行运行时
- prompt 版本已支持 active version 切换，但还没做按 `group / user / tenant` 的灰度切换
- 部分步骤的“质量”目前仍主要通过 replay、audit 和结构化输出做人工判断，而不是每一步都已有独立分值
- 记忆检索链路已有较完整质量骨架，但线上最终效果仍需依赖真实 query 样本做评估

这种表达不会减分，反而会体现你对系统边界和工程成熟度的判断是清楚的。

---

## 8. 最终面试总结话术

可以直接这样总结：

`我把这套 Agent 平台拆成了入口、上下文、记忆检索、路由、分发、selector、推理、工具、审批、收敛、回放和审计等多个步骤。每个步骤我都能说明目标、输入输出、当前可观测信号、质量判断方式和常见异常模式。这样系统就不只是“能跑通”，而是“可解释、可追踪、可回放、可治理”。`

