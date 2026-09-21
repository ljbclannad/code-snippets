# agent-platform-common

`agent-platform-common` 是 AI 服务体系的 **Agent 平台公共层**。它以 JAR 的形式提供跨服务共用的协议、运行时规则和扩展接口，当前由 `ai-chat-service` 与 `doctor-assistant-agent` 直接依赖；它不是独立部署的服务，也没有自己的 HTTP 入口。

## 为什么需要它

AI 项目同时包含患者对话和任务型 Agent：`ai-chat-service` 负责理解会话、决定是否升级任务并保障患者回复链路；`doctor-assistant-agent` 负责执行升级后的任务，产出患者可见回复、结构化结果和医生工作台数据。两者都需要理解同一次任务的路由决策、上下文、运行状态、审批和结果。如果各服务分别定义这些概念，协议与状态规则容易分叉。

本模块把这些共同概念放在同一处，使对话入口与 Agent 执行方能够使用一致的数据结构和治理规则，并为后续接入其他 Agent 提供可复用的基础。具体的医疗场景判断、模型调用、患者对话和医生工作台业务仍由各自服务实现。

## 在主链路中的位置

```text
患者消息
   │
   ▼
ai-chat-service ── 路由、放行、上下文投影 ──► doctor-assistant-agent
   │                 │                         │
   │                 └── agent-platform-common ┘
   │                     共享协议与运行时规则
   ▼
患者安全回复／普通对话降级
```

例如，聊天服务用 `PlatformTaskRoutingDecision` 表达任务升级决策，用 `PlatformDispatchPolicyEngine` 判断灰度与熔断放行，并通过 `PlatformContextProjector` 裁剪传给 Agent 的上下文。Agent 侧使用同一组 `Run`、审批、工具与执行循环协议记录和处理任务。Agent 响应返回后，`PlatformRouteRuntime` 提供统一的结果可消费性和降级判断。

## 提供哪些能力

| 目录 | 主要内容 | 作用 |
| --- | --- | --- |
| `model/` | `Run`、`RunStep`、`RunEvent`、`PlatformContext`、`ApprovalTask`、`WorkflowDefinition` 等 | 统一任务运行、上下文、审批、工作流、工具和工作台载荷的数据结构 |
| `registry/` | `AgentRegistry`、`PlatformToolRegistry` 及内存实现 | 统一 Agent 与工具的注册、发现和版本/能力查询入口 |
| `runtime/` | 路由、放行、上下文投影、Agent 选择、循环执行、状态机、审批与仓储接口 | 复用跨服务的执行和治理规则，供业务服务接入或实现 |
| `runtime/` | `RunReplayService`、`PlatformMetricsService`、Prompt 注册与渲染接口 | 支持运行回放、指标记录和 Prompt 管理 |

其中，`RunStateMachine` 集中定义运行状态转换；仓储接口定义持久化边界。`InMemoryRunPersistenceRepository` 等内存实现主要用于学习和本地验证，不能仅凭引入本模块就获得跨实例持久化、审批执行或完整的生产级 Agent 平台能力。

## 模块边界

- **本模块负责**：共享模型、通用策略与状态规则、注册中心和持久化等扩展接口，以及部分可复用的默认/内存实现。
- **`ai-chat-service` 负责**：患者入口、会话与记忆编排、任务触发，以及 Agent 失败时的患者对话承接。
- **`doctor-assistant-agent` 负责**：医疗任务场景、模型与工具调用、结构化分析、人工复核流程的业务实现。

新增平台级协议或规则时，应先确认两个消费者的兼容性；只服务于某一医疗场景的逻辑应留在对应业务服务中。

## 构建与验证

本模块由仓库根 `pom.xml` 管理。以下命令从 `governance-suite` 根目录执行：

```bash
mvn -f ai/agent-platform-common/pom.xml compile
mvn -f ai/agent-platform-common/pom.xml test
```

修改共享协议或运行时行为后，还应按受影响范围验证 `ai-chat-service` 和 `doctor-assistant-agent`。整体 AI 链路参见 [AI 服务体系说明](../README.md)；具体 Agent 场景参见 [医生助手说明](../doctor-assistant-agent/README.md)。
