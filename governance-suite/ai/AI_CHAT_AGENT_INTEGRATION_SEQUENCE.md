# AI Chat x Doctor Assistant Agent 整合时序图

本文档描述当前 `ai-chat-service` 与 `doctor-assistant-agent` 整合后的真实调用链路，重点覆盖：

- 会话上下文的创建、读取与更新
- 用户画像的 RabbitMQ RPC 获取
- 短期记忆与长期记忆的装载流程
- `TaskTriggerRouter` 的升级判定
- 普通对话与 Agent 升级两条分支
- Agent 安全清洗、人工复核和回退普通对话
- 对话完成后的异步历史落库与记忆提取

## 参与组件

- `AiChatController` / `AiChatService`
- `SessionContextService` + Redis
- `UserProfileClientFixed` + RabbitMQ + `user-profile-service`
- `LayeredMemoryOrchestrator` / `RetrievalQueryRewriteService` / `MemoryService`
- `TaskTriggerRouter` / `AgentDispatchPolicy`
- `HttpDoctorAssistantAgentGateway`
- `PatientTaskController` / `PatientTaskAgentService` / `PatientTaskSafetyService`
- `DeepSeekClient`
- `ChatAsyncProcessingService`

## 详细时序图

```mermaid
sequenceDiagram
    autonumber
    actor Patient as Patient
    participant Controller as AiChatController
    participant Chat as AiChatService
    participant Guard as ConversationExecutionGuard
    participant Quota as QuotaService
    participant Context as SessionContextService
    participant Redis as Redis
    participant Intent as DeepSeekIntentAnalyzer
    participant Profile as UserProfileClientFixed
    participant MQ as RabbitMQ
    participant ProfileSvc as user-profile-service
    participant MemoryOrch as LayeredMemoryOrchestrator
    participant Rewrite as RetrievalQueryRewriteService
    participant Filter as IntentMemoryFilterStrategy
    participant Memory as MemoryService
    participant VectorSvc as vector-memory-service
    participant Router as TaskTriggerRouter
    participant Policy as AgentDispatchPolicy
    participant ReqFactory as DoctorAssistantTaskRequestFactory
    participant AgentGW as HttpDoctorAssistantAgentGateway
    participant AgentCtrl as PatientTaskController
    participant AgentSvc as PatientTaskAgentService
    participant Safety as PatientTaskSafetyService
    participant LLM as DeepSeekClient
    participant Async as ChatAsyncProcessingService
    participant DB as MySQL

    Patient->>Controller: POST /api/chat
    Controller->>Chat: handleChatRequest(request)
    Chat->>Chat: ensureConversationId()
    Chat->>Chat: metrics.recordChatRequest()
    Chat->>Guard: tryExecute(conversationId, supplier)

    Guard->>Quota: checkQuota(customerId, estimatedTokens)
    Guard->>Context: getOrCreateContext(conversationId, customerId, groupId, message)
    Context->>Redis: GET ai:session:context:{conversationId}

    alt Redis 中不存在上下文
        Redis-->>Context: null
        Context->>Intent: analyzeIntentWithTimeout(message, [], 5000ms)
        alt 意图分析成功
            Intent-->>Context: SessionContext(topic, phase, intent, complexity)
        else 意图分析失败或超时
            Context->>Context: createDefaultContext()
        end
        Context->>Context: 写入 conversationId/customerId/groupId/messageCount
        Context->>Redis: SET context, TTL=30min
    else Redis 中已有上下文
        Redis-->>Context: existing SessionContext
        opt 命中重新分析条件
            Context->>Intent: analyzeIntentWithTimeout(message, recentSummaries, 5000ms)
            Intent-->>Context: updated SessionContext
            Context->>Context: mergeContextUpdate(...)
            Context->>Redis: SET refreshed context
        end
    end
    Context-->>Chat: SessionContext

    Chat->>Router: route(TaskTriggerContext)
    Router->>Router: 检查追问中 / 闲聊 / 事实问答 / 分析意图 / 风险信号 / 下一步动作
    Router-->>Chat: TaskTriggerDecision
    Chat->>Context: recordTaskTriggerDecision(conversationId, decision)
    Context->>Redis: SET lastTaskTrigger*

    par 用户画像加载
        Chat->>Profile: requestUserProfile(customerId)
        Profile->>Profile: 创建 CompletableFuture + pendingRequests
        Profile->>MQ: send user.profile.request(replyTo, correlationId)
        MQ->>ProfileSvc: 投递画像请求
        ProfileSvc-->>MQ: 画像响应
        MQ-->>Profile: ai.chat.user.profile.reply
        Profile->>Profile: future.complete(response)
        Profile-->>Chat: UserProfileDTO
    and 画像完成后装载记忆
        Chat->>MemoryOrch: loadMemories(groupId, customerId, message, sessionContext, profile, memoryEnabled, retrievalCount)
        MemoryOrch->>MemoryOrch: extractShortTermMemories(sessionContext)
        Note over MemoryOrch: 短期记忆来自 currentTopic、userIntent、pendingQuestions、recentSummaries
        alt 降级关闭记忆 or retrievalCount=0 or 当前消息不需要检索
            MemoryOrch-->>Chat: shortTermMemories only
        else 需要长期记忆检索
            MemoryOrch->>Rewrite: rewrite(message, sessionContext, profile)
            Rewrite-->>MemoryOrch: rewrittenQuery + keywords + suggested filters
            MemoryOrch->>Filter: decide(sessionContext)
            Filter-->>MemoryOrch: memoryTypes/minConfidence/recentDays
            MemoryOrch->>MemoryOrch: mergeRetrievalQuery + mergeFilterDecision
            MemoryOrch->>Memory: retrieveMemories(groupId, customerId, query, filters)
            Memory->>VectorSvc: requestMemories(...)
            VectorSvc-->>Memory: candidate memories
            Memory->>Memory: similarity filter + dedup + topK
            Memory-->>MemoryOrch: longTermMemories
            MemoryOrch-->>Chat: shortTermMemories + longTermMemories
        end
    end

    alt 画像补全追问优先触发
        Chat->>Chat: handleProfileCompletionIfNeeded(...)
        Chat->>Context: updateContext(conversationId, userMessage, "")
        Context->>Redis: GET/SET context
        Chat->>Async: handleChatCompleted(follow-up message)
        Async->>DB: 插入对话历史
        Async->>Memory: extractMemory(...)
        Chat-->>Controller: ChatResponse.success(followUpMessage)
        Controller-->>Patient: 追问消息
    else 正常进入对话或 Agent 分流
        alt shouldUpgradeToAgent = false
            Chat->>LLM: chat(request, profile, sessionContext, layeredMemories)
            LLM-->>Chat: DifyResponse(answer, usage)
            Chat->>Context: updateContext(conversationId, userMessage, aiResponse)
            Context->>Redis: GET/SET context
            Chat->>Async: handleChatCompleted(request, response, correlationId)
            Async->>DB: 保存对话历史
            Async->>MQ: 同步聊天记录到索引队列
            Async->>Memory: extractMemory(groupId, customerId, userMessage, aiResponse)
            Chat-->>Controller: ChatResponse.success(...)
            Controller-->>Patient: 普通对话回复
        else shouldUpgradeToAgent = true
            Chat->>Policy: evaluate(request, triggerDecision)
            alt 灰度未放行 or 熔断打开
                Policy-->>Chat: invokeAgent=false
                Chat->>LLM: chat(request, profile, sessionContext, layeredMemories)
                LLM-->>Chat: DifyResponse
                Chat->>Context: updateContext(...)
                Chat->>Async: handleChatCompleted(...)
                Chat-->>Controller: ChatResponse.success(direct_chat_fallback)
                Controller-->>Patient: 普通对话回退回复
            else 放行 Agent
                Policy-->>Chat: invokeAgent=true
                Chat->>ReqFactory: buildPatientDialogRequest(...)
                ReqFactory-->>Chat: DoctorAssistantTaskRequest
                Note over ReqFactory: 附带 sessionContext、profileSnapshot、memorySnapshot、triggerDecision、constraints
                Chat->>AgentGW: POST /api/patient-tasks/analyze
                AgentGW->>AgentCtrl: /api/patient-tasks/analyze
                AgentCtrl->>AgentSvc: analyze(request)
                AgentSvc->>AgentSvc: buildTaskId + agentSessionId
                AgentSvc->>AgentSvc: 按 taskType 生成 structuredResult
                AgentSvc->>AgentSvc: containsHighRiskSignal(patientMessage)
                AgentSvc->>Safety: sanitize(patientSafeReply, structuredResult, needsHumanReview)
                Safety-->>AgentSvc: allowed or blocked
                AgentSvc->>AgentSvc: 组装 pendingTask + doctorWorkbenchPayload
                AgentSvc-->>AgentCtrl: DoctorAssistantTaskResponse
                AgentCtrl-->>AgentGW: DoctorAssistantTaskResponse
                AgentGW-->>Chat: DoctorAssistantTaskResponse

                alt Agent 返回 SKIPPED
                    Chat->>LLM: chat(request, profile, sessionContext, layeredMemories)
                    LLM-->>Chat: DifyResponse
                    Chat->>Context: updateContext(...)
                    Chat->>Async: handleChatCompleted(...)
                    Chat-->>Controller: ChatResponse.success(agent_skipped_fallback)
                    Controller-->>Patient: 普通对话回退回复
                else Agent 返回 ERROR 或 patientSafeReply 为空
                    Chat->>LLM: chat(request, profile, sessionContext, layeredMemories)
                    LLM-->>Chat: DifyResponse
                    Chat->>Context: updateContext(...)
                    Chat->>Async: handleChatCompleted(...)
                    Chat-->>Controller: ChatResponse.success(agent_error_fallback)
                    Controller-->>Patient: 普通对话回退回复
                else Agent 返回 SUCCESS / PENDING_APPROVAL
                    Chat->>Chat: buildAgentChatResponse(...)
                    Note over Chat: 如需人工复核，追加“已同步给医生进一步评估”提示
                    Chat->>Context: updateContext(conversationId, userMessage, patientSafeReply)
                    Context->>Redis: GET/SET context
                    Chat->>Async: handleChatCompleted(request, patientSafeReply, correlationId)
                    Async->>DB: 保存患者可见回复到对话历史
                    Async->>MQ: 同步聊天记录到索引队列
                    Async->>Memory: extractMemory(groupId, customerId, userMessage, patientSafeReply)
                    Note over Chat: 只把 patientSafeReply 并回患者主链路，不写入医生侧 structuredResult / workbench payload
                    Chat-->>Controller: ChatResponse.success(patientSafeReply)
                    Controller-->>Patient: 患者安全回复
                end
            end
        end
    end
```

