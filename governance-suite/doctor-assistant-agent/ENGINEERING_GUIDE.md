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
  - 负责主循环、恢复执行、结果收敛、失败出口和降级
- `ReActPromptBuilder`
  - 构建 ReAct 协议提示词
- `ReActResponseParser`
  - 解析严格 JSON 协议响应
- `ActionExecutor`
  - 执行工具、处理风险分级、落审计

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

主链路：

1. 创建会话
2. 目标预检
3. 构建 prompt
4. 调用模型获取 `Thought + Action` 或 `Thought + FinalAnswer`
5. 解析响应
6. 执行工具
7. 记录 Observation
8. 满足结束条件时生成结构化结果
9. 落审计和会话状态

恢复链路：

1. 读取暂停会话
2. 校验任务状态
3. 执行已批准高风险动作
4. 继续 ReAct 循环

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
  - 直接执行
- `MEDIUM`
  - 执行并写审计
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

## 9. 降级策略实现

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

## 10. 关键配置项

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
mvn -f doctor-assistant-agent/pom.xml clean package
```

### 运行

```bash
mvn -f doctor-assistant-agent/pom.xml spring-boot:run
```

---

## 13. 测试与验证

当前建议优先运行模块级测试：

```bash
mvn -f doctor-assistant-agent/pom.xml test
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
