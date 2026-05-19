# API 使用指南

## 概述

医生助手 ReAct Agent 系统提供完整的 RESTful API，支持 Agent 执行、会话管理和任务审批。

## 访问方式

### Swagger UI（推荐）

启动应用后，访问：

```
http://localhost:8088/swagger-ui.html
```

Swagger UI 提供：
- 完整的 API 文档
- 交互式测试界面
- 请求/响应示例
- 数据模型定义

### OpenAPI 规范

获取 OpenAPI 3.0 规范文档：

```
http://localhost:8088/v3/api-docs
```

### Postman Collection

导入 Postman Collection 文件进行测试：

```
docs/api/doctor-assistant-agent.postman_collection.json
```

## 快速开始

### 1. 启动服务

```bash
# 启动 MySQL 和 Redis
docker-compose up -d mysql redis

# 初始化数据库
mysql -h localhost -P 3306 -u root -p < sql/schema.sql
mysql -h localhost -P 3306 -u root -p < sql/test_data.sql

# 启动应用
mvn spring-boot:run
```

### 2. 验证服务

```bash
# 健康检查
curl http://localhost:8088/actuator/health

# 访问 Swagger UI
open http://localhost:8088/swagger-ui.html
```

### 3. 执行第一个 Agent 任务

```bash
curl -X POST http://localhost:8088/api/agent/execute \
  -H "Content-Type: application/json" \
  -d '{
    "goal": "患者最近体重增加明显，帮我分析一下原因并给出建议",
    "customerId": "1001",
    "doctorId": "2001"
  }'
```

**响应示例（成功完成）：**

```json
{
  "status": "SUCCESS",
  "finalAnswer": "根据患者档案和体重记录分析：\n1. 近3个月体重增加5kg\n2. 可能原因：饮食习惯改变、运动量减少\n3. 建议：控制饮食、增加运动、定期监测",
  "executionHistory": [
    {
      "type": "THOUGHT",
      "content": "我需要先查询患者的基本信息和体重历史"
    },
    {
      "type": "ACTION",
      "content": "get_patient_profile(customerId=1001)"
    },
    {
      "type": "OBSERVATION",
      "content": "患者信息：张三，男，35岁，身高175cm"
    }
  ],
  "totalSteps": 5
}
```

**响应示例（需要确认）：**

```json
{
  "status": "PENDING_APPROVAL",
  "pendingTask": {
    "id": 101,
    "toolName": "draft_prescription",
    "inputJson": "{\"medication\":\"降压药\",\"dosage\":\"10mg\"}",
    "status": "PENDING"
  },
  "executionHistory": [...],
  "totalSteps": 3
}
```

## API 接口详解

### Agent 执行

#### 1. 执行 Agent 任务

**接口：** `POST /api/agent/execute`

**描述：** 启动一个新的 Agent 会话，Agent 将自动分析目标并执行相应操作。

**请求体：**

```json
{
  "goal": "执行目标（自然语言描述）",
  "customerId": "患者 ID",
  "doctorId": "医生 ID"
}
```

**响应：**

```json
{
  "status": "SUCCESS | PENDING_APPROVAL | ERROR | MAX_STEPS_REACHED",
  "finalAnswer": "最终答案（status=SUCCESS 时）",
  "pendingTask": {
    "id": 101,
    "toolName": "工具名称",
    "inputJson": "输入参数 JSON"
  },
  "errorMessage": "错误信息（status=ERROR 时）",
  "executionHistory": [
    {
      "type": "THOUGHT | ACTION | OBSERVATION",
      "content": "步骤内容"
    }
  ],
  "totalSteps": 5
}
```

**状态说明：**

- `SUCCESS`: 任务成功完成
- `PENDING_APPROVAL`: 遇到高风险操作，需要医生确认
- `ERROR`: 执行失败
- `MAX_STEPS_REACHED`: 达到最大步数限制（15步）

