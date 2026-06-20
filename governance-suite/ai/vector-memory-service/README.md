# Vector Memory Service

`vector-memory-service` 是 AI 记忆系统中的长期记忆中枢，负责：

- 长期记忆提取与候选态治理
- lifecycle 写入决策、衰减、归档、压缩治理
- Redis / Milvus / MySQL 混合检索
- 结果去重与语义 rerank
- 候选态转正、冲突审核、治理回放与审计留痕

服务默认端口：`8102`

## 模块定位

在 AI 主链路中，这个服务主要承接两类请求：

1. `ai-chat-service` 在回复完成后发来的记忆提取请求
2. `ai-chat-service` 在对话生成前发来的长期记忆检索请求

对应入口见：

- 监听器：[VectorMemoryMessageListener.java](src/main/java/com/lx/governance/vectormemory/listener/VectorMemoryMessageListener.java)
- 提取服务：[MemoryExtractionService.java](src/main/java/com/lx/governance/vectormemory/service/MemoryExtractionService.java)
- 检索服务：[MemoryRetrievalService.java](src/main/java/com/lx/governance/vectormemory/service/MemoryRetrievalService.java)

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

- [DeepSeekService.java](src/main/java/com/lx/governance/vectormemory/service/DeepSeekService.java)
- [MemoryLifecycleIngestionPipeline.java](src/main/java/com/lx/governance/vectormemory/service/MemoryLifecycleIngestionPipeline.java)
- [CandidateMemoryService.java](src/main/java/com/lx/governance/vectormemory/service/CandidateMemoryService.java)

### 2. 长期记忆检索

链路：

`ai-chat-service`
-> `vector.memory.request`
-> `VectorMemoryMessageListener`
-> `MemoryRetrievalService`
-> Redis / Milvus / Keyword
-> 去重
-> rerank

当前检索策略：

- Redis 最近记忆召回
- Milvus 向量召回
- MySQL 关键词召回
- 多路结果合并去重
- 按配置执行 `full` / `partial` rerank

关键类：

- [MemoryRetrievalService.java](src/main/java/com/lx/governance/vectormemory/service/MemoryRetrievalService.java)
- [MemoryDeduplicationService.java](src/main/java/com/lx/governance/vectormemory/service/MemoryDeduplicationService.java)
- [MemoryRerankService.java](src/main/java/com/lx/governance/vectormemory/service/MemoryRerankService.java)

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

- [VectorStoreService.java](src/main/java/com/lx/governance/vectormemory/service/VectorStoreService.java)
- [MilvusVectorStoreConfig.java](src/main/java/com/lx/governance/vectormemory/config/MilvusVectorStoreConfig.java)

### Redis

负责：

- 最近记忆缓存
- 内容去重索引

相关类：

- [RedisMemoryIndexService.java](src/main/java/com/lx/governance/vectormemory/service/RedisMemoryIndexService.java)

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

- [MemoryLifecycleIngestionPipeline.java](src/main/java/com/lx/governance/vectormemory/service/MemoryLifecycleIngestionPipeline.java)
- [MemoryWorthinessEvaluator.java](src/main/java/com/lx/governance/vectormemory/service/MemoryWorthinessEvaluator.java)
- [MemoryNoiseFilter.java](src/main/java/com/lx/governance/vectormemory/service/MemoryNoiseFilter.java)
- [MemoryLifecycleDecision.java](src/main/java/com/lx/governance/vectormemory/dto/MemoryLifecycleDecision.java)

### 候选态治理

当前规则：

- 提取结果先进入候选态
- 命中数和置信度达到阈值后再考虑自动确认
- 若系统中已存在等价正式记忆，则候选直接吸收
- 若系统中已存在同语义槽位但不等价的正式记忆，则转为冲突并等待人工审核
- 候选创建、强化、冲突、转正、拒绝等状态会写入 lifecycle event

相关类：

- [CandidateMemoryService.java](src/main/java/com/lx/governance/vectormemory/service/CandidateMemoryService.java)
- [CandidateMemoryReviewService.java](src/main/java/com/lx/governance/vectormemory/service/CandidateMemoryReviewService.java)

