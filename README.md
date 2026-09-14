# Code Snippets

面向 Java、Spring、分布式系统与 LLM 集成的学习和实验仓库。根目录下的项目彼此独立：它们没有统一的构建入口，也不应假定共享运行配置或依赖。

> **当前重点：**`governance-suite` 是仓库中最完整的多模块项目；其余目录主要是围绕特定技术主题的可运行示例或原理实现。

## 项目导航

| 目录 | 定位 | 技术／主题 | 运行或构建入口 |
| --- | --- | --- | --- |
| [`governance-suite`](./governance-suite/) | 微服务治理与医疗 AI Agent 平台 | Spring Boot、Spring Cloud、Seata、Nacos、Sentinel、RabbitMQ、Redis、Milvus、Spring AI | 根目录 Maven 多模块工程；需要 Docker Compose 基础设施 |
| [`spring-ai`](./spring-ai/) | Spring AI 能力演示 | Chat、Prompt、结构化输出、Tool Calling、对话记忆、MCP | [`spring-ai/demo`](./spring-ai/demo/) Maven 项目 |
| [`Snowflake-demo`](./Snowflake-demo/) | 分布式 ID 生成策略演示 | Snowflake、号段、灰度切换、MyBatis、指标、健康检查 | Maven + Docker Compose |
| [`dify-api-test-java`](./dify-api-test-java/) | Dify API 最小 Java 客户端 | Dify Workflow、Dify Agent、HTTP 调用、链路追踪 | Maven；需配置 Dify API Key |
| [`DistributedRateLimitingAlgorithm`](./DistributedRateLimitingAlgorithm/) | 经典限流算法实现 | 固定窗口、滑动窗口、令牌桶、漏桶 | 直接使用 `javac`／`java` 运行示例 |
| [`redis`](./redis/) | Redis 原理与缓存模式实现 | Mini Redis Server、跳表 ZSet、逻辑过期、支付回调幂等 | 直接使用 `javac`／`java` 运行示例 |
| [`spring-simulation-java`](./spring-simulation-java/) | 手写模拟 Spring 核心机制 | IoC、AOP、Bean 生命周期、循环依赖、事务、事件、SpEL | 直接编译并运行 `examples` 中的演示入口 |
| [`medical_docs`](./medical_docs/) | 医疗领域原始资料目录 | 文档／数据 | 非独立可运行项目 |

## 核心项目：`governance-suite`

[`governance-suite`](./governance-suite/) 是一个面向学习和架构实验的 Maven 多模块仓库，包含共享基础设施的两套体系：

### 分布式系统治理

- API 网关、用户、订单、支付、库存、预约与 Outbox 消费服务；
- Seata AT 分布式事务；
- Nacos 服务注册与配置；
- Sentinel 限流与熔断；
- RabbitMQ + Outbox 可靠异步消息；
- Redis 缓存、分布式锁和预约抢购场景；
- ShardingSphere 读写分离。

### 医疗 AI 与 Agent 平台

- `ai-chat-service`：对话入口、会话上下文装载和普通对话回退；
- `user-profile-service`：用户画像；
- `vector-memory-service`：长期向量记忆检索；
- `doctor-assistant-agent`：医生助手 Agent、结构化分析与人工复核承接；
- `knowledge-base-service`：医疗知识库管理和语义检索；
- `agent-platform-common`：多 Agent 的路由、审批、工具、上下文、审计和运行记录公共协议。

详细说明、模块端口和启动方式请参阅 [`governance-suite/README.md`](./governance-suite/README.md)。

## 按主题选择项目

### 想学习分布式系统

- 完整微服务治理链路：[`governance-suite`](./governance-suite/)
- 分布式 ID 生成与降级：[`Snowflake-demo`](./Snowflake-demo/)
- 限流算法原理：[`DistributedRateLimitingAlgorithm`](./DistributedRateLimitingAlgorithm/)
- Redis 数据结构、缓存和幂等：[`redis`](./redis/)

### 想学习 Java／Spring 原理

- IoC、AOP、事务、事件与 SpEL：[`spring-simulation-java`](./spring-simulation-java/)
- Spring AI 应用开发：[`spring-ai`](./spring-ai/)

### 想学习 LLM 与 Agent 集成

- Spring AI、Tool Calling、MCP 与对话记忆：[`spring-ai`](./spring-ai/)
- Dify Workflow／Agent 的 Java 接入：[`dify-api-test-java`](./dify-api-test-java/)
- 医疗对话、记忆、知识库和多 Agent 协同：[`governance-suite/ai`](./governance-suite/ai/)

## 环境要求

各项目独立维护环境要求，进入对应目录后优先阅读其 README。常见依赖如下：

- **JDK 17 + Maven 3.8+**：`governance-suite`、`Snowflake-demo`、`spring-ai`；
- **JDK 11 + Maven 3.6+**：`dify-api-test-java`；
- **Docker / Docker Compose**：需要 MySQL、Redis、消息队列或监控组件的服务型项目；
- **模型服务 API Key**：部分 AI 示例需要配置，例如 `DEEPSEEK_API_KEY` 或 `DIFY_API_KEY`。

## 目录约定

- 每个一级项目应维护自己的 README、构建说明和运行前置条件；
- `medical_docs` 存放资料，不应当作应用模块构建或启动；
- `.venv` 是本地 Python 虚拟环境，不属于项目源码；
- 编译产物如 `target/`、`node_modules/` 和运行日志不代表项目源码结构。

## 维护建议

新增示例时，请将其放入与主题相符的独立目录，并至少补充：

1. 项目用途与适用范围；
2. 所需运行环境和外部依赖；
3. 最小构建或运行命令；
4. 关键示例入口；
5. 与现有项目的关联（若存在）。
