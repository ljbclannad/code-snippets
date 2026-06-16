# Vector Memory Service

`vector-memory-service` 是 AI 记忆系统中的长期记忆中枢，负责：

- 长期记忆提取与候选态治理
- Redis / Milvus / MySQL 混合检索
- 结果去重与语义 rerank
- 候选态转正、冲突审核与审计留痕

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
-> `CandidateMemoryService`

说明：

- 本服务按“单轮用户消息 + 单轮 AI 回复”提取长期记忆
- 模型输出不会直接进入正式长期记忆
- 结果会先进入候选态，经过命中累计、冲突检测和审核后再转正

关键类：

- [DeepSeekService.java](src/main/java/com/lx/governance/vectormemory/service/DeepSeekService.java)
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

相关 Mapper：

- [AiGroupMemoryMapper.java](src/main/java/com/lx/governance/vectormemory/mapper/AiGroupMemoryMapper.java)
- [AiGroupMemoryCandidateMapper.java](src/main/java/com/lx/governance/vectormemory/mapper/AiGroupMemoryCandidateMapper.java)
- [AiGroupMemoryConflictMapper.java](src/main/java/com/lx/governance/vectormemory/mapper/AiGroupMemoryConflictMapper.java)

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

### 候选态治理

当前规则：

- 提取结果先进入候选态
- 命中数和置信度达到阈值后再考虑自动确认
- 若系统中已存在等价正式记忆，则候选直接吸收
- 若系统中已存在同语义槽位但不等价的正式记忆，则转为冲突并等待人工审核

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

## 长期记忆更新

正式长期记忆更新不再简单复用新增接口，而是走专门的维护服务：

- [ConfirmedMemoryMaintenanceService.java](src/main/java/com/lx/governance/vectormemory/service/ConfirmedMemoryMaintenanceService.java)

当前策略：

1. 先写入新向量
2. 再更新 MySQL 的 `embedding_id`
3. 最后尽力删除旧向量

这是一个基础版一致性方案，后续仍可继续补充 MySQL / Milvus 的补偿任务表或重试机制。

## 配置重点

配置文件：

- [application.yml](src/main/resources/application.yml)

重点配置项：

- `spring.ai.openai.*`：DeepSeek 兼容模型
- `spring.ai.dashscope.*`：Embedding 模型
- `memory.retrieval.*`：混合召回、权重、rerank、语义去重
- `milvus.*`：Milvus 连接与 collection 管理

## 测试与验证

集成测试说明见：

- [集成测试 README](src/test/java/com/lx/governance/vectormemory/integration/README.md)

模块编译验证：

```bash
mvn -pl ai/vector-memory-service -am -DskipTests compile
```