### 类型归一化

当前已统一以下口径：

- 模型抽取分类：`preference / health / goal / fact / context`
- 中文管理/DSL 分类：如“偏好 / 健康 / 事实 / 上下文”
- 内部标准类型：`PREFERENCE / SYMPTOM / ALLERGY / MEDICATION / DIET / OTHER`

相关类：

- [MemoryTypeNormalizer.java](src/main/java/com/lx/governance/vectormemory/service/MemoryTypeNormalizer.java)
- [MemoryType.java](src/main/java/com/lx/governance/vectormemory/enums/MemoryType.java)

### Embedding 复用

为减少重复调用 embedding 模型，当前已支持：

- 去重阶段预生成候选内容 embedding
- rerank 阶段优先复用去重阶段产出的 embedding cache

### 冲突治理与合并策略

当前已补齐统一的冲突关系和 resolution 策略：

- 完全等价：吸收现有正式记忆
- 偏好冲突：按最新值更新正式记忆
- 事实共存：保留版本和来源
- 症状类：按时间窗口追加并设置失效时间
- 无法自动决策：进入人工审核

相关类：

- [MemoryConflictResolver.java](src/main/java/com/lx/governance/vectormemory/service/MemoryConflictResolver.java)
- [MemoryConflictResolution.java](src/main/java/com/lx/governance/vectormemory/service/MemoryConflictResolution.java)
- [MemoryMergeService.java](src/main/java/com/lx/governance/vectormemory/service/MemoryMergeService.java)

### Retention / Decay / Archive

正式记忆已支持生命周期维护：

- 按记忆类型应用默认 retention policy
- 扫描低价值、过期、已归档记忆
- 支持仅降权的 `DECAYING`
- 支持归档后不参与默认召回
- 删除时同步清理 MySQL / Milvus / Redis

相关类：

- [MemoryRetentionPolicy.java](src/main/java/com/lx/governance/vectormemory/service/MemoryRetentionPolicy.java)
- [MemoryDecayService.java](src/main/java/com/lx/governance/vectormemory/service/MemoryDecayService.java)
- [MemoryLifecycleMaintenanceTask.java](src/main/java/com/lx/governance/vectormemory/task/MemoryLifecycleMaintenanceTask.java)

### Summary Memory / Compaction

当前已支持把高频重复证据压缩成摘要记忆：

- 区分 `EVIDENCE` 与 `SUMMARY`
- 对高频重复候选形成 summary memory
- 检索时优先返回摘要，同时保留证据可回溯

相关类：

- [MemoryCompactionService.java](src/main/java/com/lx/governance/vectormemory/service/MemoryCompactionService.java)
- [MemoryRetrievalService.java](src/main/java/com/lx/governance/vectormemory/service/MemoryRetrievalService.java)

### 可观测性与审计

当前已补齐 lifecycle 指标和事件审计：

- 写入接受率、拒绝率、冲突率、转正率、归档率、删除率
- 候选滞留时长、冲突积压、按 memoryType 的存量分布
- 维护任务成功率
- 生命周期事件表 `b_ai_group_memory_lifecycle_event`

相关类：

- [MemoryLifecycleMetricsService.java](src/main/java/com/lx/governance/vectormemory/service/MemoryLifecycleMetricsService.java)
- [MemoryLifecycleAuditService.java](src/main/java/com/lx/governance/vectormemory/service/MemoryLifecycleAuditService.java)

## 长期记忆更新

正式长期记忆更新不再简单复用新增接口，而是走专门的维护服务：

- [ConfirmedMemoryMaintenanceService.java](src/main/java/com/lx/governance/vectormemory/service/ConfirmedMemoryMaintenanceService.java)

当前策略：

1. 先写入新向量
2. 再更新 MySQL 的 `embedding_id`
3. 最后尽力删除旧向量

这是一个基础版一致性方案，后续仍可继续补充 MySQL / Milvus 的补偿任务表或重试机制。

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
- `DELETED`（逻辑删除后通过事件体现）