## 关键分支说明

### 1. 会话上下文

- 首次对话时，`SessionContextService` 会先尝试从 Redis 读取上下文。
- 若上下文不存在，会调用 `DeepSeekIntentAnalyzer` 做首轮意图分析；失败则回退默认上下文。
- 后续轮次会根据 `shouldReanalyzeIntent(...)` 决定是否重新分析，并把结果写回 Redis。
- 每次任务触发判定后，还会把 `lastTaskTriggerRoute / taskType / reasons / summary` 记录到上下文里，便于复盘。

### 2. 用户画像

- `UserProfileClientFixed` 采用 RabbitMQ RPC 模式，而不是本地直连 HTTP。
- `ai-chat-service` 发送请求时会登记 `correlationId -> CompletableFuture`。
- `user-profile-service` 返回响应后，由固定 reply 队列监听器完成 future，主链路再继续。

### 3. 记忆处理

- 短期记忆不是单独调用服务，而是直接从 `SessionContext` 提取。
- 长期记忆检索由 `LayeredMemoryOrchestrator` 统一编排：
  - `RetrievalQueryRewriteService` 负责改写检索 query
  - `IntentMemoryFilterStrategy` 负责根据当前意图决定过滤策略
  - `MemoryService` 负责向 `vector-memory-service` 发请求并做相似度过滤、去重和裁剪
