# 全链路多 Agent 平台改造 TODO

本文档用于把当前 `AI 对话系统 + doctor-assistant-agent` 的能力，逐步演进为一个可治理、可扩展、可观测的全链路多 Agent 平台。

适用范围：

- `ai-chat-service`
- `doctor-assistant-agent`
- `user-profile-service`
- `vector-memory-service`
- `knowledge-base-service`

当前基线不是“从零做平台”，而是基于已经存在的能力继续抽象和平台化：

- `ai-chat-service` 已负责对话入口、上下文、画像、记忆、路由与降级
- `doctor-assistant-agent` 已负责任务升级后的多 subAgent 执行、审批恢复、审计和流式输出
- 当前整体架构仍属于“患者对话入口 + 按需触发 Agent + 人工协同闭环”

目标架构则是：

- 统一入口
- 统一 Agent 注册中心
- 统一编排运行时
- 统一任务状态机
- 统一上下文与工具治理
- 统一观测、评测和发布治理

### 术语说明

- `平台运行时层`
  - 负责“任务怎么跑起来”
  - 包括 run 建模、step 推进、agent 选择、tool 调用、审批暂停、恢复执行、结果收敛
- `平台治理层`
  - 负责“任务能不能这样跑、该由谁看到、如何被管住”
  - 包括 Agent 启停、版本切换、group/user/tenant 可见性、审计、追踪、配额、安全和评测
- 两者关系：
  - 运行时层决定“下一步执行谁”
  - 治理层决定“这个对象是否有资格被执行”

---

## 0. 改造目标

- [x] 将当前“单入口 + 局部 Agent 子系统”演进为“全链路多 Agent 平台”
- [x] 保留 `ai-chat-service` 作为用户交互入口，但弱化其硬编码编排职责
- [x] 保留 `doctor-assistant-agent` 中可复用的运行时能力，逐步抽象为平台公共层
- [x] 让新增 Agent 不再依赖大量硬编码路由和手工注入
- [x] 让每一次用户请求都能被统一追踪为一个平台级 `run`
- [x] 支持统一的审批、恢复、降级、灰度、审计和评测

### 0.1 当前轮落实说明

本轮已明确平台化改造的统一目标，并确定以下落地原则：

- `ai-chat-service` 继续承担用户对话入口、体验编排和最终回复聚合职责，但后续逐步把任务升级、运行态管理和审批恢复等能力下沉到平台层
- `doctor-assistant-agent` 中已经成熟的 `ReActAgentEngine`、`SubAgentCoordinator`、审批恢复、流式事件和审计机制，优先抽象为平台公共运行时，而不是直接废弃重写
- 平台目标不是把所有场景都强行改成 doctor workflow，而是建立统一的 `run / agent / tool / context / approval` 协议，让 doctor、knowledge、risk 等场景都能复用
- 平台后续所有新增能力都围绕统一 `run` 建模，避免继续出现“对话链路一套状态、Agent 链路一套状态”的分裂模型
- 所有平台化改造默认保留降级、安全和人工复核边界，患者侧稳定性优先于抽象完整性
- 当前分支 `codex/ai-multi-agent-platform` 作为本次平台化改造主线分支

---

## 1. 现状基线梳理

### 1.1 对话入口链路

- [x] 盘点 `ai-chat-service` 当前承担的职责边界
- [x] 标注哪些逻辑属于“体验入口层”，哪些逻辑属于“平台编排层”
- [x] 梳理 `AiChatService` 中的主流程节点：
  - 会话初始化
  - 配额检查
  - 画像加载
  - 记忆加载
  - 任务升级判定
  - Agent 分发
  - direct chat 回退
  - 后处理与历史落库
- [x] 输出一份当前主链路时序图更新版

当前入口链路基线：

- 体验入口层：
  - HTTP 对话入口
  - `SessionContext` 建立与维护
  - 用户画像、分层记忆装载
  - 患者可见回复聚合
  - direct chat 兜底
- 平台编排候选层：
  - `TaskTriggerRouter` 的任务升级判定
  - `AgentDispatchPolicy` 的灰度、放行和熔断策略
  - `invokeAgentOrFallback(...)` 的执行分流
  - pending review 短路与回退决策
  - 后续可抽象为统一 `run` 生命周期的状态推进

当前主链路时序摘要：

1. `ai-chat-service` 接收对话请求并生成 `conversationId`
2. 做配额预检与会话并发保护
3. 读取或构建 `SessionContext`
4. 用 `TaskTriggerRouter` 判断 direct chat 或 task upgrade
5. 并行/异步装载画像和分层记忆
6. 若命中升级且策略放行，打包快照并调用 `doctor-assistant-agent`
7. 若 Agent 成功，回收 `patientSafeReply` 写回患者主链路
8. 若 Agent 未放行、失败或返回不可消费结果，则回退 direct chat
9. 统一执行上下文更新、历史落库与记忆后处理

### 1.2 Agent 子系统链路

- [x] 盘点 `doctor-assistant-agent` 当前已具备的平台化能力
- [x] 梳理 `ReActAgentEngine` 可复用的核心能力：
  - step loop
  - subAgent 切换
  - tool action
  - context update
  - approval pause/resume
  - audit
  - SSE 事件流
- [x] 梳理 `SubAgentCoordinator` 当前是“规则驱动选择器”还是“平台级调度器”
- [x] 识别当前只能服务 doctor 场景的耦合点

当前 Agent 子系统基线：

- `doctor-assistant-agent` 已具备以下平台化雏形：
  - 显式 `AgentContext`
  - subAgent 选择与切换
  - 工具调用标准化输出
  - 审批暂停与恢复
  - SSE 过程事件
  - 结构化结果与审计
- `ReActAgentEngine` 当前最值得复用的能力：
  - 统一 step loop
  - context preload / update
  - action 执行与 observation 写回
  - pending approval / resume
  - success / error / max steps 出口收敛
  - 流式事件模型
- `SubAgentCoordinator` 当前定位是“规则驱动选择器”，还不是平台级调度器：
  - 能基于 taskType、goal、context slots 选择角色
  - 但 Agent 注册、调度策略、能力声明仍然是硬编码
- 当前仅服务 doctor 场景的耦合点：
  - taskType 语义绑定患者随访/风险分层
  - prompt 构建紧贴减重与医生协作场景
  - tool registry 主要围绕医生助理能力集
  - workbench payload 偏医生工作台语义

### 1.3 公共依赖能力

- [x] 梳理当前上下文模型：
  - `SessionContext`
  - `AgentContext`
  - 分层记忆快照
  - 任务请求快照
- [x] 梳理当前工具体系和风险分级能力
- [x] 梳理当前可观测性能力：
  - `sessionId`
  - `conversationId`
  - `correlationId`
  - `agentSessionId`
  - metrics
  - 审计日志