记忆表现形态：

- `EVIDENCE`
- `SUMMARY`

保留策略：

- `LONG_TERM`
- `LATEST_WINS`
- `TIME_WINDOW`

## 治理接口

### 候选记忆审核接口

基础路径：`/api/candidate-memory`

- `POST /{id}/confirm`
  - 确认候选记忆，可触发转正、吸收、偏好更新或版本追加
- `POST /{id}/reject`
  - 拒绝候选记忆
- `GET /{id}/conflicts`
  - 查询候选关联冲突
- `GET /{id}/audits`
  - 查询候选审核审计
- `GET /{id}/comparison`
  - 查询候选对比视图

请求体：

```json
{
  "reviewer": "doctor-a",
  "resolutionReason": "manual_confirm",
  "remarks": "reviewed by doctor"
}
```

控制器：

- [CandidateMemoryReviewController.java](src/main/java/com/lx/governance/vectormemory/controller/CandidateMemoryReviewController.java)

### Lifecycle 治理接口

基础路径：`/api/memory-lifecycle`

- `GET /replay`
  - 按 `groupId + customerId` 回放 lifecycle 决策事件
- `GET /candidates`
  - 查询候选列表，可按 `status` 过滤
- `GET /candidates/{id}`
  - 查询候选详情，包含 conflicts / audits / lifecycle events / relatedFormalMemory
- `GET /memories`
  - 查询正式记忆列表，可按 `lifecycleStatus` 过滤
- `GET /memories/{id}/history`
  - 查询单条正式记忆生命周期历史
- `POST /memories/{id}/archive`
  - 人工归档正式记忆
- `POST /memories/{id}/restore`
  - 恢复已归档正式记忆到默认召回
- `POST /memories/{id}/delete`
  - 人工删除正式记忆，并清理向量与 Redis 索引
- `POST /memories/merge`
  - 合并两条同 scope 正式记忆，source 归档到 target

`GET /replay` 查询参数：

- `groupId`
- `customerId`
- `memoryType`：可选
- `limit`：可选，默认 `100`

治理动作请求体：

```json
{
  "reviewer": "doctor-a",
  "reasonCode": "manual_archive",
  "remarks": "no longer valid"
}
```

合并请求体：

```json
{
  "sourceMemoryId": 7,
  "targetMemoryId": 8,
  "reviewer": "doctor-a",
  "reasonCode": "manual_merge",
  "remarks": "merge duplicate memories"
}
```

控制器：

- [MemoryLifecycleGovernanceController.java](src/main/java/com/lx/governance/vectormemory/controller/MemoryLifecycleGovernanceController.java)

说明：

- 当前模块尚未接入 Swagger / SpringDoc
- 接口统一返回 `ApiResponse<T>`，成功结构为：

```json
{
  "success": true,
  "code": "OK",
  "message": "success",
  "traceId": "trace-id",
  "data": {}
}
```

## 配置重点

配置文件：

- [application.yml](src/main/resources/application.yml)
- [Memory Lifecycle TODO](TODO-MEMORY-LIFECYCLE.md)

重点配置项：

- `spring.ai.openai.*`：DeepSeek 兼容模型
- `spring.ai.dashscope.*`：Embedding 模型
- `memory.retrieval.*`：混合召回、权重、rerank、语义去重
- `memory.lifecycle.*`：候选阈值、衰减、归档、压缩、治理任务
- `milvus.*`：Milvus 连接与 collection 管理

## 测试与验证

集成测试说明见：

- [集成测试 README](src/test/java/com/lx/governance/vectormemory/integration/README.md)

模块编译验证：

```bash
mvn -pl ai/vector-memory-service -am -DskipTests compile
```

Lifecycle 相关定向验证：

```bash
mvn -pl ai/vector-memory-service -am -Dtest=MemoryLifecycleGovernanceServiceTest,CandidateMemoryServiceTest,MemoryLifecycleIngestionPipelineTest,MemoryCompactionServiceTest,MemoryDecayServiceTest,MemoryRetrievalServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```
