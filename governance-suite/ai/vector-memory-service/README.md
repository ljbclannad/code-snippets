# 向量记忆服务

`vector-memory-service` 是 AI 记忆系统中的长期记忆中枢，负责：

- 长期记忆提取与候选态治理
- lifecycle 写入决策、衰减、归档、压缩治理
- Redis / Milvus / MySQL 三路召回
- 结果去重与语义 rerank
- 候选态转正、冲突审核、治理回放与审计留痕

服务默认端口：`8102`

## 模块定位

在 AI 主链路中，这个服务主要承接两类请求：

1. `ai-chat-service` 在回复完成后发来的记忆提取请求
2. `ai-chat-service` 在对话生成前发来的长期记忆检索请求

对应入口见：

- 监听器：[VectorMemoryMessageListener.java](src/main/java/com/lx/governance/vectormemory/listener/VectorMemoryMessageListener.java)
- 提取应用服务：[MemoryExtractionService.java](src/main/java/com/lx/governance/vectormemory/application/ingestion/MemoryExtractionService.java)
- 检索应用服务：[MemoryRetrievalService.java](src/main/java/com/lx/governance/vectormemory/application/retrieval/MemoryRetrievalService.java)

## 当前分层

本模块已从“单个 `service` 包堆叠全部能力”调整为按职责分层：

```text
com.lx.governance.vectormemory
├── application
│   ├── governance   # 审核、治理、死信等应用编排入口
│   ├── ingestion    # 记忆提取应用入口
│   └── retrieval    # 长期记忆检索应用入口
├── domain
│   ├── candidate    # 候选态、冲突、合并、转正规则
│   ├── lifecycle    # retention / decay / compaction / ingestion policy
│   └── retrieval    # 检索过滤、去重、rerank 等领域策略
├── integration
│   ├── ai           # DeepSeek / embedding / BGE rerank 适配
│   └── storage      # Redis / Milvus 等存储适配
├── support
│   └── observability # 审计与指标
├── controller
├── listener
├── mapper
├── dto
├── entity
└── task
```

分层约束：

- `application` 负责流程编排，不承载底层存储细节
- `domain` 放业务规则、策略和领域对象
- `integration` 负责外部依赖适配
- `support` 放审计、指标等横切能力

## 核心流程

### 1. 记忆提取

链路：

`ai-chat-service`
-> `memory.extraction.queue`
-> `VectorMemoryMessageListener`
-> `MemoryExtractionService`
-> `DeepSeekService`
-> `MemoryLifecycleIngestionPipeline`
-> `CandidateMemoryService`

说明：

- 本服务按“单轮用户消息 + 单轮 AI 回复”提取长期记忆
- 模型输出不会直接进入正式长期记忆
- 结果会先经过 lifecycle 写入决策，再进入候选态
- 候选态经过命中累计、冲突检测和审核后再转正
- 正式记忆支持后续 decay / archive / delete / compaction 治理

关键类：

- [MemoryExtractionService.java](src/main/java/com/lx/governance/vectormemory/application/ingestion/MemoryExtractionService.java)
- [DeepSeekService.java](src/main/java/com/lx/governance/vectormemory/integration/ai/DeepSeekService.java)
- [MemoryLifecycleIngestionPipeline.java](src/main/java/com/lx/governance/vectormemory/domain/lifecycle/MemoryLifecycleIngestionPipeline.java)
- [CandidateMemoryService.java](src/main/java/com/lx/governance/vectormemory/domain/candidate/CandidateMemoryService.java)

### 2. 长期记忆检索

链路：

`ai-chat-service`
-> `vector.memory.request`
-> `VectorMemoryMessageListener`
-> `MemoryRetrievalService`
-> Redis / Milvus / MySQL Keyword
-> 去重
-> rerank

当前检索策略：

- Redis 最近记忆召回
- Milvus 向量召回
- MySQL 关键词召回
- 多路结果合并去重
- 按配置执行 `full` / `partial` rerank

关键类：

