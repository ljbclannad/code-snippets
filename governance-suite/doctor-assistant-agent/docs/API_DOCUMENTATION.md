# Agent API 文档

## 📋 概述

医生助手 ReAct Agent 的完整 REST API 文档。

**Base URL**: `http://localhost:8088/api/agent`

---

## 🔐 认证

当前版本暂未实现认证，后续可以添加 JWT Token 认证。

---

## 📡 API 接口

### 1. Agent 执行

#### 执行 Agent 任务

**接口**: `POST /execute`

**描述**: 启动一个新的 Agent 任务

**请求体**:
```json
{
  "goal": "分析患者张三的健康状况",
  "customerId": "C001",
  "doctorId": "D001"
}
```

**响应（成功）**:
```json
{
  "status": "SUCCESS",
  "sessionId": "123",
  "finalAnswer": "## 患者健康分析报告\n\n**基本信息**：...",
  "executionHistory": [
    {
      "type": "THOUGHT",
      "content": "我需要先了解患者的基本信息..."
    },
    {
      "type": "ACTION",
      "content": "{\"toolName\":\"get_patient_profile\",\"input\":{...}}"
    },
    {
      "type": "OBSERVATION",
      "content": "{\"name\":\"张三\",\"age\":35,...}"
    }
  ],
  "totalSteps": 5
}
```

**响应（需要人工确认）**:
```json
{
  "status": "PENDING_APPROVAL",
  "sessionId": "123",
  "pendingTask": {
    "id": 456,
    "toolName": "draft_prescription",
    "inputJson": "{\"customerId\":\"C001\",\"medication\":\"降压药\"}",
    "status": "PENDING"
  },
  "executionHistory": [...],
  "totalSteps": 5
}
```

**响应（错误）**:
```json
{
  "status": "ERROR",
  "sessionId": "123",
  "errorMessage": "工具执行失败: ...",
  "executionHistory": [...],
  "totalSteps": 3
}
```

---

#### 恢复 Agent 执行

**接口**: `POST /resume`

**描述**: 在人工批准后恢复暂停的 Agent 任务

**请求体**:
```json
{
  "sessionId": "123",
  "approvedTaskId": 456
}
```

**响应**: 同 `/execute` 接口

---

### 2. 会话管理

#### 查询会话详情

**接口**: `GET /sessions/{sessionId}`

**描述**: 查询指定会话的详细信息

**路径参数**:
- `sessionId`: 会话ID

**响应**:
```json
{
  "id": 123,
  "customerId": "C001",
  "doctorId": "D001",
  "goal": "分析患者健康状况",
  "status": "COMPLETED",
  "historyJson": "[...]",
  "messagesJson": "[...]",
  "currentStep": 5,
  "finalAnswer": "...",
  "createdAt": "2026-05-19T10:00:00",
  "updatedAt": "2026-05-19T10:05:00",
  "completedAt": "2026-05-19T10:05:00"
}
```

**状态码**:
- `200 OK`: 成功
- `404 Not Found`: 会话不存在

---

#### 查询医生的会话列表

**接口**: `GET /sessions`

**描述**: 查询医生的会话列表（支持按状态过滤）

**查询参数**:
- `doctorId` (必填): 医生ID
- `status` (可选): 会话状态（如 `PAUSED`）

**示例**:
```bash
GET /sessions?doctorId=D001&status=PAUSED
```

**响应**:
```json
[
  {
    "id": 123,
    "customerId": "C001",
    "doctorId": "D001",
    "goal": "分析患者健康状况",
    "status": "PAUSED",
    "currentStep": 3,
    "createdAt": "2026-05-19T10:00:00"
  },
  {
    "id": 124,
    "customerId": "C002",
    "doctorId": "D001",
    "goal": "制定减重计划",
    "status": "PAUSED",
    "currentStep": 5,
    "createdAt": "2026-05-19T09:30:00"
  }
]
```

---

#### 查询患者的会话历史

**接口**: `GET /sessions/customer/{customerId}`

**描述**: 查询患者的历史会话记录

**路径参数**:
- `customerId`: 患者ID

**查询参数**:
- `limit` (可选，默认10): 返回记录数

**示例**:
```bash
GET /sessions/customer/C001?limit=5
```

**响应**: 同"查询医生的会话列表"

---

### 3. 待确认任务管理

#### 查询待确认任务详情

**接口**: `GET /pending-tasks/{taskId}`

**描述**: 查询指定待确认任务的详细信息

**路径参数**:
- `taskId`: 任务ID

**响应**:
```json
{
  "id": 456,
  "agentSessionId": "123",
  "customerId": "C001",
  "doctorId": "D001",
  "toolName": "draft_prescription",
  "inputJson": "{\"customerId\":\"C001\",\"medication\":\"降压药\",\"dosage\":\"10mg\"}",
  "status": "PENDING",
  "createdAt": "2026-05-19T10:03:00",
  "expireAt": "2026-05-19T11:03:00"
}
```

**状态码**:
- `200 OK`: 成功
- `404 Not Found`: 任务不存在

---

#### 查询医生的待确认任务列表

**接口**: `GET /pending-tasks`

**描述**: 查询医生的所有待确认任务

**查询参数**:
- `doctorId` (必填): 医生ID

**示例**:
```bash
GET /pending-tasks?doctorId=D001
```

