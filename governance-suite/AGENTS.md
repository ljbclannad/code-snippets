# governance-suite 协作指南

## 项目定位

这是一个以 Spring Boot 3.2.5 和 Java 17 构建的 Maven 多模块学习/实验仓库，包含两套相互独立的服务体系，并共享本地 Docker 基础设施：

- `distributed/`：微服务治理实验，覆盖 Nacos、Seata（AT/TCC）、Sentinel、RabbitMQ Outbox、ShardingSphere、Redis Lua 挂号。
- `ai/`：医疗对话与 Agent 实验，覆盖画像、分层记忆、Milvus 检索、知识库、任务型 Agent 和人工复核闭环。
- `common/`：跨服务公共组件；修改会影响多个模块。
- `ai/agent-platform-common/`：多 Agent 平台协议与运行时公共层；包含 run、workflow、routing、selector、approval、tool、context、audit 等能力。

根 `pom.xml` 是唯一 Maven reactor，列出了全部可构建模块。服务均为独立 Spring Boot 应用，启动入口位于各模块的 `*Application.java`。

## 先定位，再改动

1. 先确定目标属于 `distributed/`、`ai/`、`common/` 或 `agent-platform-common`，只阅读和修改完成任务所需的最小范围。
2. 修改 `distributed/` 前，阅读 [`distributed/AGENTS.md`](distributed/AGENTS.md)：其中定义了服务职责、Seata/Outbox 约束和模块级验证要求。
3. 修改 `ai/` 前，阅读 [`ai/AGENTS.md`](ai/AGENTS.md)：其中定义了模型调用、降级、Token 预算、记忆检索与 Agent 场景约束。
4. 修改 `common/` 或 `ai/agent-platform-common/` 前，列出实际依赖它的目标服务，并为受影响消费者安排隔离编译/测试验证。
5. API、启动流程、可观测性或数据库结构发生外部可见变化时，同步更新相应说明。

## 体系边界

### 分布式治理

- `gateway`：统一入口、认证、限流与路由；不承载业务数据和业务逻辑。
- `order-service`：订单与 Seata 事务发起方（TM）。
- `payment-service`、`inventory-service`：Seata 事务参与方（RM）。
- `outbox-consumer`：轮询 Outbox 并投递 RabbitMQ，不直接执行业务编排。
- `appointment-service`：Redis Lua 原子库存扣减与预约；不使用分布式事务。
- 涉及 Seata 时，TM 使用 `@GlobalTransactional`，RM 的本地事务使用带 `rollbackFor` 的 `@Transactional`；外部不可回滚调用应在全局事务提交后异步处理。
- 修改订单、支付、库存或 Outbox 行为时，覆盖正常路径和失败/回滚/重复投递场景。

### AI 服务

- `ai-chat-service`：患者入口、会话上下文、画像与记忆编排、普通对话和升级路由；不管理画像或向量存储。
- `user-profile-service`：用户画像、健康标签、偏好。
- `vector-memory-service`：长期记忆向量化、检索、重排序和生命周期治理；短期记忆属于 Redis 会话链路。
- `doctor-assistant-agent`：处理已升级的任务请求，输出 `patientSafeReply`、`structuredResult`、`doctorWorkbenchPayload`；不直接承担患者会话主链路或最终医疗执行。
- `knowledge-base-service`：医疗知识管理与语义检索，不保存患者个人数据。
- 模型访问沿用 Spring AI 抽象，并保留超时、重试与降级。密钥仅通过环境变量提供，严禁写入源码或配置。
- 医疗相关改动优先保持患者侧安全回复与医生侧结构化结果的边界，并对输出结构兼容性增加测试。

## 构建与测试

从仓库根目录执行命令。默认先运行与改动模块匹配的最小验证，只有共享改动、依赖关系或结果要求时才扩大范围。

```bash
# 编译和测试一个模块
mvn -f distributed/order-service/pom.xml compile
mvn -f distributed/order-service/pom.xml test
mvn -f ai/ai-chat-service/pom.xml compile
mvn -f ai/ai-chat-service/pom.xml test

# Maven reactor 全量构建（仅在确有必要时）
mvn clean package -DskipTests

# 端到端验证脚本（需对应服务和基础设施已运行）
bash sh/test/test-create-order.sh
bash sh/test/test-03-ai-chat.sh
```

- 每个服务在 `src/test/java` 下维护测试；优先新增或调整最接近改动的单元测试。
- 集成/端到端测试可能依赖 MySQL、Redis、RabbitMQ、Nacos、Milvus 或外部模型配置；未满足前置条件时，明确记录未执行的原因，而不是把它当作通过。
- 本仓库未配置统一的代码格式化或静态检查工具，遵循目标模块现有代码风格与命名。

## 本地运行

```bash
# 默认基础设施
docker compose up -d

# 启动单个服务
mvn -q -pl distributed/order-service spring-boot:run
mvn -q -pl ai/ai-chat-service spring-boot:run

# 常用服务组
bash sh/run/start-all-services.sh
bash sh/run/start-ai-services.sh
```

`docker-compose.yml` 默认提供 MySQL、Redis、RabbitMQ、Nacos、Milvus、Elasticsearch 等；Seata、Sentinel、可观测性、读副本、Attu 和 rerank 服务由 Compose profiles 控制。AI 主链路启动前通常需要 `DASHSCOPE_API_KEY`、`DEEPSEEK_API_KEY` 和/或 `DIFY_API_KEY`，以目标模块的 `application.yml` 为准。

## 数据与运行产物

- 数据库初始化脚本位于 `sql/`；变更持久化模型时，提供可审阅的迁移脚本，并保留分布式事务参与库的 `undo_log` 约束。
- `logs/`、`target/`、本地环境文件和 IDE 文件均为运行产物。根 `.gitignore` 也忽略 `docs/`；新增或修改文档后，先确认该忽略规则是否符合当前交付意图。
- 不将 API Key、Token、真实患者数据或本地运行数据提交到仓库。

## 提交前检查

- 确认改动没有越过目标模块边界。
- 运行最小相关 `compile` 或 `test`，并报告实际结果。
- 涉及共享模块时，验证受影响消费者；涉及 Seata、Outbox、记忆检索、模型降级或 Agent 路由时，覆盖对应异常路径。
- 确认配置、SQL、API 与文档已在需要时同步更新。
