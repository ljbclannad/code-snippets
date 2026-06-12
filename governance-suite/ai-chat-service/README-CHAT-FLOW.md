# AI Chat Service 流程说明

## 概述

`ai-chat-service` 是对话编排层，负责把用户问题、会话上下文、用户画像、短期记忆、长期记忆和模型调用串成一条完整响应链路。

它本身**不负责**正式用户画像存储，也**不负责**正式长期记忆存储。它的职责主要是：

- 加载稳定画像信息
- 维护会话级上下文
- 判断是否需要检索
- 重写检索 Query
- 编排短期记忆与长期记忆
- 组装 Prompt
- 调用 DeepSeek 生成回答
- 异步触发下游持久化和记忆抽取

当前上下游依赖：

- `user-profile-service`：正式画像来源与 Redis 画像缓存
- `vector-memory-service`：长期记忆检索、候选态记忆处理
- Redis：会话上下文缓存
- MySQL：聊天历史持久化
- Elasticsearch：聊天历史搜索与推荐
- RabbitMQ：RPC 调用与异步提取触发

## 当前主流程

### 1. 会话上下文加载

入口：

- `AiChatService.handleChatRequest(...)`
- `AiChatStreamService.handleChatStreamRequest(...)`

会话上下文由 `SessionContextService` 负责。

当前行为：

- key 前缀：`ai:session:context:`
- 存储格式：Redis String(JSON)，不是 Redis Hash
- 首次消息会触发 `DeepSeekIntentAnalyzer` 做意图分析
- 后续轮次在话题漂移或时间间隔较大时会重新分析

当前会话上下文字段主要包括：

- `currentTopic`
- `userIntent`
- `conversationPhase`
- `complexity`
- `recentSummaries`
- `pendingQuestions`

这部分是当前会话的短生命周期运行态，不是长期记忆。

### 2. 用户画像加载

`ai-chat-service` 通过 RabbitMQ RPC 调用 `UserProfileClientFixed` 拉取用户画像。

正式画像的缓存和构建逻辑在 `user-profile-service` 中。

当前画像特征：

- Redis Hash 存储
- key 前缀：`ai:profile:`
- 正式数据源仍然在 `user-profile-service` 管理的业务表中

### 3. 画像诊断

在生成回答之前，`ProfileDiagnosisService` 会判断当前画像字段是否足够支撑本轮回答。

当前行为：

- 如果画像信息不足，会生成追问问题
- 追问内容会写回会话上下文
- 当前轮可能直接返回追问，而不进入完整回答链路

这一步目前是实用型字段补全门控，不是完整的画像候选态处理逻辑。画像候选态已经下沉到 `user-profile-service`。

### 4. 检索决策

记忆检索不是每轮都执行。

当前决策依据：

- `MessageValidator.shouldRetrieveMemories(...)`
- 会话 `complexity`
- 降级开关

如果判断不需要检索：

- 只使用画像和会话上下文
- 不触发长期记忆召回

### 5. Query Rewrite

如果需要长期记忆检索，会先经过 `RetrievalQueryRewriteService` 做检索 Query 重写。

当前策略：

- 规则重写
- 输入：当前消息 + 话题 + 意图 + 最近摘要 + 少量画像提示
- 输出：更适合检索的压缩 Query

这样可以避免直接拿原始口语消息去做长期记忆召回。

### 6. 分层记忆编排

这是当前版本最重要的改动之一。

`LayeredMemoryOrchestrator` 将记忆拆成两层：

#### 短期记忆

直接从 `SessionContext` 提炼，不走向量检索。

当前来源：

- 当前话题
- 用户意图
- 待确认问题
- 最近几轮摘要

特点：

- 成本低
- 强相关
- 强依赖当前 session

#### 长期记忆

通过 `MemoryService` 调用 `vector-memory-service` 加载。

当前行为：

- 检索 Query 来自 `RetrievalQueryRewriteService`
- 支持元数据过滤
- 当前已支持的过滤项包括：
  - `memoryType`
  - `minConfidence`
  - `verifiedOnly`
  - `recentDays`

当前限制：

- 元数据过滤目前是在服务层过滤，不是 SQL 下推过滤

### 7. Prompt 组装

当前 Prompt 由 `DeepSeekClient` 和 `ContextBudgetManager` 共同构建。

系统提示词目前会按预算编排这些内容：

- 核心画像
- 会话上下文
- 短期记忆区块
- 长期记忆区块
- 对话历史
- 可选扩展画像

当前已实现的关键点：