- 降级模式下可以完全跳过长期记忆检索。

### 4. Agent 升级判定

- `TaskTriggerRouter` 会优先拦截画像补全追问、闲聊、简单事实问答。
- 只有命中分析、风险分层、下一步建议等信号，才会切到 `DOCTOR_ASSISTANT_AGENT` 路由。
- 路由命中后仍不会立刻调用 Agent，还要经过 `AgentDispatchPolicy` 的灰度和熔断判断。

### 5. Agent 响应的三层输出

- `patientSafeReply`：面向患者的安全回复
- `structuredResult`：面向医生侧的结构化分析结果
- `doctorWorkbenchPayload`：面向工作台和治理系统的内部载荷

### 6. Agent 成功分支现在如何并回主链路

- 普通对话分支和 Agent 成功分支现在都会执行：
  - `SessionContextService.updateContext(...)`
  - `ChatAsyncProcessingService.handleChatCompleted(...)`
  - 进而触发历史落库、索引同步、记忆提取
- 区别在于 Agent 成功分支只把 `patientSafeReply` 并回患者主链路。
- `structuredResult` 和 `doctorWorkbenchPayload` 仍然保留在医生侧/工作台侧语义中，不会进入患者上下文或记忆抽取。

## 配置关注点

```yaml
ai-chat:
  agent:
    enabled: false
    base-url: ${DOCTOR_ASSISTANT_AGENT_BASE_URL:http://localhost:8088}
    timeout-ms: 8000
    auto-fallback-to-direct-chat: true
    patient-conservative-readonly-mode: true
    enabled-task-types:
      - PATIENT_FOLLOW_UP_ANALYSIS
      - RISK_STRATIFICATION_AND_ESCALATION
      - NEXT_FOLLOW_UP_ACTION_RECOMMENDATION
    enabled-group-ids: []
    circuit-breaker-open: false
```

## 推荐阅读

- [AI 服务总览](README.md)
- [患者对话 Agent 协同系统](../docs/patient-dialog-agent/README.md)
- [AI Chat Flow 详解](ai-chat-service/README-CHAT-FLOW.md)
