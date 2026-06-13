# 减重管理医生助手 Engineering Guide

## 1. 模块结构

```text
doctor-assistant-agent/
├── README.md
├── PRODUCT_OVERVIEW.md
├── ENGINEERING_GUIDE.md
├── sql/
├── src/main/java/com/health/agent/
│   ├── controller/
│   ├── engine/
│   ├── mapper/
│   ├── metrics/
│   ├── model/
│   ├── safety/
│   ├── service/
│   ├── tool/
│   └── vector/
├── src/main/resources/
├── test/
└── monitoring/
```

---

## 2. 核心类职责

### 引擎层

- `ReActAgentEngine`
  - 负责主循环、子代理协调、恢复执行、结果收敛、失败出口和降级
- `ReActPromptBuilder`
  - 构建 ReAct 协议提示词和子代理角色 Prompt
- `ReActResponseParser`
  - 解析严格 JSON 协议响应
- `ActionExecutor`
  - 执行工具、统一标准化工具输出、处理风险分级、落审计
- `SubAgentCoordinator`
  - 基于目标和显式状态选择当前真实子代理

### 子代理层

- `DataCollectionAgent`
  - 优先补齐患者档案、体重历史、聊天、处方和任务等基础事实
- `WeightAnalysisAgent`
  - 基于上下文完成平台期、趋势、依从性和风险信号分析
- `CarePlanningAgent`
  - 基于事实和分析结果生成建议动作、随访计划和审批请求

### 安全层

- `MedicalSafetyService`
  - 负责目标预检
  - 高风险行动前校验
  - 最终输出安全兜底
  - 降级结构化结果构建

### 服务层

- `PatientService`
  - 读取患者档案、体重、聊天、处方历史
- `FollowUpTaskService`
  - 管理随访任务
- `HealthKnowledgeService`
  - 读取减重知识库
- `WeightPlateauService`
  - 停滞期识别
- `AdherenceAnalysisService`
  - 饮食/运动依从性分析
- `AgentAuditService`
  - 记录审计日志

### 工具层

- `ToolRegistry`
  - 注册全部工具和 schema
- `ToolInputValidator`
  - 统一输入校验和归一化

---

## 3. ReAct 执行链路

当前项目已经从最早的“单 ReAct + Observation 文本传递”演进为 4 层架构：

1. 显式状态系统
2. 标准化工具输出
3. 真实子代理协调
4. 多角色协作流式展示

### 3.1 显式状态系统

当前上下文不再只靠 `messages/history` 维持，而是引入了正式的 `AgentContext`：

- `AgentContext`
  - 当前会话的结构化工作内存
- `ContextEntry`
  - 状态槽位
- `EvidenceRecord`
  - 证据记录

当前典型状态槽位包括：

- `patient_profile`
- `weight_history`
- `chat_history`
- `prescription_history`
- `follow_up_tasks`
- `weight_trend_analysis`
- `plateau_assessment`
- `adherence_analysis`
- `health_knowledge`
- `last_tool_result`
- `current_sub_agent`

`AgentContext` 会在工具执行后持续更新，并通过 `context_json` 持久化到 `agent_session`。

### 3.2 标准化工具输出

所有工具当前统一输出 `ToolExecutionOutput`，核心字段：

- `data`
- `summary`
- `stateUpdates`
- `evidence`

执行器 `ActionExecutor` 的职责不再只是跑工具，还负责：

1. 标准化旧输出
2. 把工具结果统一成状态更新协议
3. 让引擎始终消费同一种结果结构

当前 `ReActAgentEngine` 会优先消费：

- `stateUpdates`
- `evidence`

只有在工具结果不是标准结构时才走兼容 fallback。

### 3.3 真实子代理协调

当前不再是纯单 Agent 推理，而是由协调器在每一轮选择真实角色：

- `DataCollectionAgent`
- `WeightAnalysisAgent`
- `CarePlanningAgent`

协调策略由 `SubAgentCoordinator` 根据：

- `goal`
- `AgentContext.slots`

共同判断。

当前策略大致是：

- 上下文缺基础事实时优先 `DataCollectionAgent`
- 已有基础事实但缺分析结果时优先 `WeightAnalysisAgent`
- 已有分析结果后转 `CarePlanningAgent`

### 3.4 多角色流式协作展示

当前 SSE 事件已不只是：

- `thinking_delta`
- `thought`
- `action`
- `observation`

还补充了协作边界事件：

- `subagent_started`
- `subagent_finished`
- `context_updated`

这样前端可以看到：

1. 当前是哪一个子代理在工作
2. 子代理何时切换
3. 显式状态何时更新

### 3.5 当前主链路

主链路：

1. 创建会话
2. 目标预检
3. 初始化 `AgentContext`
4. 选择当前子代理
5. 构建带角色和显式状态的 prompt
6. 调用模型获取 `Thought + Action` 或 `Thought + FinalAnswer`
7. 解析响应
8. 执行工具并获取 `ToolExecutionOutput`
9. 将 `stateUpdates/evidence` 写入 `AgentContext`
10. 记录 Observation
11. 满足结束条件时生成结构化结果
12. 落审计和会话状态

恢复链路：

1. 读取暂停会话
2. 校验任务状态
3. 执行已批准高风险动作
4. 恢复 `AgentContext`
5. 继续带子代理协调的 ReAct 循环

---

## 4. 工具注册与风险分级

### 低风险工具

- `get_patient_profile`
- `get_weight_history`
- `get_chat_history`
- `get_prescription_history`
- `calculate_bmi`
- `analyze_weight_trend`
- `detect_weight_plateau`
- `analyze_adherence_signals`
- `get_health_knowledge`

