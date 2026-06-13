# AI 服务体系开发规范

本文档定义 AI 服务体系（ai/ 目录）的开发规范和工作规则。

## 模块范围

AI 服务体系包含以下模块：

- `ai-chat-service` - AI 对话服务
- `user-profile-service` - 用户画像服务
- `vector-memory-service` - 向量记忆服务
- `doctor-assistant-agent` - 医生助理 Agent
- `knowledge-base-service` - 知识库服务

## 硬性规则

### 模块隔离

- **必须**在修改代码前识别目标模块
- **必须**将构建、测试、验证限定在相关模块内
- **不得**因模块相邻而修改无关文件
- **不得**在 AI 服务体系内进行跨模块重构，除非用户明确要求

### 依赖管理

- **必须**验证共享依赖（`common` 模块）的修改不会破坏其他服务
- **不得**在 AI 服务的 pom.xml 中引入与分布式系统治理相关的依赖（Seata、ShardingSphere）
- **不得**修改 Spring AI 版本，除非用户明确要求

### 构建与验证

- **必须**使用模块级 Maven 命令验证更改：
  ```bash
  mvn -f ai/<module>/pom.xml compile
  mvn -f ai/<module>/pom.xml test
  ```
- **必须**在声称"验证通过"前提供实际证据（编译输出、测试结果、日志）
- **不得**在未验证的情况下声称代码可以运行

### 大模型 API 集成

- **必须**使用 Spring AI 的统一抽象（`ChatClient`、`ChatModel`）
- **必须**实现降级策略，防止 API 故障影响系统可用性
- **必须**配置合理的超时时间和重试策略
- **不得**在业务代码中硬编码 API Key

## AI 记忆系统规范

### ai-chat-service

**职责边界：**
- 对话编排和上下文管理
- 记忆检索和画像加载
- 大模型调用和降级策略
- **不负责**用户画像的存储和管理
- **不负责**向量数据的存储和检索

**关键组件：**
- `AiChatService` - 对话编排核心
- `ContextBudgetManager` - 动态上下文预算管理
- `PromptSectionBuilder` - 上下文片段构建
- `DegradationStrategy` - 降级策略

**修改指导：**
- 修改降级策略时，必须更新相关文档和指标
- 修改上下文构建逻辑时，必须考虑 Token 预算
- 添加新的记忆检索逻辑时，必须实现超时和降级

### user-profile-service

**职责边界：**
- 用户基础档案管理
- 健康标签和偏好设置
- 画像完成度评分
- **不负责**对话历史存储
- **不负责**向量记忆检索

**数据库设计：**
- `user_profile` - 基础档案
- `health_tags` - 健康标签
- `preferences` - 偏好设置

**修改指导：**
- 添加新字段时，必须提供迁移脚本
- 修改 API 时，必须保持向后兼容或提供版本控制
- 修改画像评分逻辑时，必须更新文档

### vector-memory-service

**职责边界：**
- 长期记忆的向量化存储
- 语义相似度检索
- 重排序（Rerank）优化
- **不负责**短期记忆（Redis）
- **不负责**对话历史的全文检索

**技术栈：**
- Milvus - 向量数据库
- Spring AI Embeddings - 向量化
- 自定义 Rerank 逻辑

**修改指导：**
- 修改 Milvus 连接配置时，必须测试连接可用性
- 修改向量维度时，必须重建索引
- 修改检索算法时，必须提供 A/B 测试结果

## 医生助手 Agent 规范

### doctor-assistant-agent

**职责边界：**
- 接收 ai-chat-service 触发的任务请求
- 分析患者对话历史和画像
- 生成结构化的分析结果
- **不负责**对话本身（由 ai-chat-service 负责）
- **不负责**任务的最终执行（由医生工作台负责）

**场景开发流程：**
1. 定义场景枚举（`TaskScenario`）
2. 实现场景处理器（`ScenarioHandler`）
3. 定义输出结构（JSON Schema）
4. 编写单元测试和集成测试
5. 更新 API 文档

**修改指导：**
- 添加新场景时，必须定义清晰的输入输出结构
- 修改现有场景时，必须保持输出结构向后兼容
- 调用大模型时，必须使用 Function Calling 确保输出结构化

### knowledge-base-service

**职责边界：**
- 医疗知识的存储和检索
- 知识图谱管理
- 知识推荐
- **不负责**患者个人数据

**修改指导：**
- 添加新知识类型时，必须定义清晰的数据模型
- 修改检索逻辑时，必须验证召回率和准确率
- 导入知识时，必须验证数据质量和完整性