- [x] 梳理当前降级与灰度能力

当前公共依赖能力基线：

- 上下文模型：
  - `SessionContext` 负责患者对话态、会话阶段、追问态、消息计数等入口上下文
  - `AgentContext` 负责 task 级结构化工作内存、状态槽位和证据链
  - `LayeredMemories` 负责短期/长期记忆快照
  - `DoctorAssistantTaskRequest` 负责把入口上下文快照注入 Agent 子系统
- 工具体系与风险分级：
  - doctor agent 内部已有 `ToolRegistry`
  - 已支持 `LOW / MEDIUM / HIGH` 风险等级
  - 已支持高风险动作进入待审批任务
- 可观测性：
  - 入口层已有 `conversationId`、`sessionId`、`correlationId`
  - Agent 层已有 `agentSessionId`
  - 已有 metrics、日志和审计，但尚未统一成平台级 run trace
- 降级与灰度：
  - `ai-chat-service` 已有多级降级、熔断、缓存兜底
  - `AgentDispatchPolicy` 已有灰度放行与熔断开关
  - `doctor-assistant-agent` 已有模板回退和安全收敛

---

## 2. 目标架构定义

### 2.1 明确平台分层

- [x] 定义 `Experience Layer`
  - `ai-chat-service`
  - 医生工作台
  - 外部 API 入口
- [x] 定义 `Platform Orchestration Layer`
  - routing engine
  - workflow engine
  - approval engine
  - run engine
- [x] 定义 `Agent Capability Layer`
  - doctor assistant agent
  - knowledge agent
  - risk triage agent
  - care planning agent
  - future agents
- [x] 定义 `Shared Intelligence Layer`
  - memory
  - profile
  - retrieval
  - tool registry
  - prompt registry
- [x] 定义 `Governance Layer`
  - audit
  - tracing
  - evals
  - rollout
  - quota
  - safety

目标分层定义：

- `Experience Layer`
  - 负责患者聊天入口、医生工作台入口、外部编排 API
  - 只关心交互协议、展示协议和最终回复聚合
- `Platform Orchestration Layer`
  - 负责 route、workflow、approval、run lifecycle
  - 是后续平台改造的核心抽象层
- `Agent Capability Layer`
  - 承载具体业务能力 Agent
  - doctor assistant 只是首个成熟场景，不是平台本身
- `Shared Intelligence Layer`
  - 提供 profile、memory、retrieval、tools、prompts 等共享能力
- `Governance Layer`
  - 提供安全、灰度、审计、追踪、评测、成本治理

### 2.2 明确平台边界

- [x] 明确哪些能力沉到平台公共层
- [x] 明确哪些能力仍保留在场景 Agent 内
- [x] 明确 `ai-chat-service` 在平台化后只负责哪些职责
- [x] 明确 `doctor-assistant-agent` 在平台化后是“一个能力 Agent”还是“平台 runtime 宿主”

平台边界定义：

- 下沉到平台公共层的能力：
  - `run` 状态管理
  - workflow 执行骨架
  - approval / resume
  - Agent Registry
  - Tool Registry
  - trace / audit / replay
  - rollout / policy
- 保留在场景 Agent 内的能力：
  - 场景专属 taskType 语义
  - 场景专属 prompt 语料和结果解释
  - 场景专属 workbench payload
  - 场景专属工具组合策略
- 平台化后 `ai-chat-service` 的职责：
  - 用户交互入口
  - `SessionContext` 管理
  - 患者可见结果聚合
  - 调用平台 routing / run API
  - direct chat 体验兜底
- 平台化后 `doctor-assistant-agent` 的定位：
  - 优先作为一个能力 Agent 场景保留
  - 其中通用 runtime 能力逐步抽入平台公共模块
  - 最终不再承担“平台宿主”的唯一职责

---

## 3. Agent 注册中心

### 3.1 设计 Agent Registry

- [x] 设计 `AgentDefinition` 模型
- [x] 定义字段：
  - `agentId`
  - `displayName`
  - `agentType`
  - `capabilities`
  - `supportedTaskTypes`
  - `inputSchema`
  - `outputSchema`
  - `allowedTools`
  - `riskLevel`
  - `owner`
  - `version`
  - `status`
- [x] 设计 Agent 注册方式：
  - 静态配置
  - Spring Bean 自动注册
  - 数据库存储
  - 配置中心动态加载
- [x] 明确首版采用哪种注册机制

当前落实结果：

- 已新增模块 `ai/agent-platform-common`
- 已落地 `AgentDefinition`、`AgentType`、`AgentStatus`
- 已定义 `AgentRegistry` 接口与 `InMemoryAgentRegistry` 首版实现
- 首版注册机制选型：
  - 先用 Spring Bean + 内存注册中心完成本地平台化接线
  - 后续再演进到配置中心或数据库驱动的注册中心

### 3.2 改造现有 subAgent

- [x] 将 `DataCollectionSubAgent` 注册为平台 Agent 定义
- [x] 将 `WeightAnalysisSubAgent` 注册为平台 Agent 定义
- [x] 将 `CarePlanningSubAgent` 注册为平台 Agent 定义
- [x] 将 `RiskTriageSubAgent` 注册为平台 Agent 定义
- [x] 去掉仅靠 `@Autowired` + 硬编码字段的注册方式

当前落实结果：

- 已新增 `DoctorAgentPlatformRegistrationConfig`
- `doctor-assistant-agent` 启动时会把 4 个现有 subAgent 注册为平台 Agent 定义
- `SubAgentCoordinator` 已从 4 个 `@Autowired` 字段改为 `List<AgentSubAgent>` 构造注入
- 相关测试已去掉 `ReflectionTestUtils.setField(...)` 的字段装配方式
- `SubAgentCoordinator` 已从“按固定角色名直接取 bean”升级为“按 capability 从 `AgentRegistry` 选择已启用 Agent，再按 `agentId` 绑定运行时实例”的 registry 驱动 selector

### 3.3 Agent 生命周期治理

- [x] 支持启用/停用 Agent
- [ ] 支持 Agent 灰度发布
- [x] 支持 Agent 版本切换
- [x] 支持按 group/user/tenant 控制可见 Agent

当前落实结果：

- `AgentRegistry` 已新增 `updateStatus(...)`、`enable(...)`、`disable(...)`
- `AgentRegistry` 已新增 `listVersions(...)`、`switchVersion(...)`
- `AgentRegistry` 已新增 `listVisible(...)`、`findEnabledByCapability(...)`
- `InMemoryAgentRegistry` 已支持在运行态切换 `AgentStatus`
- `InMemoryAgentRegistry` 已支持同一逻辑 Agent 的多版本注册，并切换当前激活版本
- `AgentDefinition` 已支持配置：
  - `visibleGroupIds`
  - `visibleUserIds`
  - `visibleTenantIds`
