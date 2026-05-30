# Agent API 文档

> 当前 API 以减重管理医生助手的最新实现为准。
> 若历史资料中出现 `draft_prescription`、`suggest_diagnosis`、`search_similar_cases` 或“分析患者健康状况”等旧表述，请以本文档为准。

## 概述

- Base URL: `http://localhost:8088/api/agent`
- 核心能力：
  - 发起减重管理分析
  - 恢复高风险审批后的会话
  - 查询会话和待审批任务
  - 批准/拒绝高风险任务

## 1. 执行 Agent 任务

**接口**: `POST /execute`

**请求体**

```json
{
  "goal": "分析患者最近两周减重停滞的原因，并给出下一步建议",
  "customerId": "C001",
  "doctorId": "D001"
}
```

**成功响应**

```json
{
  "status": "SUCCESS",
  "sessionId": "123",
  "finalAnswer": "问题摘要: 患者近两周已进入减重停滞期...",
  "structuredFinalAnswer": {
    "summary": "患者近两周已进入减重停滞期。",
    "keyFindings": [
      "近14天体重变化小于0.5kg",
      "最近7天没有明显下降趋势"
    ],
    "riskLevel": "MEDIUM",
    "recommendedActions": [
      "复核饮食记录",
      "加强运动执行"
    ],
    "needsAttention": true,
    "uncertainties": [
      "缺少完整饮食依从性记录"
    ],
    "supportingEvidence": [
      "近14天体重变化 0.3kg",
      "最近7天趋势为 PLATEAU"
    ],
    "evidenceSources": [
      "WEIGHT_HISTORY"
    ],
    "inferences": [
      "当前更符合停滞期而非持续下降阶段"
    ]
  },
  "executionHistory": [],
  "totalSteps": 4
}
```

**待审批响应**

```json
{
  "status": "PENDING_APPROVAL",
  "sessionId": "123",
  "pendingTask": {
    "id": 456,
    "toolName": "draft_weight_management_plan",
    "status": "PENDING"
  },
  "executionHistory": [],
  "totalSteps": 3
}
```

**降级 / 失败响应**

```json
{
  "status": "ERROR",
  "sessionId": "123",
  "errorMessage": "DeepSeek API 调用超时: PT5S",
  "structuredFinalAnswer": {
    "summary": "当前模型调用失败，无法完成自动分析。",
    "riskLevel": "MEDIUM",
    "recommendedActions": [
      "请人工接管当前会话",
      "补充近期关键数据后再尝试重新分析"
    ],
    "uncertainties": [
      "当前未获得可用的自动分析结果"
    ],
    "supportingEvidence": [
      "模型调用超时或重试耗尽"
    ],
    "evidenceSources": [
      "SYSTEM_RUNTIME"
    ],
    "inferences": [
      "当前无法安全完成自动分析，需要人工接管或补充数据"
    ]
  }
}
```

## 2. 恢复 Agent 执行

**接口**: `POST /resume`

**请求体**

```json
{
  "sessionId": "123",
  "approvedTaskId": 456
}
```

## 3. 会话与待审批任务

### 会话

- `GET /sessions/{sessionId}`
- `GET /sessions?doctorId={doctorId}&status={status}`
- `GET /sessions/customer/{customerId}?limit=10`

### 待审批任务

- `GET /pending-tasks/{taskId}`
- `GET /pending-tasks?doctorId={doctorId}`
- `POST /pending-tasks/{taskId}/approve`
- `POST /pending-tasks/{taskId}/reject`

## 4. 当前高风险工具

- `draft_weight_management_plan`
- `recommend_medical_review`

## 5. 说明

- 成功结果优先看 `structuredFinalAnswer`
- 高风险建议需要审批
- 降级结果同样会返回结构化输出