### 中风险工具

- `send_reminder`
- `create_follow_up_task`
- `send_health_report`

### 高风险工具

- `draft_weight_management_plan`
- `recommend_medical_review`

### 风险执行策略

- `LOW`
  - 直接执行，输出标准 `ToolExecutionOutput`
- `MEDIUM`
  - 执行并写审计，输出标准 `ToolExecutionOutput`
- `HIGH`
  - 不直接执行，创建待审批任务

---

## 5. 结构化输出模型

当前最终结果模型在 `StructuredFinalAnswer`。

核心字段：

- `summary`
- `keyFindings`
- `riskLevel`
- `recommendedActions`
- `needsAttention`
- `uncertainties`
- `supportingEvidence`
- `evidenceSources`
- `inferences`

`AgentResult.finalAnswer` 不是原始源数据，而是 `StructuredFinalAnswer.toTextSummary()` 生成的兼容文本摘要。

---

## 6. 证据链落地方式

### 设计目标

- 最终建议必须可追溯
- 高风险建议不能只靠推断

### 字段约束

- `supportingEvidence`
  - 放事实
- `evidenceSources`
  - 放来源标签
- `inferences`
  - 放归纳判断

### 当前来源标签

- `WEIGHT_HISTORY`
- `CHAT_HISTORY`
- `FOLLOW_UP_TASK`
- `PRESCRIPTION_HISTORY`
- `HEALTH_KNOWLEDGE`
- `SYSTEM_RUNTIME`
- `SAFETY_RULE`

### 当前已补证据输出的工具

- `analyze_weight_trend`
- `detect_weight_plateau`
- `analyze_adherence_signals`

---

## 7. 高风险证据门槛

在 `MedicalSafetyService` 中执行。

规则：

- 当最终输出 `riskLevel = HIGH`
- `supportingEvidence` 少于 2 条时阻断
- `evidenceSources` 为空时阻断

阻断后返回安全降级结构，而不是继续输出高风险建议。

---

## 8. 审计闭环

`AgentAuditService` 已覆盖：

- 中低风险工具执行结果
- 高风险待审批任务创建
- 审批通过 / 拒绝回写
- 最终会话结果

### `agent_execution_log` 当前关心字段

- `session_id`
- `pending_task_id`
- `customer_id`
- `doctor_id`
- `goal`
- `tool_name`
- `tool_input_json`
- `tool_output_json`
- `result_status`
- `approval_status`
- `review_comment`
- `reviewed_at`
- `final_answer`
- `execution_history_json`
- `total_steps`
- `latency_ms`

---

## 9. 流式事件模型

当前主接口 `/api/agent/execute` 与 `/api/agent/resume` 返回 `text/event-stream`。

### 文本增量事件

- `thinking_delta`

### 结构化执行事件

- `thought`
- `action`
- `observation`
- `pending_approval`
- `final_answer`
- `error`
- `done`

### 多角色协作事件

- `subagent_started`
- `subagent_finished`
- `context_updated`

当前一条典型流会看到：

1. `subagent_started`
2. `thinking_delta`
3. `thought`
4. `action`
5. `context_updated`
6. `observation`
7. `subagent_finished`
8. 下一角色 `subagent_started`
9. 最终 `final_answer`
10. `done`

---

## 10. 降级策略实现

当前降级场景：

- 模型超时或重试耗尽
- 协议修复失败
- 达到最大步数
- 恢复执行失败

实现方式：

- 不新增 `DEGRADED` 状态
- 仍沿用现有 `ERROR` / `MAX_STEPS`
- 但始终附带结构化降级结果

降级结果构建入口：

- `MedicalSafetyService.buildDegradedStructuredAnswer(...)`

---

## 11. 关键配置项

### Agent 基础配置

位于 `src/main/resources/application.yml`

- `agent.max-steps`
- `agent.tool-timeout-millis`
- `agent.protocol-repair-attempts`
- `agent.deepseek.*`

### 停滞期识别配置

- `agent.weight-plateau.window-days`
- `agent.weight-plateau.max-weight-change-kg`
- `agent.weight-plateau.recent-trend-days`
- `agent.weight-plateau.recent-downward-threshold-kg`

### 依从性分析配置

- `agent.adherence.analysis-window-days`
- `agent.adherence.min-weight-records`

---

## 11. 数据库表说明

核心表：

- `agent_session`
- `agent_pending_task`
- `agent_execution_log`

业务表：

- `patient_profile`
- `weight_record`
- `chat_message`
- `prescription_record`
- `follow_up_task`
- `health_knowledge`

迁移脚本：

- `sql/schema.sql`
- `sql/migrations/2026-05-30-add-agent-audit-columns.sql`

---

## 12. 本地运行

### 前置条件

- Java 17
- Maven
- MySQL
- Redis

### 构建

```bash
mvn -f ai/doctor-assistant-agent/pom.xml clean package
```

### 运行

```bash
mvn -f ai/doctor-assistant-agent/pom.xml spring-boot:run
```

---

## 13. 测试与验证

当前建议优先运行模块级测试：

```bash
mvn -f ai/doctor-assistant-agent/pom.xml test
```

关键测试方向：

- 工具注册与收口
- 停滞期识别
- 依从性分析
- 结构化输出
- 证据链校验
- 高风险安全规则
- 审计闭环
- 降级策略

---

## 14. 已知限制与后续演进

当前限制：

- 依从性分析仍是规则型
- 真实模型行为稳定性主要依赖测试而非完整评测集
- 部分历史设计文档仍保留旧工具命名

后续建议：

- 建立评测集 V1
- 同步剩余历史文档
- 逐步把更多工具补成标准化证据输出