- `SubAgentCoordinator` 选择 Agent 时已只消费 `AgentStatus.ENABLED` 的注册定义
- `SubAgentCoordinator` 选择 Agent 时已把 `group / user / tenant` 上下文传入注册中心做可见性过滤
- 已补充 `InMemoryAgentRegistryTest`，覆盖：
  - disable 已注册 Agent
  - enable 已禁用 Agent
  - 更新未知 Agent 状态时报错
  - 同一逻辑 Agent 的版本切换
  - 切换未知版本时报错
  - group/user/tenant 可见性过滤
- 已补充 `SubAgentCoordinatorTest`，覆盖不可见 group 下拒绝选中 Agent 的场景

当前实现边界：

- 首版版本切换仍基于内存注册中心
- 同一逻辑 Agent 同一时刻只允许一个版本处于 `ENABLED`
- `SubAgentCoordinator` 已天然消费切换后的激活版本，但尚未加入流量比例发布
- 当前 `tenantId` 仍是可选上下文字段，只有上游提供时才参与过滤

---

## 4. 统一编排运行时

### 4.1 抽象平台 Run Engine

- [x] 定义统一 `Run` 概念
- [x] 定义 `RunType`：
  - chat
  - task
  - workflow
  - approval_resume
- [x] 定义 `RunStatus`：
  - pending
  - running
  - waiting_approval
  - completed
  - failed
  - degraded
  - cancelled
- [x] 定义 `RunStep`
- [x] 定义 `RunEvent`

当前落实结果：

- 已在 `agent-platform-common` 中新增：
  - `Run`
  - `RunType`
  - `RunStatus`
  - `RunStep`
  - `RunEvent`
  - `RunEventType`
- `Run` 当前已覆盖核心运行时字段：
  - `runId`
  - `parentRunId`
  - `conversationId`
  - `sessionId`
  - `workflowId`
  - `agentId`
  - `input/output`
  - `steps/events`
  - `createdAt/startedAt/endedAt`
- 已新增 `RunModelTest` 验证模型构造和基本关系

当前实现边界：

- 当前只完成公共运行时模型定义，尚未落具体 `RunService / RunRepository / RunExecutor`
- 当前 `RunStep.nodeType` 仍是字符串，后续在 workflow node 抽象时再统一收口

### 4.2 抽象工作流执行模型

- [x] 设计 workflow node 类型：
  - route
  - agent_call
  - tool_call
  - branch
  - parallel
  - wait_approval
  - finalize
- [x] 定义 workflow 输入输出协议
- [x] 定义超时机制
- [x] 定义重试机制
- [x] 定义 fallback 机制
- [x] 定义人工介入机制

当前落实结果：

- 已在 `agent-platform-common` 中新增：
  - `WorkflowDefinition`
  - `WorkflowNode`
  - `WorkflowEdge`
  - `WorkflowNodeType`
  - `WorkflowTransitionType`
  - `WorkflowTimeoutPolicy`
  - `WorkflowRetryPolicy`
  - `WorkflowFallbackPolicy`
  - `WorkflowApprovalPolicy`
  - `WorkflowManualInterventionPolicy`
  - `WorkflowManualInterventionType`
- `WorkflowDefinition` 已覆盖平台级 workflow 基础协议：
  - `workflowId`
  - `workflowName`
  - `version`
  - `startNodeId`
  - `inputSchema/outputSchema`
  - workflow 级 timeout/retry/fallback/manual intervention 策略
  - `nodes/edges`
- `WorkflowNode` 已支持节点级策略挂载：
  - `agentId`
  - `toolId`
  - `config`
  - `inputMapping/outputMapping`
  - `timeoutPolicy`
  - `retryPolicy`
  - `fallbackPolicy`
  - `approvalPolicy`
  - `manualInterventionPolicy`
- 已新增 `WorkflowModelTest` 验证 workflow 定义、审批、人工介入和降级策略的组合关系

当前实现边界：

- 当前只完成工作流定义模型和策略协议，尚未落具体 `WorkflowExecutor / WorkflowCompiler / WorkflowRepository`
- `parallel / branch / route` 目前先统一用 `WorkflowNode + WorkflowEdge` 表达，后续再根据执行器需要细化分支求值器和并发汇聚语义
- 审批与人工介入目前完成的是“平台协议建模”，后续 4.3 再把 `AgentSessionWorkflowService` 的真实 pause/resume 能力接入

### 4.3 提炼现有运行时

- [x] 从 `ReActAgentEngine` 提炼通用 loop 能力
- [x] 从 `AgentSessionWorkflowService` 提炼 approval/resume 能力
- [x] 从 `AiChatService` 提炼 route/fallback 能力
- [x] 避免平台运行时直接绑定医生减重场景 prompt 和 tool

当前落实结果：

- 已在 `agent-platform-common` 中新增统一 loop 协议：
  - `LoopContext<T>`
  - `LoopOutcome<S, R>`
  - `LoopTerminationReason`
  - `PlatformLoopExecutor<S, R>`
- 已在 `agent-platform-common` 中新增可复用循环模板执行器：
  - `AbstractPlatformLoopExecutor<S, R>`
  - `LoopStepDecision<S, R>`
  - 已由 `ReActAgentEngine` 通过本地适配器接入，doctor 场景开始复用平台模板循环骨架
- 已在 `agent-platform-common` 中新增平台公共路由运行时：
  - `PlatformRouteRuntime`
  - `PlatformRouteDecision`
  - `PlatformRouteTarget`
  - `PlatformPendingReviewSnapshot`
  - `PlatformAgentResponse`
- `ai-chat-service` 中的 `AgentConversationRouteRuntime` 已降级为本地 DTO 适配层：
  - 灰度放行仍由 `AgentDispatchPolicy` 决定
  - pending review short circuit / agent response consumable / fallback terminal error 规则已下沉到平台公共运行时
- 已在 `agent-platform-common` 中补充统一审批挂起模型：
  - `ApprovalStatus`
  - `ApprovalTask`
  - `SuspendedExecution`
- 已在 `agent-platform-common` 中新增平台审批抽象：
  - `ApprovalRepository<T>`
  - `ApprovalService<T, R>`
  - `ApprovalResumeExecutor<R>`
- doctor 场景已接入平台审批抽象：
  - `PendingTaskService` 作为 `ApprovalRepository<PendingTask>` 适配 `agent_pending_task`
  - `DoctorAgentApprovalResumeExecutor` 承担审批通过后的恢复状态装载
  - `AgentSessionWorkflowService` 作为 doctor 场景的 `ApprovalService<PendingTask, AgentApprovalResumeState>`