#### 2. 恢复 Agent 执行

**接口：** `POST /api/agent/resume`

**描述：** 在医生确认高风险操作后，恢复 Agent 执行。

**请求体：**

```json
{
  "sessionId": "会话 ID",
  "approvedTaskId": 101
}
```

**使用流程：**

1. Agent 执行遇到高风险操作，返回 `PENDING_APPROVAL`
2. 医生查看待确认任务：`GET /api/agent/pending-tasks?doctorId=2001`
3. 医生批准任务：`POST /api/agent/pending-tasks/101/approve`
4. 恢复执行：`POST /api/agent/resume`

### 会话管理

#### 1. 查询会话详情

**接口：** `GET /api/agent/sessions/{sessionId}`

**描述：** 根据会话 ID 查询完整的会话信息。

**响应：**

```json
{
  "id": "1234567890",
  "customerId": "1001",
  "doctorId": "2001",
  "goal": "患者最近体重增加明显，帮我分析一下原因并给出建议",
  "status": "COMPLETED",
  "historyJson": "[...]",
  "messagesJson": "[...]",
  "currentStep": 5,
  "createdAt": "2024-01-15T10:30:00",
  "updatedAt": "2024-01-15T10:35:00"
}
```

#### 2. 查询医生的会话列表

**接口：** `GET /api/agent/sessions?doctorId={doctorId}&status={status}`

**参数：**

- `doctorId`: 医生 ID（必填）
- `status`: 会话状态过滤（可选，如 `PAUSED`）

**示例：**

```bash
# 查询所有会话
curl "http://localhost:8088/api/agent/sessions?doctorId=2001"

# 只查询暂停中的会话
curl "http://localhost:8088/api/agent/sessions?doctorId=2001&status=PAUSED"
```

#### 3. 查询患者的会话历史

**接口：** `GET /api/agent/sessions/customer/{customerId}?limit={limit}`

**参数：**

- `customerId`: 患者 ID（必填）
- `limit`: 返回记录数量（可选，默认 10）

### 任务管理

#### 1. 查询待确认任务详情

**接口：** `GET /api/agent/pending-tasks/{taskId}`

**响应：**

```json
{
  "id": 101,
  "agentSessionId": "1234567890",
  "toolName": "draft_prescription",
  "inputJson": "{\"medication\":\"降压药\",\"dosage\":\"10mg\"}",
  "status": "PENDING",
  "reviewComment": null,
  "createdAt": "2024-01-15T10:32:00"
}
```

#### 2. 查询医生的待确认任务列表

**接口：** `GET /api/agent/pending-tasks?doctorId={doctorId}`

**示例：**

```bash
curl "http://localhost:8088/api/agent/pending-tasks?doctorId=2001"
```

#### 3. 批准任务

**接口：** `POST /api/agent/pending-tasks/{taskId}/approve`

**请求体：**

```json
{
  "reviewComment": "处方合理，批准执行"
}
```

**响应：**

```json
{
  "success": true,
  "message": "任务已批准",
  "taskId": 101
}
```

#### 4. 拒绝任务

**接口：** `POST /api/agent/pending-tasks/{taskId}/reject`

**请求体：**

```json
{
  "reviewComment": "剂量偏大，需要重新评估"
}
```

## 完整使用流程示例

### 场景：起草处方（高风险操作）

#### 步骤 1：执行 Agent 任务

```bash
curl -X POST http://localhost:8088/api/agent/execute \
  -H "Content-Type: application/json" \
  -d '{
    "goal": "患者血压偏高，请起草降压药处方",
    "customerId": "1001",
    "doctorId": "2001"
  }'
```

**响应：**

```json
{
  "status": "PENDING_APPROVAL",
  "pendingTask": {
    "id": 101,
    "toolName": "draft_prescription",
    "inputJson": "{\"medication\":\"氨氯地平\",\"dosage\":\"5mg\",\"frequency\":\"每日一次\"}"
  },
  "executionHistory": [...]
}
```

