# Vector Memory Service Memory Lifecycle TODO

本文档用于规划 `vector-memory-service` 在当前基础上，如何逐步从“长期记忆提取 + 检索服务”演进为“带 lifecycle 治理能力的记忆管理服务”。

当前判断：

- 现有服务已经具备长期记忆提取、候选态治理、冲突审核、混合检索、rerank 和基础审计能力
- 现阶段**不建议**立即拆出独立 `memory-lifecycle-service`
- 更合适的路线是先在 `vector-memory-service` 内部完成写入治理、生命周期管理和记忆质量控制，再根据复用范围决定是否物理拆分

---

## 1. 目标定义

当前 `vector-memory-service` 主要解决：

- 记忆怎么提取
- 记忆怎么存储
- 记忆怎么检索

`memory lifecycle` 需要进一步解决：

- 什么内容值得写入长期记忆
- 写入前是否需要去重、合并、打标签、降噪
- 记忆是事实、偏好、事件、上下文还是临时状态
- 记忆何时转正、何时衰减、何时归档、何时删除
- 新旧记忆冲突时如何决策
- 哪些记忆应保留原文，哪些记忆应压缩总结
- 记忆质量如何度量和追踪

一句话目标：

`vector-memory-service` 从“记忆存取服务”升级为“记忆治理服务”。

---

## 2. 演进原则

### 原则 1：先逻辑分层，再物理拆分

第一阶段只在当前服务内增加 lifecycle pipeline，不新增独立服务。

### 原则 2：读写路径解耦

- `read path` 继续聚焦检索性能和召回质量
- `write path` 单独承担候选入库、去重合并、冲突判断、衰减归档等治理逻辑

### 原则 3：优先补“治理决策”，不是优先补“表和接口”

优先明确：

- 记忆类型
- 写入规则
- 冲突规则
- 保留规则
- 失效规则

再决定存储结构和调度方式。

### 原则 4：兼容当前链路

- 不破坏 `ai-chat-service -> memory.extraction.queue -> vector-memory-service` 主链路
- 不破坏当前 `vector.memory.request` 检索链路
- 生命周期能力默认以“增强写入路径”的方式接入

---

## 3. 当前能力与 lifecycle 的差距

### 已具备

- 提取后先进入候选态
- 候选记忆累计命中后再转正
- 与正式记忆做等价吸收和冲突判断
- 正式记忆写 MySQL + Milvus
- 检索支持混合召回、去重、rerank

### 尚未系统化

- 明确的记忆写入策略中心
- 临时记忆 / 稳定记忆 / 高价值事实记忆分层
- 记忆过期、衰减、归档、删除机制
- 同槽位新旧记忆覆盖规则
- 长文本记忆压缩和 summary memory
- 记忆质量指标和治理任务面板

---

## 4. 目标分层

建议把内部能力划成两条主线：

### A. Read Path

负责：

- 混合召回
- query filter
- semantic search
- dedup
- rerank
- retrieval audit

当前主要落点：

- `MemoryRetrievalService`
- `MemoryDeduplicationService`
- `MemoryRerankService`

### B. Write Path / Lifecycle Path

负责：

- extraction output normalization
- candidate ingestion
- memory worthiness scoring
- dedup / merge / conflict decision
- confirmation / promotion
- retention / decay / archive / delete
- memory compaction / summarization

建议新增的内部能力以这条路径组织。

---

## 5. TODO 总览

### Phase 0. 先补文档和模型口径

- [x] 补一份统一的 memory taxonomy 文档
- [x] 定义 `事实 / 偏好 / 症状 / 目标 / 事件 / 临时上下文` 的边界
- [x] 定义“长期记忆值得写入”的判定标准
- [x] 定义“候选态 -> 转正 -> 衰减 -> 归档 -> 删除”的生命周期状态图
- [x] 梳理当前数据库表与 lifecycle 状态字段是否足够

### Phase 1. 在当前服务内建立 lifecycle 写入管道

- [x] 为提取结果建立统一的 `MemoryLifecycleIngestionPipeline`
- [x] 将当前 `MemoryExtractionService -> CandidateMemoryService` 之间的隐式规则显式化
- [x] 增加 `MemoryWorthinessEvaluator`
- [x] 增加 `MemoryNoiseFilter`
- [x] 增加 `MemoryLifecycleDecision` 统一输出模型
- [x] 让写入链路对每次决策记录 reason code

### Phase 2. 建立记忆去重、合并与冲突治理层

- [x] 增加 `MemoryMergeService`
- [x] 增加 `MemoryConflictResolver`
- [x] 定义同一语义槽位的新旧记忆覆盖规则
- [x] 定义“完全等价 / 近似等价 / 冲突 / 可并存”四类关系
- [x] 支持偏好类记忆直接更新最新值
- [x] 支持事实类记忆保留版本和来源
- [x] 支持症状类记忆带时间窗口失效

### Phase 3. 建立 retention / decay / archive 机制

