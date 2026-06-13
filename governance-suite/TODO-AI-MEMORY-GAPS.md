# AI Memory System TODO

这份文档用于跟踪当前 `governance-suite` 中 AI 对话、长期记忆、画像候选态相关能力的未实现项。

目标：
- 给后续开发提供稳定的任务清单
- 按优先级拆分实现顺序
- 每项尽量对应到明确的代码入口和验收结果

建议开发顺序：
1. 先补齐核心链路一致性
2. 再增强检索与 Prompt 编排能力
3. 最后完善候选态、冲突治理与审计闭环

---

## P0 核心链路缺口

### 1. 长期记忆过滤做 SQL 层下推
- [x] 将长期记忆过滤从服务层过滤下推到 SQL 查询层
- 当前现状：
  `vector-memory-service` 里主要通过 `matchesFilter(...)` 在 Java 层过滤，见 [MemoryRetrievalService.java](/Users/lejinbo/LLM/code-snippets/governance-suite/vector-memory-service/src/main/java/com/lx/governance/vectormemory/service/MemoryRetrievalService.java:203)
- 当前问题：
  先查再滤会放大无效数据量，也会影响 rerank 前候选质量
- 目标结果：
  `memoryType`、`minConfidence`、`verifiedOnly`、`recentDays` 等过滤条件在 mapper / SQL 层完成
- 建议落点：
  - [MemoryRetrievalService.java](/Users/lejinbo/LLM/code-snippets/governance-suite/vector-memory-service/src/main/java/com/lx/governance/vectormemory/service/MemoryRetrievalService.java:80)
  - `AiGroupMemoryMapper` 及对应 XML
- 验收标准：
  - 检索接口行为不变
  - SQL 层可按过滤条件缩小结果集
  - 过滤相关测试覆盖 mapper 和 service 两层

### 2. 按意图类型动态设置长期记忆过滤策略
- [x] 在 `ai-chat-service` 中按 `userIntent` 动态生成长期记忆过滤条件
- 当前现状：
  记忆检索会走 rewrite 和分层编排，但没有基于意图类型切换过滤策略
- 当前问题：
  饮食、运动、症状、用药等场景应使用不同的 `memoryType / confidence / recency` 策略
- 目标结果：
  对不同意图建立独立的长期记忆过滤模板
- 建议落点：
  - [LayeredMemoryOrchestrator.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai-chat-service/src/main/java/com/lx/governance/aichat/service/LayeredMemoryOrchestrator.java:23)
  - `MemoryService`
  - 新增意图到过滤条件的策略类
- 验收标准：
  - `userIntent` 不同，传给下游记忆检索的 filter 不同
  - 至少覆盖 3 到 5 类主要意图
  - 单测能验证策略分支

### 3. 流式链路接入真实画像拉取
- [x] 让流式聊天链路使用真实用户画像，而不是空画像
- 当前现状：
  [AiChatStreamService.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai-chat-service/src/main/java/com/lx/governance/aichat/service/AiChatStreamService.java:202) 中 `fetchUserProfile(...)` 仍是 `TODO`
- 当前问题：
  流式和非流式链路行为不一致，导致流式回答上下文质量偏低
- 目标结果：
  流式链路复用与非流式一致的画像获取方式
- 建议落点：
  - [AiChatStreamService.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai-chat-service/src/main/java/com/lx/governance/aichat/service/AiChatStreamService.java:202)
  - `UserProfileClientFixed`
- 验收标准：
  - 流式链路能拿到真实画像
  - 异常时有清晰 fallback
  - 流式测试覆盖成功与降级场景

### 4. 流式链路写回完整 assistant 响应
- [x] 流式回答完成后，把完整 assistant 内容写回会话上下文和历史
- 当前现状：
  [AiChatStreamService.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai-chat-service/src/main/java/com/lx/governance/aichat/service/AiChatStreamService.java:238) 更新上下文时传的是空字符串
- 当前问题：
  流式对话无法完整参与后续上下文连续性、摘要和记忆抽取
- 目标结果：
  在 stream 完成时累积完整文本并用于 `updateContext(...)`
