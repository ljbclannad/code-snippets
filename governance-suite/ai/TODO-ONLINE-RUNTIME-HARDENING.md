# AI 对话系统线上运行时治理 TODO

本文档专门收敛当前 `AI Chat x Doctor Assistant Agent` 主链路在真实上线后最容易暴露的运行时问题。

目标不是再补一份“设计说明”，而是把后续开发要逐项处理的线上问题整理成可执行 TODO，方便按优先级推进。

适用范围：

- `ai-chat-service`
- `doctor-assistant-agent`
- `vector-memory-service`
- `user-profile-service`

---

## 0. 总体目标

- 把当前链路从“功能可跑”推进到“高流量下可控、可降级、可补偿、可回放”
- 重点解决：
  - Token 上下文膨胀
  - QPS 和长尾 RT 不稳
  - 重复发送 / 幂等
  - 流式中断收口
  - 上下文乱序覆盖
  - 异步索引 / 记忆最终一致性
  - 热点租户 / 热点会话冲击
  - 降级、限流、拒绝策略不清晰

建议推进方式：

- P0：直接影响主回答正确性、稳定性或成本的问题
- P1：高并发下会明显放大，但当前还能靠人工观测兜住的问题
- P2：偏治理、补偿和长期维护体验的问题

---

## 1. Token 上下文压缩与 Budget 治理

### 1.1 问题定义

- 当前链路虽然已经有 `SessionContext`、`LayeredMemories`、`ReActPromptBuilder` 的压缩基础
- 但还缺统一的 token budget 治理策略
- 上线后容易出现：
  - prompt 过胖
  - 低价值上下文挤掉高价值证据
  - 长尾对话 RT 抖动
  - 单请求成本不可控

### 1.2 目标

- 给不同 prompt section 建立稳定预算
- 保证高价值信息优先进入 prompt
- 在多轮对话和 Agent 首轮里都能控制 token 膨胀

### 1.3 TODO

- [x] 在 `ai-chat-service` 补统一 `PromptBudgetPolicy`
  - 管理：
    - system prompt budget
    - current user message budget
    - profile budget
    - session context budget
    - short-term memory budget
    - long-term memory budget
    - tool result budget
- [x] 在 `ContextBudgetManager` 增加按 section 的字符/Token 级裁剪策略
- [x] 在 `SessionContextSectionBuilder` 增加字段优先级和截断规则
- [x] 在 `LayeredMemoryOrchestrator` 增加长期记忆进入 prompt 前的二次裁剪
  - 先按相似度
  - 再按类型
  - 再按预算截断
- [x] 在 `ReActPromptBuilder` 中增加 snapshot summary 压缩模板
  - `profileSnapshot`
  - `sessionContext`
  - `triggerDecision`
  - `memorySnapshot`
- [x] 为 prompt 渲染结果输出预算日志
  - 各 section 实际字符数
  - 预算命中情况
  - 被裁剪 section

### 1.4 验收标准

- `prompt tokens P95` 明显低于未治理版本
- 压缩后主链路 `P95 / P99` 改善
- 多轮连续追问场景下回答质量不明显下降
- 可以明确解释“哪些信息被裁掉了、为什么被裁掉”

---

## 2. QPS 提升与长尾 RT 优化

### 2.1 问题定义

- 当前链路串了上下文、画像、记忆、模型、Agent、工具等多个阶段
- 如果不做系统性治理，QPS 上来后最先坏的通常不是平均 RT，而是：
  - `P95 / P99` 飙升
  - 线程池排队
  - SSE 连接占满
  - 下游超时放大

### 2.2 目标

- 让轻请求尽量留在轻链路
- 让重请求具备明确的限流、拒绝和降级边界
- 让线程池和连接池不互相污染

### 2.3 TODO

- [ ] 输出主链路分段耗时指标
  - `SessionContext`
  - intent analyze
  - profile RPC
  - memory retrieval
  - rerank
  - model generation
  - agent invoke
- [x] 在 `ai-chat-service` 增加按路由结果的 RT 与降级率统计
  - `DIRECT_CHAT`
  - `AGENT_FALLBACK`
  - `PENDING_REVIEW_SHORT_CIRCUIT`
- [x] 审查并隔离线程池
  - `memoryExtractExecutor`
  - deepseek executor
  - agent stream executor
  - tool executor
- [x] 补各线程池 reject 指标和告警阈值
- [ ] 评估 `ConversationExecutionGuard` 在热点会话下的队头阻塞情况
- [ ] 形成一份链路级容量压测基线
  - 目标 QPS
  - `P95 / P99`
  - reject rate
  - degrade rate

### 2.4 验收标准

- 能明确给出目标 QPS 和达成后的压测报告
- 主链路 `P95 / P99` 在目标流量下可控
- 任一线程池饱和时不会拖垮整条链路

---

## 3. 主链路过载时的限流、拒绝与排队策略

### 3.1 问题定义

- 大流量时最危险的不是“偶尔慢一点”，而是系统没有明确策略：
  - 主链路到底该排队还是拒绝
  - 异步链路到底能堆多少
  - Agent 重任务到底能不能继续接

### 3.2 目标