**响应**:
```json
[
  {
    "id": 456,
    "agentSessionId": "123",
    "customerId": "C001",
    "doctorId": "D001",
    "toolName": "draft_prescription",
    "inputJson": "{...}",
    "status": "PENDING",
    "createdAt": "2026-05-19T10:03:00"
  },
  {
    "id": 457,
    "agentSessionId": "124",
    "customerId": "C002",
    "doctorId": "D001",
    "toolName": "draft_diagnosis",
    "inputJson": "{...}",
    "status": "PENDING",
    "createdAt": "2026-05-19T09:45:00"
  }
]
```

---

#### 查询会话的待确认任务列表

**接口**: `GET /sessions/{sessionId}/pending-tasks`

**描述**: 查询指定会话的所有待确认任务

**路径参数**:
- `sessionId`: 会话ID

**响应**: 同"查询医生的待确认任务列表"

---

#### 批准任务

**接口**: `POST /pending-tasks/{taskId}/approve`

**描述**: 批准待确认任务

**路径参数**:
- `taskId`: 任务ID

**请求体**:
```json
{
  "reviewComment": "处方合理，批准开具"
}
```

**响应**:
```json
{
  "success": true,
  "message": "任务已批准",
  "taskId": 456
}
```

---

#### 拒绝任务

**接口**: `POST /pending-tasks/{taskId}/reject`

**描述**: 拒绝待确认任务

**路径参数**:
- `taskId`: 任务ID

**请求体**:
```json
{
  "reviewComment": "剂量过大，需要调整"
}
```

**响应**:
```json
{
  "success": true,
  "message": "任务已拒绝",
  "taskId": 456
}
```

---

## 🔄 完整工作流程

### 场景：Agent 需要开具处方

```
1. 前端调用 POST /execute
   ↓
2. Agent 开始执行，遇到高风险操作
   ↓
3. 返回 PENDING_APPROVAL 状态
   {
     "status": "PENDING_APPROVAL",
     "sessionId": "123",
     "pendingTask": {...}
   }
   ↓
4. 前端展示待确认任务给医生
   ↓
5. 医生审批：
   - 批准：POST /pending-tasks/456/approve
   - 拒绝：POST /pending-tasks/456/reject
   ↓
6. 如果批准，前端调用 POST /resume
   {
     "sessionId": "123",
     "approvedTaskId": 456
   }
   ↓
7. Agent 恢复执行，返回最终结果
   {
     "status": "SUCCESS",
     "finalAnswer": "..."
   }
```

---

## 📊 状态说明

### 会话状态（SessionStatus）

| 状态 | 说明 |
|------|------|
| `RUNNING` | 运行中 |
| `PAUSED` | 已暂停（等待人工确认） |
| `COMPLETED` | 已完成 |
| `FAILED` | 失败 |

### 任务状态（TaskStatus）

| 状态 | 说明 |
|------|------|
| `PENDING` | 待审批 |
| `APPROVED` | 已批准 |
| `REJECTED` | 已拒绝 |
| `EXPIRED` | 已过期 |

### 结果状态（ResultStatus）

| 状态 | 说明 |
|------|------|
| `SUCCESS` | 成功完成 |
| `PENDING_APPROVAL` | 等待人工确认 |
| `ERROR` | 执行错误 |
| `MAX_STEPS_REACHED` | 达到最大步数限制 |

---

## ⚠️ 错误处理

### 错误响应格式

```json
{
  "code": "AGENT_ERROR",
  "message": "会话不存在: 123"
}
```

### 常见错误码

| 错误码 | HTTP状态码 | 说明 |
|--------|-----------|------|
| `AGENT_ERROR` | 400 | Agent 业务错误 |
| `INTERNAL_ERROR` | 500 | 系统内部错误 |

---

## 🧪 测试示例

### 使用 curl 测试

#### 1. 执行 Agent 任务
```bash
curl -X POST http://localhost:8088/api/agent/execute \
  -H "Content-Type: application/json" \
  -d '{
    "goal": "分析患者张三的健康状况",
    "customerId": "C001",
    "doctorId": "D001"
  }'
```

#### 2. 查询待确认任务
```bash
curl http://localhost:8088/api/agent/pending-tasks?doctorId=D001
```

#### 3. 批准任务
```bash
curl -X POST http://localhost:8088/api/agent/pending-tasks/456/approve \
  -H "Content-Type: application/json" \
  -d '{
    "reviewComment": "批准"
  }'
```

#### 4. 恢复执行
```bash
curl -X POST http://localhost:8088/api/agent/resume \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "123",
    "approvedTaskId": 456
  }'
```

#### 5. 查询会话详情
```bash
curl http://localhost:8088/api/agent/sessions/123
```

---

## 📝 注意事项

1. **任务过期时间**：待确认任务默认 1 小时后过期
2. **最大步数限制**：Agent 默认最多执行 15 步
3. **并发控制**：同一会话不能同时执行多个 resume 操作
4. **数据持久化**：所有会话和任务数据都持久化到数据库

---

## 🚀 后续扩展

1. **认证授权**：添加 JWT Token 认证
2. **权限控制**：医生只能查看自己的任务
3. **WebSocket**：实时推送任务状态变化
4. **分页查询**：支持大数据量的分页查询
5. **批量操作**：支持批量批准/拒绝任务