- 建议落点：
  - [AiChatStreamService.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai-chat-service/src/main/java/com/lx/governance/aichat/service/AiChatStreamService.java:190)
  - `StreamResponseAccumulator`
- 验收标准：
  - 流式回答结束后，上下文中的 assistant 消息完整可用
  - 下轮对话可以利用该回答形成连续性

---

## P1 检索与 Prompt 能力增强

### 5. Query Rewrite 从“复杂问题启用 LLM”演进为更完整策略
- [x] 完善当前 LLM rewrite 机制，而不是只停留在 `complex` 问题启用
- 当前现状：
  [RetrievalQueryRewriteService.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai-chat-service/src/main/java/com/lx/governance/aichat/service/RetrievalQueryRewriteService.java:77) 现在是 `complex` 走 LLM，其他走规则版
- 当前问题：
  仍依赖规则版 baseline 和 fallback，策略维度还比较单一
- 目标结果：
  建立更稳定的 rewrite 策略体系
- 子任务：
  - [x] 增加 rewrite 命中率和质量监控
  - [x] 增加更多触发条件，而不仅仅依赖 `complexity`
  - [x] 对 rewrite 结果做更细致的质量校验
  - [x] 评估并实现轻结构化 rewrite 输出
- 验收标准：
  - 能清楚区分 `RULE / LLM / FALLBACK_RULE`
  - 有可观测指标支撑效果评估

### 6. Prompt 编排从集中式管理演进为可插拔 section pipeline
- [x] 将 Prompt 编排从单体 `ContextBudgetManager` 演进为更易扩展的 section pipeline
- 当前现状：
  编排逻辑仍主要集中在 [ContextBudgetManager.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai-chat-service/src/main/java/com/lx/governance/aichat/context/ContextBudgetManager.java:49)
- 当前问题：
  新增区块、按场景替换区块、测试单个 section 都不够自然
- 目标结果：
  将核心画像、会话上下文、短期记忆、长期记忆、历史、扩展画像拆成独立 section builder
- 建议落点：
  - `ContextBudgetManager`
  - 新增 `PromptSectionBuilder` 一类抽象
- 验收标准：
  - 每个 section 可独立构建和测试
  - 总装配器只负责预算和顺序控制

### 7. Redis 最近记忆路径补齐过滤一致性
- [x] 让 Redis recent memory 路径和 MySQL/Milvus 路径使用一致的过滤规则
- 当前现状：
  Redis 结果解析后直接进入合并去重，没有完整使用 `MemorySearchFilter`
- 当前问题：
  不同来源的候选记忆可能出现过滤口径不一致
- 目标结果：
  recent memory 也能参与 `memoryType / recentDays / confidence / verifiedOnly` 等统一过滤
- 建议落点：
  - [MemoryRetrievalService.java](/Users/lejinbo/LLM/code-snippets/governance-suite/vector-memory-service/src/main/java/com/lx/governance/vectormemory/service/MemoryRetrievalService.java:96)
- 验收标准：
  - Redis / MySQL / Milvus 三路过滤行为一致

### 8. 自动确认策略按风险和来源细化
- [ ] 将候选态自动确认从固定阈值升级为分层策略
- 当前现状：
  画像和记忆候选都还是固定阈值：
  - [ProfileCandidateFieldService.java](/Users/lejinbo/LLM/code-snippets/governance-suite/user-profile-service/src/main/java/com/lx/governance/profile/service/ProfileCandidateFieldService.java:30)
  - [CandidateMemoryService.java](/Users/lejinbo/LLM/code-snippets/governance-suite/vector-memory-service/src/main/java/com/lx/governance/vectormemory/service/CandidateMemoryService.java:25)
- 当前问题：
  高风险字段与低风险字段不应共享同一确认标准
- 目标结果：
  支持按字段类型、来源可信度、记忆类型配置不同自动确认门槛
- 验收标准：
  - 规则配置化
  - 高风险场景默认更保守

### 9. 画像正式回写范围扩展与通用化
- [ ] 扩大画像候选字段回写覆盖范围，减少硬编码字段判断
- 当前现状：
  [ProfileCandidateFieldService.java](/Users/lejinbo/LLM/code-snippets/governance-suite/user-profile-service/src/main/java/com/lx/governance/profile/service/ProfileCandidateFieldService.java:118) 里回写逻辑是分支式硬编码