- 同步主链路保实时
- 异步链路保最终一致
- 重任务链路有界排队

### 3.3 TODO

- [x] 在 `ai-chat-service` 明确主链路过载策略
  - 超额时：
    - 直接拒绝
    - 或降级回复
    - 不做无上限排队
- [x] 在 `ChatAsyncProcessingService` / MQ 链路补队列堆积监控
- [x] 为 `memory.extraction.queue`、聊天历史同步队列建立 backlog 告警
- [x] 在 Agent 入口定义有界并发和有界队列策略
  - 请求过多时直接拒绝或回退 direct chat
- [x] 将“主链路拒绝 / 异步入队 / 重任务限流”写成统一运行策略文档

### 3.4 验收标准

- 主链路高峰时不会无限排队
- 异步队列 backlog 可观测
- Agent 重任务不会因无界堆积导致系统雪崩

---

## 4. 请求幂等与重复发送治理

### 4.1 问题定义

- 当前有 `ConversationExecutionGuard`，但它解决的是“并发推进”，不是“重复请求幂等”
- 上线后常见场景：
  - 前端点两次发送
  - 网络重试
  - 客户端超时后重发
  - MQ 或下游重投

### 4.2 目标

- 同一轮请求只推进一次
- 相同重试请求直接复用已有结果
- 非幂等副作用工具绝不能重复执行

### 4.3 TODO

- [x] 给 `ChatRequest` 增加 `requestId`
- [x] 在 `ai-chat-service` 增加请求幂等存储
  - key 建议：
    - `conversationId + requestId`
- [ ] 如果无 `requestId`，补消息指纹方案
  - `conversationId + messageFingerprint + timeWindow`
- [ ] 为 `DoctorAssistantTaskRequest.constraints.idempotencyKey` 建立主链路透传和使用规范
- [ ] 梳理 `ActionExecutor` 中所有 `NON_IDEMPOTENT` 工具
  - 明确哪些必须加业务侧防重复
- [ ] 输出幂等命中率、重复请求率、重复工具执行率指标

### 4.4 验收标准

- 同一请求重试不会重复触发模型和 Agent
- 非幂等工具重复执行率接近 0
- 能通过日志回放解释一次请求是否命中幂等

---

## 5. 流式中断与对话结果收口

### 5.1 问题定义

- 流式请求最难的不是推流本身，而是中断后这轮对话到底怎么记
- 当前 `AiChatStreamService` 只有“完整完成后保存历史”的基础逻辑
- 还缺：
  - interrupted turn 的状态定义
  - partial response 是否保存的策略
  - partial response 是否进入异步后处理的边界

### 5.2 目标

- 明确区分：
  - `COMPLETED`
  - `INTERRUPTED`
  - `FAILED`
- 只有完成态才能污染正式历史、索引和长期记忆

### 5.3 TODO

- [x] 给流式回答增加显式状态枚举
  - `STREAMING / COMPLETED / INTERRUPTED / FAILED`
- [x] 扩展 `StreamResponseAccumulator`
  - 累积 full / partial content
  - 记录中断原因
- [x] 在 `AiChatStreamService` 中区分：
  - 自然完成
  - 客户端断开
  - 服务端异常
  - 超时
- [x] 只允许 `COMPLETED` 响应进入：
  - `handleChatCompleted`
  - 索引同步
  - 长期记忆提取
- [ ] 设计 partial response 的落库策略
  - 单独状态位
  - 或单独事件表
- [ ] 提供前端重连后的历史补拉方案

### 5.4 验收标准

- 半截回答不会误进入长期记忆
- 前端断流后，服务端能明确标记 turn 状态
- 能复盘每次流式中断的原因和比例

---

## 6. 下游超时、失败与分层降级

### 6.1 问题定义

- 现在链路里已经有一部分 fallback
- 但还缺统一的“依赖故障 -> 触发哪一级降级 -> 指标怎么打”的标准口径

### 6.2 目标

- 任一依赖出问题时，系统知道要退到哪一层
- 降级是显式可观测的，不是静默劣化

### 6.3 TODO

- [ ] 整理主链路依赖降级矩阵
  - profile RPC timeout -> empty profile
  - memory retrieval timeout -> short-term only
  - rerank timeout -> raw ranking
  - agent timeout -> template fallback / direct chat fallback
  - async sync failure -> audit only
- [ ] 在 `ai-chat-service` 增加统一降级事件打点
- [ ] 在 `doctor-assistant-agent` 增加模板回退原因统计
- [ ] 给每种降级定义：
  - 触发条件
  - 用户可见影响
  - 是否需要告警

### 6.4 验收标准

- 任一降级都能在日志和指标中定位
- 故障演练时能稳定进入预期降级路径

---

## 7. 上下文乱序覆盖与版本保护

### 7.1 问题定义

- 当前 `SessionContext` 写 Redis 还是典型的最后写入覆盖
- 遇到消息乱序、异步回放、重试乱序时，旧消息可能覆盖新上下文

### 7.2 目标

- 上下文更新具备版本保护
- 旧消息不能覆盖新状态

### 7.3 TODO