- [x] 为不同记忆类型定义默认 TTL 或保留策略
- [x] 增加 `MemoryRetentionPolicy`
- [x] 增加 `MemoryDecayService`
- [x] 增加定时任务扫描低价值或过期记忆
- [x] 支持“仅降低检索权重，不立即删除”
- [x] 支持“归档后不参与默认召回，但可审计回看”
- [x] 支持删除时同步处理 MySQL / Milvus / Redis 索引

### Phase 4. 建立记忆压缩与 summary memory

- [x] 增加 `MemoryCompactionService`
- [x] 对高频重复候选记忆进行合并摘要
- [x] 对长周期事件形成 summary memory
- [x] 区分“原始证据记忆”和“摘要记忆”
- [x] 检索时支持摘要优先、证据回溯

### Phase 5. 建立 lifecycle 可观测性

- [x] 增加 lifecycle 指标：写入接受率、拒绝率、冲突率、转正率、归档率、删除率
- [x] 增加按记忆类型统计的存量分布
- [x] 增加候选态滞留时长监控
- [x] 增加冲突审核积压监控
- [x] 增加 decay / archive job 成功率监控
- [x] 为每次 lifecycle 决策补充审计事件

### Phase 6. 建立回放与治理接口

- [x] 增加 memory lifecycle 决策回放视图
- [x] 增加按 memoryId 查询生命周期历史接口
- [x] 增加人工干预接口：确认、合并、归档、删除、恢复
- [x] 增加冲突处理结果回写链路
- [x] 增加治理后台需要的列表查询接口


---

## 6. 建议新增的核心类

第一批建议先补这些类，尽量保持现有命名风格：

- `MemoryLifecycleIngestionPipeline`
- `MemoryWorthinessEvaluator`
- `MemoryNoiseFilter`
- `MemoryLifecycleDecision`
- `MemoryMergeService`
- `MemoryConflictResolver`
- `MemoryRetentionPolicy`
- `MemoryDecayService`
- `MemoryCompactionService`
- `MemoryLifecycleAuditService`

---

## 7. 建议新增的状态与字段

可考虑在候选态和正式记忆模型中逐步补充：

- `lifecycle_status`
- `retention_policy`
- `expires_at`
- `decay_score`
- `last_accessed_at`
- `last_reinforced_at`
- `source_count`
- `evidence_count`
- `conflict_status`
- `superseded_by`
- `archived_at`
- `archive_reason`

注意：

- 第一阶段不要求一次性加完
- 优先补最能支撑治理决策和审计追踪的字段

---

## 8. 推荐的最小可落地版本

如果只做一个最小闭环，建议按下面顺序推进：

1. 增加 `MemoryWorthinessEvaluator`
2. 增加 `MemoryLifecycleDecision`
3. 把当前候选入库前后的规则统一收口
4. 增加 `MemoryRetentionPolicy`
5. 为正式记忆补 `expires_at / decay_score`
6. 增加定时 decay job
7. 增加 lifecycle metrics 和审计日志

做到这一步，就已经不是“只有向量检索”的服务了，而是具备基础 memory lifecycle 能力的记忆治理服务。

---

## 9. 不建议当前阶段做的事

- [ ] 不要一开始就拆独立微服务
- [ ] 不要先做很重的通用规则引擎
- [ ] 不要先做复杂的前端治理后台
- [ ] 不要先引入过多异步链路，导致调试困难
- [ ] 不要在 lifecycle 规则未明确前大改现有表结构

---

## 10. 物理拆分时机

只有在以下条件明显出现后，再考虑独立服务：

- 多个 agent / 业务线共用一套记忆治理规则
- lifecycle 定时任务和检索流量争抢资源
- 记忆治理规则需要独立发布和灰度
- 需要独立扩容写入治理与检索能力

拆分后的推荐边界：

- `vector-memory-service`
  - 保留 retrieval、vector index、rerank、query compiler
- `memory-lifecycle-service`
  - 承担 ingestion、merge、retention、decay、archive、audit

---

## 11. 近期优先级建议

P0：

- [ ] 补 memory taxonomy
- [ ] 补 `MemoryWorthinessEvaluator`
- [ ] 补 `MemoryLifecycleDecision`
- [ ] 补 lifecycle 审计 reason code

P1：

- [ ] 补 `MemoryRetentionPolicy`
- [ ] 补 decay / archive job
- [ ] 补 merge / conflict 细粒度规则

P2：

- [ ] 补 compaction / summary memory
- [ ] 补人工治理接口
- [ ] 评估物理拆分

---

## 12. 完成标志

当以下条件满足时，可以认为 `vector-memory-service` 已完成第一阶段 lifecycle 演进：

- 写入不再是“提取结果直接入候选”
- 每条记忆都有明确的写入决策理由
- 正式记忆具备基本保留/失效/衰减机制
- 冲突、覆盖、合并规则可以稳定解释
- 检索链路能感知记忆状态而不是无差别召回
- 审计和指标可以回答“这条记忆为什么存在、为什么消失、为什么被替换”

这时再决定是否拆服务，会比现在稳得多。