- [MemoryRetrievalService.java](src/main/java/com/lx/governance/vectormemory/application/retrieval/MemoryRetrievalService.java)
- [MemoryDeduplicationService.java](src/main/java/com/lx/governance/vectormemory/domain/retrieval/MemoryDeduplicationService.java)
- [MemoryRerankService.java](src/main/java/com/lx/governance/vectormemory/domain/retrieval/MemoryRerankService.java)
- [StructuredMemoryQueryCompiler.java](src/main/java/com/lx/governance/vectormemory/domain/retrieval/StructuredMemoryQueryCompiler.java)

## 数据分层

### MySQL

负责保存：

- 正式长期记忆
- 候选记忆
- 冲突记录
- 审核审计记录
- lifecycle 事件记录

相关 Mapper：

- [AiGroupMemoryMapper.java](src/main/java/com/lx/governance/vectormemory/mapper/AiGroupMemoryMapper.java)
- [AiGroupMemoryCandidateMapper.java](src/main/java/com/lx/governance/vectormemory/mapper/AiGroupMemoryCandidateMapper.java)
- [AiGroupMemoryConflictMapper.java](src/main/java/com/lx/governance/vectormemory/mapper/AiGroupMemoryConflictMapper.java)
- [AiGroupMemoryLifecycleEventMapper.java](src/main/java/com/lx/governance/vectormemory/mapper/AiGroupMemoryLifecycleEventMapper.java)

### Milvus

负责保存正式长期记忆的向量，用于语义检索。

相关类：

- [VectorStoreService.java](src/main/java/com/lx/governance/vectormemory/integration/storage/VectorStoreService.java)
- [MilvusVectorStoreConfig.java](src/main/java/com/lx/governance/vectormemory/config/MilvusVectorStoreConfig.java)

### Redis

负责：

- 最近记忆缓存
- 内容去重索引

相关类：

- [RedisMemoryIndexService.java](src/main/java/com/lx/governance/vectormemory/integration/storage/RedisMemoryIndexService.java)

## 当前实现重点

### Lifecycle 写入管道

当前写入治理已经从“直接进候选表”演进为显式 pipeline：

- 抽取结果类型归一化
- 噪声过滤
- worthiness 评估
- Redis 快速去重
- 输出统一 `MemoryLifecycleDecision`
- 对每次决策记录 reason code 和 lifecycle event

相关类：

- [MemoryLifecycleIngestionPipeline.java](src/main/java/com/lx/governance/vectormemory/domain/lifecycle/MemoryLifecycleIngestionPipeline.java)
- [MemoryWorthinessEvaluator.java](src/main/java/com/lx/governance/vectormemory/domain/lifecycle/MemoryWorthinessEvaluator.java)
- [MemoryNoiseFilter.java](src/main/java/com/lx/governance/vectormemory/domain/lifecycle/MemoryNoiseFilter.java)
- [MemoryLifecycleDecision.java](src/main/java/com/lx/governance/vectormemory/dto/MemoryLifecycleDecision.java)

### 候选态治理

候选创建与后续自动确认分为两个阶段：

1. 首次创建候选时，与活跃候选及正式记忆检测语义冲突；发现冲突则登记为 `CONFLICT` 并记录冲突对象，否则登记为 `CANDIDATE`。
2. 再次命中已有候选时，先强化命中数、置信度和来源信息；本次累积命中数大于 `5` 且本次抽取置信度不低于 `0.8`，才进入自动确认判断。该阶段重新匹配正式记忆；没有匹配项时转正，有匹配项时按下表处理。

| 与正式记忆的关系及类型 | 自动确认阶段的处理 |
| --- | --- |
| 完全等价或近似等价 | 吸收到已有正式记忆，关联该记录 |
| 冲突的 `PREFERENCE`、`DIET` | 以新偏好更新已有正式记忆 |
| 冲突的 `ALLERGY`、`MEDICATION`、`OTHER` | 追加版本，保留历史记录 |
| 冲突的 `SYMPTOM` | 按时间窗口追加症状记忆 |
| 其他需人工判断的冲突 | 转入 `CONFLICT`，等待人工审核 |
| 可共存的非等价记忆 | 追加版本 |