- `ReActAgentEngine` 已去掉“未注入 workflow service 时的兼容 fallback 分支”：
  - resume / resumeStream 必须统一经过平台化后的 `AgentSessionWorkflowService`
  - 恢复链路不再允许绕过平台审批编排能力
- `AgentSessionWorkflowService` 已不再只负责 session 持久化收尾，开始承接 approval/runtime 编排职责：
  - 新增 `describeSuspendedExecution(...)`
  - 新增 `loadApprovedResumeState(...)`
- `ReActAgentEngine.resume(...) / resumeStream(...)` 已下沉前置恢复逻辑：
  - 会话状态校验
  - 审批任务归属校验
  - 审批状态校验
  - history/messages/context/action 恢复
  - 引擎本身只保留“执行已批准 action + 继续 loop”
- `ReActAgentEngine` 内部私有 `LoopExecution` 已被平台公共 `LoopContext<ReActLoopState>` 取代
- `runLoop` 已统一产出 `LoopOutcome<ReActLoopState, AgentResult>`，显式收口：
  - success -> `FINISHED / COMPLETED`
  - pending approval -> `WAITING_APPROVAL / WAITING_APPROVAL`
  - runtime error -> `FAILED / FAILED`
  - max steps -> `MAX_STEPS / DEGRADED`
- `ai-chat-service` 已新增 `AgentConversationRouteRuntime`，把入口层的 route/fallback 编排判断从 `AiChatService` 中拆出：
  - pending review short circuit 判定
  - agent 灰度/放行后的 invocation 判定
  - agent 返回结果的 fallback 判定
  - auto fallback 开关关闭时的 terminal error 判定
- `doctor-assistant-agent` 已新增场景适配接口：
  - `AgentPromptProvider`
  - `AgentToolCatalog`
- `ReActAgentEngine` 不再直接依赖医生场景具体实现类：
  - 不再直接绑定 `ReActPromptBuilder` 具体类型
  - 不再直接绑定 `ToolRegistry` 具体类型
  - 改为只消费 prompt provider 和 tool catalog 抽象
- 已在 `agent-platform-common` 中新增平台统一 `ToolDescriptor`
- `ToolRegistry` 当前承担双角色：
  - 继续作为 doctor 场景工具执行注册中心
  - 同时通过 `ToolDescriptor` 对外暴露平台友好的工具描述目录
- `ReActPromptBuilder` 当前承担双角色：
  - 继续保留 doctor 场景 prompt 组织能力
  - 同时通过 provider 接口向运行时暴露通用 prompt 构建入口
- doctor 场景 prompt 语料和 tool 元数据已迁移到独立 scene package：
  - `scene/doctor/prompt/DoctorScenePromptTemplate`
  - `scene/doctor/tool/DoctorToolDefinitionRegistry`
  - 平台运行时只消费抽象接口，doctor 场景内容由 scene registry/template 提供

当前实现边界：

- 当前提炼完成的是 doctor 场景下最核心的 approval/resume 和 loop 协议骨架，底层仍复用 `agent_session` 和 `agent_pending_task`

---

## 5. 统一任务状态机

### 5.1 数据模型

- [x] 设计平台级数据表或持久化模型：
  - `agent_run`
  - `agent_run_step`
  - `agent_run_event`
  - `agent_pending_approval`
  - `agent_artifact`
- [x] 统一主键与关联键：
  - `run_id`
  - `parent_run_id`
  - `conversation_id`
  - `session_id`
  - `workflow_id`
  - `agent_id`

当前落实结果：

- 现有平台公共模型已覆盖核心运行态主干：
  - `Run` 对应 `agent_run`
  - `RunStep` 对应 `agent_run_step`
  - `RunEvent` 对应 `agent_run_event`
  - `ApprovalTask` 对应 `agent_pending_approval`
- 新增 `ArtifactRecord`，补齐 `agent_artifact` 的统一持久化对象
- 新增 `RunPersistenceSnapshot`，作为平台运行态聚合快照：
  - 统一承载 `run + approvals + artifacts`
  - 同时显式记录遗留表来源，便于兼容期投影和后续双写迁移
- 当前统一关联键先固定为：
  - `run_id = 平台统一执行主键`
  - `parent_run_id = 上级 run`
  - `conversation_id = 入口对话链路主键`
  - `session_id = 兼容入口或遗留会话主键`
  - `workflow_id = 编排模板主键`
  - `agent_id = 当前执行主体`

### 5.2 状态流转

- [x] 定义标准状态流转图
- [x] 定义 direct chat 与 agent run 的关联方式
- [x] 定义审批暂停后的恢复入口
- [x] 定义失败重试后的状态回写规则
- [x] 定义降级返回时的状态落库策略

当前落实结果：

- 新增 `RunStateMachine`，把平台 run 的标准状态转移固化为统一规则：
  - `PENDING -> RUNNING`
  - `RUNNING -> WAITING_APPROVAL / COMPLETED / FAILED / DEGRADED / CANCELLED`
  - `WAITING_APPROVAL -> RUNNING`
  - `FAILED / DEGRADED -> RUNNING`（retry）
- 新增 `RunTransition`、`RunTransitionTrigger`，显式表达：
  - 审批恢复
  - 失败重试
  - 降级回写
  - direct chat fallback
- 新增 `ConversationRunLink`、`PlatformEntryMode`、`PlatformOutcomeMode`
  - 用于定义入口 `conversationId` 与平台 `runId` 的关联
  - 区分本次请求是纯 direct chat、agent run，还是 agent 失败后回退 direct chat
- `AgentSessionWorkflowService` 已开始复用统一状态机描述关键转移：
  - `WAITING_APPROVAL -> RUNNING`（approved / rejected resume）
  - `FAILED -> RUNNING`（retry）
  - `RUNNING -> DEGRADED`（fallback to direct chat）

### 5.3 兼容现有 doctor session

- [x] 制定 `agent_session` 到平台 `run` 模型的映射方案
- [x] 评估是否需要保留原表兼容期
- [x] 明确迁移策略为“新 run 主路径 + legacy 只读兼容”，不做双写
- [x] 设计 run 主路径切换步骤与 legacy 退场方案

当前落实结果：

- 新增 `AgentSessionPlatformSnapshotMapper`
- 兼容策略不是让平台运行时继续直接理解 `agent_session` 语义，而是：
  - 先把 `agent_session` 投影为平台 `Run`
  - 把 `agent_pending_task` 投影为平台 `ApprovalTask`
  - 再聚合为 `RunPersistenceSnapshot`
