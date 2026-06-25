# AI 对话主链路统一运行策略

本文档把 `ai-chat-service`、`doctor-assistant-agent` 这条线上主链路的运行时策略收口成一套统一口径，重点回答三个问题：

1. 主链路超载时是否排队还是拒绝
2. 异步链路可以堆多少、怎么观测
3. Agent 重任务什么时候继续接，什么时候限流或回退

---

## 1. 策略目标

- 患者主回答优先保实时，不能无限排队
- 异步链路优先保最终一致，但必须有界、可观测、可告警
- Agent / tool 这类重任务必须独立限流，不能拖垮普通对话

---

## 2. 主链路拒绝策略

### 2.1 当前实现

- `ai-chat-service` 主对话异步编排使用 `chatExecutor`
- `chatExecutor` 是有界线程池
- 当线程池拒绝新任务时：
  - 直接抛 `RejectedExecutionException`
  - `AiChatService.buildErrorResponse(...)` 收口为固定兜底：
    - `当前服务负载较高，请稍后再试`
  - 同时记录：
    - `ai.executor.rejected{executor="chatExecutor"}`
    - `ai.chat.route.results{route_result="OVERLOAD_REJECTED", degraded="true"}`

### 2.2 策略原则

- 主链路不做无上限排队
- 主链路不把线程池饱和伪装成普通业务失败
- 过载时优先快速失败，避免把 RT、连接数和下游超时一起放大

### 2.3 面试可答口径

- 轻请求和普通对话要优先保活，因此主链路线程池必须有界
- 当主链路线程池已满时，继续排队只会把 `P95/P99` 和超时连锁放大，所以这里选择直接拒绝
- 拒绝不是 silent fail，而是有固定兜底文案和独立指标，便于压测与运维观察 reject rate

---

## 3. 异步入队策略

### 3.1 当前异步链路

- 对话完成后由 `ChatAsyncProcessingService` 异步执行：
  - 历史落库
  - 聊天历史同步到 ES
  - 触发长期记忆提取
- 链路中的关键队列：
  - `chat.history.sync.queue`
  - `memory.extraction.queue`

### 3.2 当前治理

- `memoryExtractExecutor` 独立于主链路线程池，避免边路任务污染主回答
- 已暴露 backlog / consumer / pending 指标：
  - `ai.async.queue.backlog{queue="chat_history_sync", metric_type="ready|consumers"}`
  - `ai.async.queue.backlog{queue="memory_extraction", metric_type="ready|consumers"}`
  - `ai.rpc.pending.requests{client="user_profile|vector_memory"}`
- 已补阈值告警：
  - `AsyncBacklogAlertMonitor`
  - `AsyncBacklogAlertProperties`

### 3.3 策略原则

- 异步链路允许短时堆积，但必须有界、可观测
- backlog 超阈值时先告警，再根据持续时间决定是否扩容、降级或临时关闭部分能力
- 异步任务失败不影响当前轮主回答，但必须进入后续补偿体系

### 3.4 Nacos 动态阈值

- backlog 告警阈值支持通过 Nacos Config 动态刷新
- 当前配置入口：
  - `bootstrap.yml` 通过 `spring-cloud-starter-alibaba-nacos-config` 接入 `ai-chat-service.yml`
  - `AsyncBacklogAlertProperties` 标注 `@RefreshScope`
- 推荐在 Nacos 中维护：

```yml
ai-chat:
  async-backlog-alert:
    enabled: true
    check-interval-ms: 30000
    repeat-alert-interval-ms: 300000
    queue-thresholds:
      memory-extraction-ready-threshold: 100
      chat-history-sync-ready-threshold: 100
    rpc-thresholds:
      user-profile-pending-threshold: 50
      vector-memory-pending-threshold: 50
```

---

## 4. Agent 重任务限流策略

### 4.1 当前实现基础

- `doctor-assistant-agent` 已拆出独立线程池：
  - `agentStreamExecutor`
  - `patientTaskStreamExecutor`
  - `deepSeekRequestExecutor`
  - `agentToolExecutor`
- 已补 executor 级指标：
  - `agent.executor.active`
  - `agent.executor.pool.size`
  - `agent.executor.queue.size`
  - `agent.executor.queue.remaining`
  - `agent.executor.max.pool.size`
  - `agent.executor.rejected`

### 4.2 当前建议策略

- Agent 是重链路，不应与普通 direct chat 共享线程和排队能力
- 当 Agent 入口线程池、DeepSeek 请求线程池或 tool executor 饱和时：
  - 患者入口优先回退 direct chat
  - 医生工作台可按场景选择直接拒绝或稍后重试
- 患者侧“能回退就回退”，医生侧“不能保证结果质量时就拒绝”

### 4.3 后续待补

- 在 Agent 入口增加统一的限流 / 拒绝 / fallback 策略枚举
- 将不同任务类型映射为不同资源等级
  - 轻分析
  - 重分析
  - 高风险待审批链路

---

## 5. 运行时决策顺序

建议按下面顺序做运行时判断：

1. 主链路线程池是否可接单
2. 当前会话是否命中并发保护 / 幂等复用
3. 是否命中轻链路直出条件
4. 是否需要升级 Agent
5. Agent 相关线程池 / 下游资源是否允许继续接单
6. 若不允许：
   - 患者侧优先 direct chat fallback
   - 医生侧优先明确拒绝
7. 回答成功后再进入异步历史 / ES / 记忆链路

---

## 6. 告警与处理建议

### 6.1 建议优先关注

- `ai.executor.rejected`
- `ai.chat.route.results{route_result="OVERLOAD_REJECTED"}`
- `ai.async.queue.backlog`
- `ai.rpc.pending.requests`
- `agent.executor.rejected`

### 6.2 建议处理顺序

1. 先看 reject 是否来自主链路还是 Agent 链路
2. 再看 backlog 是瞬时尖峰还是持续堆积
3. 如果是持续堆积：
   - 优先降级长期记忆 / 推荐 / Agent
   - 再考虑扩线程池、扩消费者或扩实例
4. 如果是热点租户或热点会话：
   - 优先做租户级和会话级限流

---

## 7. 当前结论

当前这条链路已经具备基础的“上线级运行策略雏形”：

- 主链路有界并支持快速拒绝
- 异步链路有 backlog 指标和阈值告警
- Agent 重任务已经做了线程池隔离和拒绝指标

仍待继续补强的内容：

- Agent 入口统一限流 / 回退策略
- backlog 持续堆积后的自动降级联动
- 更完整的补偿任务与回放体系