- 短期记忆和长期记忆已经分成不同的 Prompt 区块
- 不再是旧版本那种扁平的单一记忆列表

### 8. DeepSeek 回答生成

`DeepSeekClient` 会向模型发送：

- 系统提示词
- 当前用户问题

支持两种模式：

- 普通回答：`chat(...)`
- 流式回答：`chatStream(...)`

### 9. 会话上下文更新

回答完成后：

- 会话上下文会写回 Redis
- 最近摘要和运行态字段会更新

这样可以保证下一轮对话的连续性，而不需要把每一轮都写入向量记忆。

### 10. 异步持久化与提取触发

回答结束后：

- 聊天历史写入 MySQL
- 可选同步到 Elasticsearch
- 异步触发记忆提取

这个异步提取不会在 `ai-chat-service` 内直接把长期记忆落成正式态，而是交给下游服务处理。

## 长期记忆现状

长期记忆已经不再是“提取后直接写正式记忆”。

当前 `vector-memory-service` 已经支持候选态流程：

- `CANDIDATE`
- `CONFIRMED`
- `REJECTED`
- `CONFLICT`

当前下游行为：

- 抽取出的记忆先进入候选处理
- 冲突会记录到冲突表
- 重复命中的候选可自动升级为 `CONFIRMED`
- 支持人工确认和人工驳回
- `CONFIRMED` 后会真正写入：
  - 正式长期记忆表
  - Milvus
  - Redis 最近记忆索引

这套候选态机制在 `vector-memory-service` 中，不在 `ai-chat-service` 内部。

## 画像候选态现状

正式画像也不再只是“从业务表构建后缓存”。

当前 `user-profile-service` 已经支持字段级候选态：

- 字段级 `CANDIDATE`
- 字段级 `CONFLICT`
- 字段级 `REJECTED`
- 字段级 `CONFIRMED`

当前下游行为：

- 确认后的候选字段会回写正式画像数据源
- 当前已支持回写：
  - `basic.*`
  - `health.*`
  - `behavior.*`
- 确认后会刷新 Redis 画像缓存

这套画像候选态机制在 `user-profile-service` 中，不在 `ai-chat-service` 内部。

## 当前架构摘要

```text
用户消息
  -> 会话上下文加载/更新
  -> 用户画像 RPC 加载
  -> 画像诊断
  -> 是否需要检索的判断
  -> 检索 Query 重写
  -> 短期记忆直接提炼
  -> 长期记忆通过 vector-memory-service 召回
  -> 分层 Prompt 组装
  -> DeepSeek 生成回答
  -> 聊天历史异步持久化
  -> 记忆提取异步触发
```

## 当前已经实现的能力

- 会话上下文加载与更新
- 用户画像 RPC 拉取
- 画像补全追问
- 检索开关判断
- 检索 Query Rewrite
- 短期/长期记忆分层编排
- Prompt 中短期记忆与长期记忆分块
- `vector-memory-service` 长期记忆元数据过滤
- 长期记忆候选态下游处理
- 画像候选态下游处理

## 当前仍然有限的部分

- `ai-chat-service` 还没有按意图类型动态设置不同长期记忆过滤策略
- 长期记忆元数据过滤还没有做 SQL 层下推
- Query Rewrite 仍然是规则版，不是 LLM 版
- Prompt 编排虽然已增强，但核心仍集中在 `ContextBudgetManager`
- 记忆冲突与画像冲突目前还是第一版实现，不是完整语义冲突系统

## 相关代码入口

- [AiChatService.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai-chat-service/src/main/java/com/lx/governance/aichat/service/AiChatService.java:1)
- [AiChatStreamService.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai-chat-service/src/main/java/com/lx/governance/aichat/service/AiChatStreamService.java:1)
- [SessionContextService.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai-chat-service/src/main/java/com/lx/governance/aichat/service/SessionContextService.java:1)
- [RetrievalQueryRewriteService.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai-chat-service/src/main/java/com/lx/governance/aichat/service/RetrievalQueryRewriteService.java:1)
- [LayeredMemoryOrchestrator.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai-chat-service/src/main/java/com/lx/governance/aichat/service/LayeredMemoryOrchestrator.java:1)
- [ContextBudgetManager.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai-chat-service/src/main/java/com/lx/governance/aichat/context/ContextBudgetManager.java:1)
- [DeepSeekClient.java](/Users/lejinbo/LLM/code-snippets/governance-suite/ai-chat-service/src/main/java/com/lx/governance/aichat/client/DeepSeekClient.java:1)