- 当前 doctor 兼容投影规则：
  - `agent_session.id -> run_id`
  - `agent_session.id -> session_id`
  - `agent_session.status -> run.status`
  - `agent_pending_task(PENDING) -> WAITING_APPROVAL`
  - `history_json / messages_json / context_json -> run.input`
  - `final_answer / error_message -> run.output`
- 迁移策略调整为：
  - 新请求统一切到平台 `run` 主路径
  - `agent_session` / `agent_pending_task` 仅保留为历史读取和兼容投影来源
  - 当前学习项目阶段不做双写，避免引入一致性和补偿复杂度
- 新增平台仓储骨架：
  - `RunRepository`
  - `RunEventRepository`
  - `ArtifactRepository`
  - `ConversationRunLinkRepository`
  - `RunPersistenceRepository`
- 提供 `InMemoryRunPersistenceRepository` 作为学习项目阶段的新主路径承接实现
- 新增 `DoctorPlatformRunPersistenceService` 和默认配置 `DoctorPlatformRunPersistenceConfig`
  - 患者任务入口已开始把 doctor 运行态投影并写入平台 `run` 主路径
  - 当前平台主路径先使用内存仓储，便于验证状态机、run snapshot 和 conversation-run link
- 当前切换步骤明确为：
  - 第 1 步：患者任务入口先产出平台 `run snapshot`
  - 第 2 步：审批/恢复链路逐步改成优先读平台 `run`
  - 第 3 步：legacy 表退化为历史查询源
  - 第 4 步：移除 `agent_session` / `agent_pending_task` 写路径
- 当前第 2 步已开始落地：
  - 新增 `DoctorPlatformResumeStateService`
  - `DoctorAgentApprovalResumeExecutor` 已改成“平台快照优先，legacy 兜底”
  - resume 所需的 `history / messages / context / currentStep` 已可从平台 `RunPersistenceSnapshot` 恢复

---

## 6. 统一上下文总线

### 6.1 上下文模型统一

- [x] 设计统一 `PlatformContext`
- [x] 统一纳入以下上下文域：
  - conversation context
  - user profile snapshot
  - layered memories
  - task trigger decision
  - agent working memory
  - evidence
  - tool outputs
- [x] 定义上下文可见性：
  - user-visible
  - doctor-visible
  - agent-internal
  - audit-only

### 6.2 上下文传播

- [x] 设计入口到 Agent 的上下文注入协议
- [x] 设计 Agent 间中间产物传递协议
- [x] 设计跨服务上下文序列化格式
- [x] 设计上下文裁剪和 token budget 策略

### 6.3 上下文治理

- [x] 限制无关上下文泄漏到不该看到的 Agent
- [x] 为敏感字段设计脱敏策略
- [x] 为证据链设计保留策略

当前落实结果：

- 已在 `agent-platform-common` 中新增统一上下文模型：
  - `PlatformContext`
  - `PlatformContextField`
  - `PlatformContextEvidence`
  - `PlatformToolOutput`
  - `PlatformContextDomain`
  - `PlatformContextVisibility`
- 已统一定义可见性级别：
  - `USER_VISIBLE`
  - `DOCTOR_VISIBLE`
  - `AGENT_INTERNAL`
  - `AUDIT_ONLY`
- `ai-chat-service` 的 `DoctorAssistantTaskRequestFactory` 已开始构建统一 `PlatformContext`
  - 当前已注入 `session_context / profile_snapshot / memory_snapshot / trigger_decision / patient_message`
- `ai-chat-service -> doctor-assistant-agent` 请求 DTO 已携带 `platformContext`
- `doctor-assistant-agent` 的 `DoctorAssistantTaskRequest -> AgentExecutionCommand -> ReActAgentEngine`
  已优先打通统一上下文注入链路
- 已新增上下文传输/裁剪协议：
  - `PlatformContextChannel`
  - `PlatformContextBudget`
  - `PlatformContextProjector`
  - `DefaultPlatformContextProjector`
- 已新增 Agent 间 handoff 协议：
  - `PlatformAgentHandoff`
  - `PlatformAgentHandoffItem`
  - `PlatformHandoffItemType`
- `ReActAgentEngine` 已在子 Agent 切换时生成统一 handoff 包：
  - 写入 `sub_agent_handoff` 上下文槽位
  - 附加到 `SUBAGENT_STARTED` 事件数据
- 已在 `doctor-assistant-agent` 内把上下文治理前移到运行时：
  - `ContextEntry / EvidenceRecord` 已保留 `visibility`
  - `trigger_decision` 已按 `AUDIT_ONLY` 写入上下文，而不是默认暴露给所有 subAgent
  - `ReActAgentEngine` 已在首轮 prompt、每步 subAgent prompt、Agent handoff 三处统一投影可见上下文
  - 各 `AgentSubAgent` 已声明自身可见 slot 白名单，避免把无关工作内存整包泄漏给不相关角色
- 已新增统一脱敏器 `ContextPrivacySanitizer`：
  - 规则来源于 `doctor.agent.safety.privacy`
  - 当前已覆盖 `name / phone / idCard / address` 四类敏感字段
  - 在首轮 user prompt、subAgent 上下文投影、handoff payload/evidence 摘要暴露前统一执行脱敏
  - 当前仍以“暴露面脱敏”为主，底层持久化上下文与审计链路暂保留原始值，便于恢复与回放
- 已新增证据链保留策略 `ContextEvidenceRetentionService`：
  - 规则来源于 `doctor.agent.safety.evidence-retention`
  - 当前对持久化前的 `AgentContext.evidenceRecords` 执行统一裁剪
  - 支持 `max-records / max-payload-records / max-summary-chars / keep-audit-only-payload`
  - `AgentSessionService` 在 `update/pause/complete` 落 `contextJson` 前统一应用，减少 resume / replay 上下文膨胀
- 当前跨服务序列化格式已明确为：
  - `PlatformContext.schemaVersion = platform-context/v1`
  - `PlatformContext.channel`
  - `fields / evidences / toolOutputs`
- `ai-chat-service` 已在请求构造阶段对 `PlatformContext` 做统一投影和 budget 裁剪
  - 当前默认裁剪参数挂在 `ai-chat.agent.task.platform-context.*`
- 当前实现边界：
  - 统一上下文模型、入口注入协议、Agent 间 handoff 协议和可见性治理已落地
  - 当前仍保留“暴露面脱敏 + 底层原始值保留”的恢复/回放边界，后续如进入生产化阶段再补持久化脱敏或分级加密

---

## 7. 统一工具中心

### 7.1 Tool Registry

- [x] 设计平台级 `ToolDefinition`
- [x] 定义字段：
  - `toolName`
  - `description`
  - `schema`
  - `riskLevel`
  - `owner`
  - `timeout`
  - `retryPolicy`
  - `idempotencyPolicy`