- 当前问题：
  新字段接入成本高，扩展性弱
- 目标结果：
  建立字段路径到正式数据源写回器的映射机制
- 验收标准：
  - 新字段接入不需要继续堆大段 `if/switch`
  - 回写失败可追踪

---

## P2 候选态、冲突治理与审计闭环

### 10. 记忆冲突系统升级为完整语义冲突系统
- [x] 将长期记忆冲突从启发式文本冲突升级为语义冲突系统
- 当前现状：
  [CandidateMemoryService.java](/Users/lejinbo/LLM/code-snippets/governance-suite/vector-memory-service/src/main/java/com/lx/governance/vectormemory/service/CandidateMemoryService.java:182) 里的冲突判定还是前缀 + 文本不等的第一版
- 当前问题：
  对同义表达、否定表达、条件差异、时态变化等支持不足
- 目标结果：
  支持更稳定的语义归一、冲突打分、冲突类型识别
- 子任务：
  - [x] 语义标准化
  - [x] 正负极性增强
  - [x] 冲突类型建模
  - [x] 冲突分数和置信度统一

### 11. 画像冲突系统升级为语义冲突系统
- [x] 将字段值级别冲突升级为语义冲突系统
- 当前现状：
  [ProfileCandidateFieldService.java](/Users/lejinbo/LLM/code-snippets/governance-suite/user-profile-service/src/main/java/com/lx/governance/profile/service/ProfileCandidateFieldService.java:50) 更偏“同字段不同值”的直接冲突
- 当前问题：
  对“近义值 / 枚举映射 / 时效性 / 来源冲突”处理能力不足
- 目标结果：
  支持字段语义标准化与冲突解释

### 12. 候选态 review 流程补齐仲裁与审计闭环
- [x] 将当前 `confirm/reject` 流程升级为更完整的候选治理流程
- 当前现状：
  记忆和画像两边都已有 review controller / service，但能力还比较薄
- 当前问题：
  缺少合并、仲裁、复核、版本演进、操作审计等能力
- 目标结果：
  候选态不只是状态切换，而是完整治理流程
- 子任务：
  - [x] 多候选比较视图
  - [x] 人工仲裁结果落库
  - [x] 审核操作审计
  - [x] 冲突关闭原因与备注
  - [x] 候选与正式态版本关系追踪

### 13. 文档与代码现状持续对齐
- [ ] 修正文档中过期结论，并持续同步当前实现边界
- 当前现状：
  [README-CHAT-FLOW.md](/Users/lejinbo/LLM/code-snippets/governance-suite/ai-chat-service/README-CHAT-FLOW.md:276) 中“Query Rewrite 仍然是规则版”已过期
- 目标结果：
  文档能准确反映当前能力和限制
- 验收标准：
  - README 中的“已实现 / 未实现”与代码一致
  - 每次核心能力升级后同步更新

---

## 推荐开发顺序

### 第一阶段：保证主链路一致性
- [x] 1. 长期记忆过滤 SQL 下推
- [x] 2. 按意图类型动态设置长期记忆过滤策略
- [x] 3. 流式链路接入真实画像拉取
- [x] 4. 流式链路写回完整 assistant 响应

### 第二阶段：提升检索质量与 Prompt 可扩展性
- [x] 5. 完善 LLM Query Rewrite 策略
- [x] 6. Prompt section pipeline 化
- [x] 7. Redis 最近记忆过滤一致性
- [ ] 8. 自动确认策略分层化
- [ ] 9. 画像正式回写范围扩展

### 第三阶段：补齐治理闭环
- [x] 10. 记忆语义冲突系统
- [x] 11. 画像语义冲突系统
- [x] 12. 候选态 review 与审计闭环
- [ ] 13. 文档持续对齐

---

## 备注

- 当前 `ai-chat-service` 的 Query Rewrite 已不是纯规则版，而是“复杂问题启用 LLM rewrite，其余走规则版，失败回退规则版”。
- 如果后续继续开发，建议每完成一项就同步更新本文件，保持它作为唯一的能力缺口跟踪入口。