## 配置管理

### 本地开发配置

- 使用 `application.yml` 和 `application-dev.yml`
- API Keys 通过环境变量注入，不得提交到代码库
- 数据库连接指向本地 Docker 容器

### 生产配置

- 使用 Nacos 配置中心
- 敏感信息使用加密配置
- 每个环境使用独立的命名空间

## 错误处理

### 大模型 API 错误

- **超时**：降级到下一级策略
- **限流（429）**：指数退避重试
- **鉴权失败（401）**：记录错误，返回降级响应
- **服务不可用（5xx）**：切换到备用模型或降级

### 依赖服务错误

- **user-profile-service 不可用**：使用缓存或降级到 L3
- **vector-memory-service 不可用**：跳过长期记忆，降级到 L2
- **Redis 不可用**：跳过短期记忆，仅使用系统提示词

### 数据库错误

- **连接失败**：重试 3 次，然后返回错误
- **查询超时**：记录慢查询，优化索引
- **死锁**：自动重试

## 性能优化

### 上下文构建优化

- 并行加载画像和记忆
- 使用 Redis 缓存热点数据
- 根据 Token 预算动态裁剪上下文

### 记忆检索优化

- 向量检索限制 top-k（默认 5）
- 使用 Rerank 提升精准度
- 缓存近期检索结果

### 大模型调用优化

- 流式响应提升用户体验
- 合理设置 max_tokens
- 使用更快的模型处理简单任务

## 监控与可观测性

### 必须暴露的指标

- `ai_chat_requests_total` - 对话请求总数
- `ai_chat_degradation_level` - 当前降级等级
- `ai_chat_llm_duration_seconds` - 大模型调用耗时
- `memory_retrieval_duration_seconds` - 记忆检索耗时
- `memory_retrieval_results_total` - 记忆检索结果数

### 日志规范

- **INFO**：关键业务事件（对话开始、降级触发）
- **WARN**：降级、重试、性能问题
- **ERROR**：API 失败、依赖服务不可用
- **DEBUG**：详细的调试信息（开发环境）

### 链路追踪

- 使用 Micrometer Tracing
- 每个对话分配唯一的 `sessionId`
- 跨服务调用传递 `traceId`

## 测试规范

### 单元测试

- 核心业务逻辑必须有单元测试
- Mock 外部依赖（大模型 API、依赖服务）
- 测试覆盖率 > 60%

### 集成测试

- 使用脚本测试端到端流程
- 验证降级策略是否生效
- 验证监控指标是否正确

### 性能测试

- 压测对话 QPS
- 验证降级策略下的性能表现
- 验证记忆检索的延迟

## 常见问题处理

### MyBatis-Plus 与 Spring AI 冲突

- 问题：`factoryBeanObjectType` 错误
- 原因：MyBatis-Plus 与 Spring AI 的版本不兼容
- 解决：参考 memory 文件 `feedback_doctor_assistant_mybatis_plus.md`

### 共享依赖修改

- 问题：修改 `common` 模块导致其他服务编译失败
- 原因：未隔离验证
- 解决：参考 memory 文件 `feedback_shared_dependency_modification.md`

### 大模型 API 调用失败

1. 检查 API Key 是否正确
2. 检查网络连接
3. 检查 API 限流情况
4. 查看降级策略是否生效

## 验证清单

在提交代码前，必须完成以下验证：

- [ ] 模块编译通过：`mvn -f ai/<module>/pom.xml compile`
- [ ] 单元测试通过：`mvn -f ai/<module>/pom.xml test`
- [ ] 服务能正常启动并注册到 Nacos
- [ ] API 能正常响应（通过 curl 或测试脚本）
- [ ] 监控指标正常暴露（`/actuator/prometheus`）
- [ ] 日志无 ERROR 级别错误
- [ ] 更新了相关文档（如有 API 变更）

## 文档更新

修改代码时，同步更新以下文档（如适用）：

- `ai/README.md` - 如果影响启动流程、架构说明
- API 文档（`docs/api/`）- 如果有 API 变更
- 运维手册（`docs/runbooks/`）- 如果影响部署或监控
- 技术分析（`docs/analysis/`）- 如果是性能优化或架构演进

## 参考资源

- [AI 服务体系文档](../docs/ai-services/README.md)
- [Spring AI 官方文档](https://docs.spring.io/spring-ai/reference/)
- [Milvus 官方文档](https://milvus.io/docs)
- [项目根目录 CLAUDE.md](../CLAUDE.md)