- [x] 将 doctor agent 内工具注册表升级为平台共享工具注册表

### 7.2 Tool Policy

- [x] 设计工具权限控制
- [x] 设计工具风险分级策略
- [x] 设计工具审计策略
- [x] 设计工具级熔断/限流
- [x] 设计工具调用幂等

### 7.3 Tool Output 标准化

- [x] 统一 `ToolExecutionOutput` 为平台协议
- [x] 统一 `stateUpdates`
- [x] 统一 `evidence`
- [x] 统一 `summary`
- [x] 统一异常输出结构

当前落实结果：

- `agent-platform-common` 已新增平台工具模型：
  - `ToolDefinition`
  - `ToolRetryPolicy`
  - `ToolIdempotencyPolicy`
- `agent-platform-common` 已新增平台共享注册中心抽象：
  - `PlatformToolRegistry`
  - `InMemoryPlatformToolRegistry`
- `doctor-assistant-agent` 的 `DoctorToolDefinitionRegistry` 已从场景私有 definition map 升级为基于平台 registry 的工具元数据注册中心
- 当前 doctor 场景每个工具已补齐平台治理字段：
  - `toolName / description / schema / riskLevel / owner / timeoutMs / retryPolicy / idempotencyPolicy`
- 当前 `ToolRegistry` 仍保留 doctor 场景执行器装配职责，但工具描述元数据已优先来自平台级 `ToolDefinition`
- `ToolDefinition` 已继续补齐工具治理字段：
  - `auditEnabled`
  - `visibleGroupIds / visibleUserIds / visibleTenantIds`
  - `qpsLimit`
  - `circuitBreakerFailureThreshold / circuitOpenMillis`
- `PlatformToolRegistry` 已支持按访问上下文做可见性过滤，当前工具权限模型先按 `group / user / tenant` 生效
- `ActionExecutor` 已升级为工具策略执行入口：
  - 先做 access check，再做 schema normalize
  - 低中高风险执行路径统一以平台 `ToolDefinition.riskLevel` 为准
  - 审计写入支持按 `auditEnabled` 开关控制
  - 超时优先使用 `ToolDefinition.timeoutMs`
  - 重试优先使用 `ToolDefinition.retryPolicy`
  - 限流先使用工具级固定窗口 `qpsLimit`
  - 熔断先使用工具级连续失败阈值 + open window
  - 幂等工具会按 `toolName + normalizedInput` 做结果回放
- `PlatformToolOutput` 已成为运行时主协议：
  - `ActionExecutor` 成功输出统一标准化为 `PlatformToolOutput`
  - doctor 场景原有 `ToolExecutionOutput` 通过适配器兼容接入，不要求一次性重写全部 tool executor
- `PlatformToolOutput` 当前已统一的字段语义：
  - `summary`
  - `stateUpdates`
  - `evidences`
  - `visibility`
  - `toolName`
- `AgentContextMutationService` 已支持同时消费：
  - 平台 `PlatformToolOutput`
  - 历史兼容 `ToolExecutionOutput`
- 异常输出已统一为平台结构：
  - `PlatformToolError.code`
  - `PlatformToolError.message`
  - `PlatformToolError.retriable`
  - `PlatformToolError.details`
- 当前实现边界：
  - tool executor 内部仍可返回 doctor 私有 `ToolExecutionOutput`
  - 但运行时出口、上下文回灌、错误结构已经统一为平台协议

---

## 8. 路由与调度中心

### 8.1 平台级路由

- [x] 将 `TaskTriggerRouter` 升级为平台路由器的一部分
- [x] 区分以下路由层级：
  - direct chat vs task
  - task type routing
  - agent selection
  - workflow selection
- [x] 明确规则路由与模型路由的边界

### 8.2 Agent Selector

- [x] 将 `SubAgentCoordinator` 从 doctor 专属规则改造为可扩展 selector
- [x] 支持基于以下维度选择 Agent：
  - taskType
  - context slots
  - risk signals
  - rollout config
  - tool availability
- [x] 支持串行协作与并行协作

### 8.3 灰度与降级

- [x] 将 `AgentDispatchPolicy` 升级为平台级策略中心
- [x] 支持按以下维度灰度：
  - taskType
  - groupId
  - tenant
  - userId
  - agent version
- [x] 支持平台级统一 fallback

当前落实结果：

- `agent-platform-common` 已新增统一入口路由模型：
  - `PlatformRoutingContext`
  - `PlatformTaskRoutingDecision`
  - `PlatformConversationRouteType`
  - `PlatformRoutingTriggerMode`
- `TaskTriggerRouter` 已升级为“场景适配器 + 平台决策输出”双接口：
  - 对内保留 `TaskTriggerDecision`
  - 对平台输出 `PlatformTaskRoutingDecision`
  - 已显式区分 4 层路由：
    - `direct_chat_vs_task`
    - `task_type_routing`
    - `agent_selection`
    - `workflow_selection`
- 当前规则/模型路由边界已明确：
  - 当前实际执行仍是 `RULE_BASED`
  - 平台协议已保留 `MODEL_BASED`
  - 后续模型路由切换不再需要改入口 DTO
- `agent-platform-common` 已新增统一 selector 协议：
  - `AgentSelector`
  - `AgentSelectionRequest`
  - `AgentSelectionDecision`
  - `AgentSelectionMode`
- `SubAgentCoordinator` 已从“只返回单个 subAgent”升级为“先产出平台选择决策，再兼容单角色执行”：
  - 已纳入 `taskType`
  - `context slots`
  - `risk signals`
  - `rollout config`
  - `tool availability`
- 当前 selector 已支持两种协作模式：
  - `SERIAL`
  - `PARALLEL`
- 当前 `PARALLEL` 已继续向运行时落地：
  - selector 产出的 `selectedAgentIds` 会写入 `sub_agent_selection_decision / sub_agent_pending_plan / sub_agent_executed_plan`
  - `ReActAgentEngine` 会按 plan 顺序继续消费后续 Agent，避免 plan 只停留在决策层
  - 例如风险复核场景当前可先走 `RiskTriageAgent`，下一步再自动切到 `DataCollectionAgent`
- 当前保留边界：
  - `PARALLEL` 目前是“并行计划 + 分步串行消费”，还不是真正的并发分支执行与汇聚运行时
- `agent-platform-common` 已新增统一治理策略引擎：
  - `PlatformDispatchPolicyEngine`
  - `PlatformDispatchContext`
  - `PlatformDispatchDecision`
- `ai-chat-service` 的 `AgentDispatchPolicy` 已退化为平台策略适配器，不再自己维护灰度判断核心逻辑
- 当前灰度策略已支持按以下维度统一放行：
  - `taskType`
  - `groupId`
  - `userId`
  - `tenantId`
  - `agentVersion`