#### 步骤 2：查看待确认任务

```bash
curl "http://localhost:8088/api/agent/pending-tasks?doctorId=2001"
```

#### 步骤 3：批准任务

```bash
curl -X POST http://localhost:8088/api/agent/pending-tasks/101/approve \
  -H "Content-Type: application/json" \
  -d '{
    "reviewComment": "处方合理，批准执行"
  }'
```

#### 步骤 4：恢复执行

```bash
curl -X POST http://localhost:8088/api/agent/resume \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "1234567890",
    "approvedTaskId": 101
  }'
```

**响应：**

```json
{
  "status": "SUCCESS",
  "finalAnswer": "已为患者起草降压药处方：氨氯地平 5mg，每日一次。建议定期监测血压。",
  "executionHistory": [...]
}
```

## 工具能力说明

### 低风险工具（直接执行）

- `get_patient_profile`: 查询患者档案
- `get_weight_history`: 查询体重历史
- `get_chat_history`: 查询聊天记录
- `search_similar_cases`: 搜索相似病例
- `get_health_report`: 生成健康报告
- `calculate_bmi`: 计算 BMI
- `get_medication_info`: 查询药物信息
- `get_exercise_plan`: 获取运动计划

### 中风险工具（执行并记录）

- `send_reminder`: 发送健康提醒
- `create_follow_up_task`: 创建随访任务
- `send_health_report`: 发送健康报告

### 高风险工具（需要确认）

- `draft_prescription`: 起草处方建议
- `draft_diagnosis`: 起草诊断建议

## 监控指标

### Prometheus 指标

访问 `http://localhost:8088/actuator/prometheus` 获取监控指标：

**Agent 执行指标：**

- `agent_executions_total`: Agent 执行总次数
- `agent_execution_duration_seconds`: 执行耗时分布
- `agent_steps_total`: 执行步数分布
- `agent_tokens_used_total`: Token 消耗统计

**工具调用指标：**

- `tool_calls_total`: 工具调用总次数
- `tool_call_duration_seconds`: 工具调用耗时
- `tool_approval_required_total`: 需要审批的工具调用次数

**向量检索指标：**

- `vector_search_total`: 向量检索总次数
- `vector_search_duration_seconds`: 检索耗时
- `vector_search_cache_hits_total`: 缓存命中次数

## 错误处理

### 常见错误码

- `400 Bad Request`: 请求参数错误
- `404 Not Found`: 资源不存在（会话或任务）
- `500 Internal Server Error`: 服务器内部错误

### 错误响应格式

```json
{
  "timestamp": "2024-01-15T10:30:00",
  "status": 400,
  "error": "Bad Request",
  "message": "参数验证失败：goal 不能为空",
  "path": "/api/agent/execute"
}
```

## 最佳实践

### 1. 会话管理

- 定期清理已完成的会话
- 监控暂停时间过长的会话
- 为每个患者咨询创建独立会话

### 2. 任务审批

- 及时处理待确认任务，避免会话长时间暂停
- 审批时填写详细的 reviewComment，便于追溯
- 拒绝任务后，Agent 会跳过该操作继续执行

### 3. 性能优化

- 使用 Redis 缓存减少数据库查询
- 启用 Milvus 向量检索提升相似病例查询速度
- 监控 Token 消耗，避免超出预算

### 4. 安全建议

- 生产环境启用 API 认证（JWT）
- 敏感数据加密存储
- 记录完整的审计日志
- 定期备份数据库

## 相关文档

- [项目总结](../PROJECT_SUMMARY.md)
- [端到端测试指南](../E2E_TEST_GUIDE.md)
- [Docker 部署指南](../DOCKER_DEPLOYMENT.md)
- [向量检索集成指南](../VECTOR_SEARCH_GUIDE.md)