- [x] 给 `SessionContext` 增加版本字段或 `lastMessageAt`
- [x] 在 `SessionContextService.updateContext(...)` 增加版本检查
- [x] 在 `recordAgentOutcome(...)` 增加写入保护
- [x] 在 `recordTaskTriggerDecision(...)` 增加写入保护
- [x] 设计乱序场景回放测试

### 7.4 验收标准

- 旧请求回放不会覆盖新上下文
- 乱序场景可通过自动化测试复现和验证

---

## 8. 热点租户 / 热点会话治理

### 8.1 问题定义

- AI 流量经常是热点型，而不是均匀型
- 某个大客户、某个群、某个热点医生任务可能瞬间放大

### 8.2 目标

- 热点租户不能拖垮整体链路
- 热点会话要有更强限流和降级策略

### 8.3 TODO

- [ ] 在 `QuotaService` 扩展 tenant/group 维度配额
- [ ] 补热点对象监控
  - `tenantId`
  - `groupId`
  - `customerId`
- [ ] 对热点 key 增加更强限流和降级规则
- [ ] 为 SSE、Agent、tool executor 增加热点流量告警

### 8.4 验收标准

- 热点租户不会显著拖慢其他租户
- 监控中能快速识别热点来源

---

## 9. 异步最终一致性与补偿任务

### 9.1 问题定义

- 当前链路天然是最终一致：
  - 回答同步返回
  - 历史落库异步
  - 索引同步异步
  - 记忆提取异步
- 这没问题，但缺补偿机制就会形成长期脏数据

### 9.2 目标

- 明确主数据源
- 允许异步延迟
- 提供可重复执行的补偿任务

### 9.3 TODO

- [ ] 明确：
  - 聊天历史主数据源
  - 正式记忆主数据源
  - ES / Milvus / Redis 属于派生数据
- [ ] 增加聊天历史到 ES 的重放任务
- [ ] 增加聊天历史到记忆提取的重放任务
- [ ] 设计补偿任务幂等逻辑
- [ ] 增加补偿成功率 / backlog 指标

### 9.4 验收标准

- 任意一段异步消费失败后都能补偿修复
- 不需要人工直接改库才能恢复索引或记忆

---

## 10. Prompt / 模型版本回滚与 bad case 回放

### 10.1 问题定义

- AI 系统上线后，很多问题不是代码 bug，而是 prompt 变更、模型切换带来的行为漂移
- 如果没有版本和回放能力，就很难定位“为什么昨天能答对、今天答错了”

### 10.2 目标

- 所有关键回答都能回溯 prompt version / model version
- bad case 能重放

### 10.3 TODO

- [ ] 为 chat 主链路记录：
  - model version
  - prompt template version
- [ ] 为 Agent 主链路记录：
  - system prompt version
  - sub-agent prompt version
  - tool catalog version
- [ ] 建立 bad case 回放样本集
- [ ] 支持按版本对比新旧输出差异

### 10.4 验收标准

- 任一 bad case 都能定位使用的 prompt / model 版本
- 回滚后可以验证问题是否收敛

---

## 11. 安全、脱敏与成本保护

### 11.1 问题定义

- 上线后除了可用性，还要防：
  - 敏感信息进入日志
  - prompt 透传过量
  - token 成本失控
  - 工具调用成本无上限

### 11.2 目标

- 数据最小化
- 成本可观测
- 出现异常时能快速熔断重能力

### 11.3 TODO

- [ ] 梳理日志脱敏策略
- [ ] 对 prompt 构建链路做最小化审查
- [ ] 增加 token 成本报表
  - per user
  - per tenant
  - per route
- [ ] 增加工具调用成本和次数报表
- [ ] 增加重链路熔断开关
  - 画像
  - 长期记忆
  - rerank
  - Agent

### 11.4 验收标准

- 敏感信息不会直接出现在普通日志
- 成本异常能被快速识别和切断

---

## 12. 优先级建议

### P0

- [ ] 请求幂等与重复发送治理
- [ ] 流式中断状态收口
- [x] 上下文版本保护
- [ ] 主链路过载时的限流 / 拒绝 / 降级策略
- [ ] Token budget 统一治理

### P1

- [ ] 分段性能指标与容量压测
- [ ] 热点租户 / 热点会话治理
- [ ] 异步补偿任务
- [ ] 降级矩阵和可观测性完善

### P2

- [ ] Prompt / 模型版本回滚体系
- [ ] 成本报表与更细粒度的治理开关

---

## 13. 建议开发顺序

如果后续要逐步落代码，我建议按下面顺序推进：

1. `AiChatStreamService`
   - 先补流式状态与 interrupted 收口
2. `AiChatService` / `ChatRequest`
   - 补 `requestId` 幂等
3. `SessionContextService`
   - 补版本保护
4. `ContextBudgetManager` / prompt builders
   - 补统一 token budget
5. `ChatAsyncProcessingService`
   - 补异步补偿任务入口

---

## 14. 完成定义

这份 TODO 不是“写完文档就算完成”，每一项真正完成至少要满足：

- 有代码落点
- 有监控指标
- 有异常场景测试
- 有上线开关或回滚方案
- 有 bad case 或压测结果支撑