- 当前平台级统一 fallback 仍由 `PlatformRouteRuntime` 收口：
  - pending review short-circuit
  - dispatch blocked -> direct chat fallback
  - agent skipped/error/empty reply -> unified fallback
- 本轮顺手处理的 8.x 边界问题：
  - 修复了 selector 风险信号过宽导致“普通下一步建议”误分流到 `RiskTriageAgent` 的问题

---

## 9. 审批与人工协同

### 9.1 HITL 平台化

- [x] 抽象统一 `ApprovalRequest`
- [x] 抽象统一 `ApprovalDecision`
- [x] 定义适用于不同 Agent 的审批原因枚举
- [x] 定义审批 SLA、超时和提醒机制

当前落实结果：

- 平台公共模型已补齐：
  - `ApprovalRequest`
  - `ApprovalDecision`
  - `ApprovalReasonCode`
  - `ApprovalSlaPolicy`
  - `ApprovalReminderPolicy`
  - `ApprovalDecisionType`
- `AgentSessionWorkflowService` 已可把 doctor `PendingTask` 映射为平台 `ApprovalRequest`
- 当前 doctor 场景已落地的审批原因枚举包括：
  - `HIGH_RISK_TOOL_CALL`
  - `PRESCRIPTION_CHANGE_REVIEW`
  - `DIAGNOSIS_SENSITIVE_REVIEW`
  - `PATIENT_TASK_HUMAN_REVIEW`
- 当前默认审批策略：
  - `dueInMinutes = 30`
  - `expireInMinutes = 60`
  - `timeoutDecisionType = EXPIRED`
  - 首次提醒 10 分钟后触发，之后每 10 分钟提醒一次，最多 3 次

### 9.2 工作台协议统一

- [x] 统一 `doctorWorkbenchPayload` 风格的工作台载荷
- [x] 支持不同 Agent 返回不同工作台视图字段
- [x] 支持审批、驳回、补充信息、重新执行

当前落实结果：

- 平台公共模型已补齐：
  - `WorkbenchPayload`
  - `WorkbenchViewType`
  - `WorkbenchAction`
  - `WorkbenchActionType`
  - `WorkbenchPendingTask`
- `doctor-assistant-agent` 当前响应已同时返回：
  - 兼容旧链路的 `doctorWorkbenchPayload`
  - 面向统一平台协议的 `platformWorkbenchPayload`
- `platformWorkbenchPayload` 当前已支持的统一动作包括：
  - `APPROVE`
  - `REJECT`
  - `REQUEST_INFO`
  - `RETRY`
- 当前已通过 `viewType + viewData + extensions` 承载不同 Agent 的视图差异：
  - `viewType` 区分 `SUMMARY / REVIEW / APPROVAL`
  - `viewData` 承载推理证据、不确定项、supporting evidence
  - `extensions` 承载 doctor 场景特有的 upstream / agent evidence sources

### 9.3 恢复执行标准化

- [x] 抽象统一 `resume run` API
- [x] 统一恢复前校验逻辑
- [x] 统一恢复后上下文回灌逻辑

当前落实结果：

- 新增平台公共模型：
  - `ApprovalDecision`
  - `ApprovalDecisionType`
  - `ResumeRunRequest`
- 新增 `DoctorRunResumeService`
  - 统一承接 approve / reject 后的审批决策对象输出
  - 统一承接 resume 前的 session / approvalTask 归属与状态校验
- 新增 `AgentContextMutationService`
  - 统一承接工具执行后的 observation 格式化
  - 统一承接 action result -> context slots / evidence / last_tool_result 回灌
- `ReActAgentEngine` 的普通 loop 与 resume 流程已共用同一套上下文回灌逻辑
- `AgentController.resume(...)` 已改为先走 `DoctorRunResumeService.prepareResume(...)`
- `AgentController` 的 approve / reject 入口已切到统一 service，而不是直接调用 `PendingTaskService`

---

## 10. 观测与审计平台

### 10.1 Trace 统一

- [x] 定义统一 trace 维度：
  - `traceId`
  - `conversationId`
  - `correlationId`
  - `runId`
  - `stepId`
  - `agentId`
  - `toolName`
- [x] 保证跨服务透传这些 ID

当前落实结果：

- 平台公共模型已新增 `PlatformTraceContext`
- 当前统一 trace 维度已收敛到单一对象：
  - `traceId`
  - `conversationId`
  - `correlationId`
  - `runId`
  - `stepId`
  - `agentId`
  - `toolName`
- `ai-chat-service -> doctor-assistant-agent` 的患者任务请求已开始透传 `traceContext`
- `DoctorAssistantTaskRequestFactory` 已在入口侧注入统一 `traceId`
- `doctor-assistant-agent` 已把 `traceContext` 继续传入 `AgentExecutionCommand`
- `DoctorAssistantTaskResponse` 已返回补全后的 `traceContext`
  - 当前 `runId` 与 `agentId` 先映射为当前执行会话 ID
  - `toolName` 在待审批场景下可定位到当前审批待办对应工具

### 10.2 Replay 能力

- [x] 支持按 `runId` 回放整条链路
- [x] 支持查看每个 step 的输入输出
- [x] 支持查看每次路由原因
- [x] 支持查看每次工具调用及结果

当前落实结果：

- 平台公共层已新增：
  - `RunReplayView`
  - `ReplayRouteDecision`
  - `ReplayToolCall`
  - `RunReplayService`
- `RunReplayService` 当前会按 `runId` 聚合以下对象：
  - `Run`
  - `RunStep`
  - `RunEvent`
  - `ApprovalTask`
  - `ArtifactRecord`
  - `ConversationRunLink`
- doctor 场景已新增只读查询入口：
  - `DoctorRunReplayService`
  - `GET /api/runs/{runId}/replay`
- 当前 replay 视图已支持：
  - 查看整条 run 的主状态、legacy 映射和链路入口
  - 查看 step 级 input / output 快照
  - 查看 route 级 trigger source / fallback reason / outcome mode
  - 查看审批型工具调用的 toolName / reason / status / result summary
- `AgentSessionPlatformSnapshotMapper` 已补充关键事件 payload：
  - `RUN_CREATED` 包含 customerId / doctorId / goal
  - `RUN_STARTED` 包含 currentStep
  - `WAITING_APPROVAL` 包含 pendingTasks 摘要
  - `RUN_COMPLETED` / `RUN_FAILED` 包含 finalAnswer / errorMessage

### 10.3 指标与成本

