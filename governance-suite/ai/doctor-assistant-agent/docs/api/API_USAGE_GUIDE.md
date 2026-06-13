# API 使用指南

> 当前使用指南以减重管理医生助手的最新实现为准。
> 旧工具名如 `draft_prescription`、`suggest_diagnosis`、`search_similar_cases` 已不再属于当前主工具链。

## 概述

减重管理医生助手提供 RESTful API，支持：

- 发起减重管理分析
- 查询会话
- 查询待审批任务
- 批准或拒绝高风险任务
- 恢复暂停中的会话

## 访问方式

### Swagger UI

```text
http://localhost:8088/swagger-ui.html
```

### OpenAPI 规范

```text
http://localhost:8088/v3/api-docs
```

## 快速开始

### 1. 启动依赖

```bash
docker-compose up -d mysql redis
```

### 2. 初始化数据库

```bash
mysql -h localhost -P 3306 -u root -p < sql/schema.sql
mysql -h localhost -P 3306 -u root -p < sql/test_data.sql
```

如果数据库已是旧结构，还需要执行迁移：

```bash
mysql -h localhost -P 3306 -u root -p < sql/migrations/2026-05-30-add-agent-audit-columns.sql
```

### 3. 启动应用

```bash
mvn spring-boot:run
```

### 4. 健康检查

```bash
curl http://localhost:8088/actuator/health
```

## 第一个请求

```bash
curl -X POST http://localhost:8088/api/agent/execute \
  -H "Content-Type: application/json" \
  -d '{
    "goal": "患者最近两周体重不下降，帮我判断是否进入平台期并给出建议",
    "customerId": "C001",
    "doctorId": "D001"
  }'
```

## 成功响应示例

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

## 待审批响应示例

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

## 降级 / 失败响应示例

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

## 状态说明

- `SUCCESS`
- `PENDING_APPROVAL`
- `ERROR`
- `MAX_STEPS`

## 当前高风险工具

- `draft_weight_management_plan`
- `recommend_medical_review`

## 说明

- 最终结果优先读取 `structuredFinalAnswer`
- 高风险动作会进入审批链
- 降级响应同样返回结构化输出