首次登记为 `CONFLICT` 不代表后续只能人工处理；重复命中达到上述条件后，仍会进入按类型决策的自动确认流程。候选创建、强化、冲突、转正、拒绝等操作保留相应生命周期事件或审核记录。

相关类：

- [CandidateMemoryService.java](src/main/java/com/lx/governance/vectormemory/domain/candidate/CandidateMemoryService.java)
- [CandidateMemoryReviewService.java](src/main/java/com/lx/governance/vectormemory/application/governance/CandidateMemoryReviewService.java)
- [MemoryConflictResolver.java](src/main/java/com/lx/governance/vectormemory/domain/candidate/MemoryConflictResolver.java)
- [MemoryConflictResolution.java](src/main/java/com/lx/governance/vectormemory/domain/candidate/MemoryConflictResolution.java)

### 类型归一化

当前已统一以下口径：

- 模型抽取分类：`preference / health / goal / fact / context`
- 中文管理/DSL 分类：如“偏好 / 健康 / 事实 / 上下文”
- 内部标准类型：`PREFERENCE / SYMPTOM / ALLERGY / MEDICATION / DIET / OTHER`

相关类：

- [MemoryTypeNormalizer.java](src/main/java/com/lx/governance/vectormemory/domain/lifecycle/MemoryTypeNormalizer.java)
- [MemoryType.java](src/main/java/com/lx/governance/vectormemory/enums/MemoryType.java)

### Embedding 复用

为减少重复调用 embedding 模型，当前已支持：

- 去重阶段预生成候选内容 embedding
- rerank 阶段优先复用去重阶段产出的 embedding cache

相关类：

- [EmbeddingService.java](src/main/java/com/lx/governance/vectormemory/integration/ai/EmbeddingService.java)
- [MemoryDeduplicationService.java](src/main/java/com/lx/governance/vectormemory/domain/retrieval/MemoryDeduplicationService.java)

### Retention / Decay / Archive / Compaction

正式记忆已支持生命周期维护：

- 按记忆类型应用默认 retention policy
- 扫描低价值、过期、已归档记忆
- 支持仅降权的 `DECAYING`
- 支持归档后不参与默认召回
- 支持高频证据压缩成摘要记忆
- 删除时同步清理 MySQL / Milvus / Redis

相关类：

- [MemoryRetentionPolicy.java](src/main/java/com/lx/governance/vectormemory/domain/lifecycle/MemoryRetentionPolicy.java)
- [MemoryDecayService.java](src/main/java/com/lx/governance/vectormemory/domain/lifecycle/MemoryDecayService.java)
- [MemoryCompactionService.java](src/main/java/com/lx/governance/vectormemory/domain/lifecycle/MemoryCompactionService.java)
- [ConfirmedMemoryMaintenanceService.java](src/main/java/com/lx/governance/vectormemory/domain/lifecycle/ConfirmedMemoryMaintenanceService.java)
- [MemoryLifecycleMaintenanceTask.java](src/main/java/com/lx/governance/vectormemory/task/MemoryLifecycleMaintenanceTask.java)

### 可观测性与审计

当前已补齐 lifecycle 指标和事件审计：

- 写入接受率、拒绝率、冲突率、转正率、归档率、删除率
- 候选滞留时长、冲突积压、按 memoryType 的存量分布
- 维护任务成功率
- 生命周期事件表 `b_ai_group_memory_lifecycle_event`

相关类：

- [MemoryLifecycleMetricsService.java](src/main/java/com/lx/governance/vectormemory/support/observability/MemoryLifecycleMetricsService.java)
- [MemoryLifecycleAuditService.java](src/main/java/com/lx/governance/vectormemory/support/observability/MemoryLifecycleAuditService.java)

## Lifecycle 状态模型

候选态：

- `CANDIDATE`
- `CONFLICT`
- `CONFIRMED`
- `REJECTED`

正式记忆态：

- `CONFIRMED`
- `DECAYING`
- `ARCHIVED`

分类、状态与数据库迁移边界见[生命周期说明](docs/向量记忆分类与生命周期.md)。