- [x] 增加平台级指标：
  - run 总量
  - success/failure/degraded 比例
  - approval 率
  - fallback 率
  - 每 Agent latency
  - 每 Tool latency
  - token/cost 分摊
- [x] 区分 direct chat 与 agent workflow 的成本口径

当前落实结果：

- 平台公共层已新增：
  - `PlatformMetricsService`
  - `PlatformCostScope`
- `PlatformMetricsService` 当前已支持统一记录：
  - `platform.run.total`
  - `platform.run.outcome`
  - `platform.run.status`
  - `platform.run.approval.required`
  - `platform.run.fallback`
  - `platform.run.latency`
  - `platform.tool.latency`
  - `platform.tokens.prompt / completion / total`
  - `platform.cost.estimated`
- doctor 场景当前已接入的平台指标主路径：
  - `DoctorPlatformRunPersistenceService.persistPatientTaskRun(...)`
  - 可按 `agent_id / run_type / outcome / fallback reason` 统计 run 结果
  - 可按会话创建时间到完成/更新时间估算当前 run latency
- 当前 token/cost 已完成统一口径接入：
  - `ai-chat-service` 的 `DeepSeekClient` 以 `DIRECT_CHAT` scope 上报平台 token/cost
  - `doctor-assistant-agent` 的 `DeepSeekClient` 以 `AGENT_WORKFLOW` scope 上报平台 token/cost
- 当前价格配置默认值为 `0`
  - `ai-chat.metrics.cost.prompt-price-per-1k-tokens`
  - `ai-chat.metrics.cost.completion-price-per-1k-tokens`
  - `agent.metrics.cost.prompt-price-per-1k-tokens`
  - `agent.metrics.cost.completion-price-per-1k-tokens`
  - 后续补充真实单价后即可输出非零 `platform.cost.estimated`

### 10.4 审计

- [x] 定义平台统一审计事件
- [x] 保留患者可见输出与内部输出的隔离审计
- [x] 审计模型调用版本、prompt 版本、tool 版本

当前落实结果：

- 平台公共层已新增：
  - `PlatformAuditEvent`
  - `PlatformAuditEventType`
  - `PlatformAuditOutputScope`
  - `PlatformAuditEventRepository`
- 当前平台审计事件已统一包含：
  - `runId`
  - `conversationId`
  - `agentId`
  - `modelVersion`
  - `promptVersion`
  - `toolVersion`
  - `outputScope`
  - `payload`
- `PlatformAuditOutputScope` 已显式区分：
  - `PATIENT_VISIBLE`
  - `INTERNAL_ONLY`
- doctor 场景当前已接入的平台审计关键节点包括：
  - `RUN_PERSISTED`
  - `APPROVAL_STATUS_SYNCED`
- `DoctorPlatformRunPersistenceService` 已在平台路径内写入统一审计事件
  - 当前默认记录 `promptVersion = doctor-react-prompt-v1`
  - 当前默认记录 `toolVersion = doctor-toolkit-v1`
  - `modelVersion` 字段已预留，后续可在真实模型版本接入后补齐

---

## 11. Prompt 与配置治理

### 11.1 Prompt Registry

- [x] 设计 prompt 模板注册中心
- [x] 支持按 Agent/场景/版本管理 prompt
- [ ] 支持灰度切换 prompt 版本
- [x] 支持回滚 prompt

当前已落地：

- 新增平台公共 `PromptRegistry / PromptTemplateRenderer / PromptTemplateDescriptor`
- 支持按 `scene + agentId + type + version` 注册与渲染 prompt
- doctor 场景 `system / sub-agent / protocol-repair` prompt 已接入注册中心
- 审计事件中的 `promptVersion` 已改为从注册中心读取 active version
- 当前支持显式切换 active version 作为回滚手段
- 真正的灰度切换仍未实现：
  - 暂不支持按 `group / user / tenant` 对 prompt 版本做定向路由

### 11.2 配置治理

- [x] 将关键平台参数纳入统一配置管理：
  - timeout
  - max steps
  - allowed tools
  - fallback 开关
  - rollout 范围
  - safety thresholds
- [x] 明确哪些走本地配置，哪些走 Nacos

当前已落地：

- `doctor.agent.platform.*`
  - 已统一承接 `workflowId / platformAgentId / toolVersion / fallbackToTemplateOnAgentError / allowedTools`
- `ai-chat.agent.task.*`
  - 已统一承接入口升级请求的 `timeoutMillis / fallbackToDirectChatOnFailure / requireEvidence / requireAuditTrail`
- 当前状态说明：
  - `allowed tools` 已从 subAgent 默认偏好升级为“默认值 + 配置覆盖”
  - `fallback` 已从代码写死改为显式开关
  - `timeout` 已在入口升级请求侧收口为统一默认约束
  - `max steps / safety thresholds / rollout 范围` 已开始归并到统一配置模型：
    - `doctor.agent.runtime.*`
    - `doctor.agent.safety.*`
    - `ai-chat.agent.*`
  - 已补充本地配置 / Nacos 配置边界文档：
    - [MULTI-AGENT-CONFIG-GOVERNANCE.md](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/MULTI-AGENT-CONFIG-GOVERNANCE.md)
  - `Nacos` 接入暂未落地，当前仍以本地 Spring Boot 配置为主，但远端化优先级已经明确

---

## 15. 验收标准

- [ ] 新增一个 Agent 不需要修改核心编排主流程大量代码
- [ ] 一个用户请求可以被统一映射成平台 `run`
- [ ] direct chat 和 agent workflow 都可统一追踪
- [ ] 任一 Agent 的审批/恢复链路可复用统一机制
- [ ] 工具、Prompt、Agent、策略都支持版本化和灰度
- [ ] 关键风险场景具备自动化回归和 replay 能力
- [ ] 患者侧稳定性不因平台化改造显著下降

---

## 16. 当前优先级建议

建议优先顺序：

1. `Run 模型 + 状态机`
2. `Agent Registry + Tool Registry`
3. `统一 Trace / Audit / Replay`
4. `提炼 Runtime`
5. `入口接入平台`

---

## 17. 相关文档

- [AI 服务体系说明](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/README.md)
- [患者对话 Agent 协同系统](/Users/lejinbo/LLM/code-snippets/governance-suite/docs/patient-dialog-agent/README.md)
- [患者对话 Agent 演进计划](/Users/lejinbo/LLM/code-snippets/governance-suite/docs/patient-dialog-agent/evolution-plan.md)
- [Doctor Assistant Agent Engineering Guide](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/doctor-assistant-agent/ENGINEERING_GUIDE.md)
- [Doctor Assistant Agent 流式链路架构](/Users/lejinbo/LLM/code-snippets/governance-suite/ai/doctor-assistant-agent/AGENT_STREAMING_ARCHITECTURE.md)
