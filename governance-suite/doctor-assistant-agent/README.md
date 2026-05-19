# 医生助手 ReAct Agent 项目

## 📋 项目概述

这是一个基于 **ReAct (Reasoning + Acting)** 架构的医生助手 AI Agent 系统，实现了自主推理、多步决策和智能行动执行。

### 核心特性

- ✅ **自主推理**：Agent 自己决定查询什么、分析什么、做什么
- ✅ **多步决策**：根据观察结果动态调整策略，不是固定流程
- ✅ **智能行动**：不只是查询数据，还能发送消息、创建任务、起草处方
- ✅ **完全透明**：每一步 Thought、Action、Observation 都可见
- ✅ **医疗安全**：高风险操作需医生确认，低风险操作直接执行

## 🏗️ 项目结构

```
doctor-assistant-agent/
├── pom.xml                          # Maven 配置
├── sql/
│   └── schema.sql                   # 数据库设计
├── src/main/java/com/health/agent/
│   ├── model/                       # 数据模型
│   │   ├── AgentResult.java        # Agent 执行结果
│   │   ├── ActionDecision.java     # 行动决策
│   │   ├── ThoughtResult.java      # 思考结果
│   │   ├── Step.java               # 执行步骤
│   │   ├── Message.java            # 消息
│   │   ├── PendingTask.java        # 待确认任务实体
│   │   ├── AgentSession.java       # 会话实体
│   │   ├── AgentExecutionLog.java  # 执行日志实体
│   │   ├── SessionStatus.java      # 会话状态枚举
│   │   ├── ResultStatus.java       # 结果状态枚举
│   │   ├── TaskStatus.java         # 任务状态枚举
│   │   └── StepType.java           # 步骤类型枚举
│   ├── tool/                        # 工具系统
│   │   ├── Tool.java               # 工具定义
│   │   ├── ToolExecutor.java       # 工具执行器接口
│   │   ├── ToolRiskLevel.java      # 工具风险等级
│   │   └── ToolRegistry.java       # 工具注册中心（待实现）
│   ├── engine/                      # Agent 引擎
│   │   ├── ReActAgentEngine.java   # ReAct 引擎核心（待实现）
│   │   ├── ReActPromptBuilder.java # Prompt 构建器（待实现）
│   │   ├── ReActResponseParser.java# 响应解析器（待实现）
│   │   └── ActionExecutor.java     # 行动执行器（待实现）
│   ├── service/                     # 业务服务
│   │   ├── AgentSessionService.java# 会话服务（待实现）
│   │   └── PendingTaskService.java # 任务服务（待实现）
│   ├── controller/                  # API 控制器
│   │   └── AgentController.java    # Agent API（待实现）
│   ├── mapper/                      # MyBatis Mapper
│   ├── config/                      # 配置类
│   └── exception/                   # 异常定义
└── src/main/resources/
    ├── application.yml              # 应用配置（待创建）
    └── mapper/                      # MyBatis XML
```

## 🎯 核心功能

### 1. ReAct 工作流程

```
医生："分析患者张三的健康状况"
    ↓
【第1步】Agent Thought: "我需要先了解患者的基本信息"
        Agent Action: get_patient_profile
        Action Input: {"customerId": "张三"}
        Observation: {"age": 35, "gender": "女", "bmi": 22.5, "chronicDiseases": ["高血压"]}
    ↓
【第2步】Agent Thought: "患者有高血压，需要查看用药情况"
        Agent Action: get_prescription_history
        Action Input: {"customerId": "张三"}
        Observation: [历史处方数据]
    ↓
【第3步】Agent Thought: "体重目标是减重，需要查看进展"
        Agent Action: get_weight_history
        Action Input: {"customerId": "张三", "days": 30}
        Observation: [体重下降3kg，但最近反弹1kg]
    ↓
【第4步】Agent Thought: "反弹可能有原因，查看聊天记录"
        Agent Action: get_chat_history
        Action Input: {"customerId": "张三"}
        Observation: [患者说"有点焦虑"、"是不是方法不对"]
    ↓
【第5步】Agent Thought: "患者有焦虑情绪，建议心理咨询"
        Agent Action: draft_prescription
        Action Input: {"customerId": "张三", "recommendations": [...]}
        ⚠️ 高风险操作 → 创建待确认任务 → Agent 暂停
    ↓
【医生审批】医生查看详情 → 批准执行
    ↓
Agent 恢复执行
    ↓
【第6步】Agent Thought: "现在我有足够信息生成完整分析了"
        Final Answer: [完整的患者分析报告]
```

### 2. 工具系统（13个工具）

#### 低风险工具（直接执行）
1. **get_patient_profile** - 查询患者基本信息
2. **get_weight_history** - 查询体重历史
3. **get_chat_history** - 查询聊天记录
4. **get_prescription_history** - 查询历史处方
5. **search_similar_cases** - 搜索相似病例（Milvus）
6. **analyze_weight_trend** - 分析体重趋势并预测
7. **analyze_sentiment** - 分析聊天情感倾向
8. **calculate_risk_score** - 计算患者风险评分

#### 中风险工具（执行但记录）
9. **send_reminder** - 给患者发送提醒消息
10. **create_followup_task** - 创建随访任务
11. **flag_high_risk_patient** - 标记高风险患者

#### 高风险工具（需医生确认）
12. **draft_prescription** - 起草处方建议
13. **suggest_diagnosis** - 提供诊断建议

## 📊 数据库设计

### 核心表

1. **agent_session** - Agent 会话表
   - 存储会话状态、执行历史、对话消息
   - 支持暂停/恢复机制

2. **agent_pending_task** - 待确认任务表
   - 存储高风险操作的待确认任务
   - 支持医生审批流程

3. **agent_execution_log** - 执行日志表
   - 记录每次 Agent 执行的完整信息
   - 用于监控和分析

### 业务表

4. **patient_profile** - 患者档案表
5. **weight_record** - 体重记录表
6. **chat_message** - 聊天记录表
7. **prescription_record** - 处方记录表

## 🚀 快速开始

### 1. 环境要求

- Java 17+
- Maven 3.6+
- MySQL 8.0+
- Redis 7.0+

### 2. 数据库初始化

```bash
# 创建数据库并导入表结构
mysql -u root -p < sql/schema.sql
```

### 3. 配置文件

创建 `src/main/resources/application.yml`：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/doctor_assistant?useUnicode=true&characterEncoding=utf8
    username: root
    password: your_password
  redis:
    host: localhost
    port: 6379

# Claude API 配置
claude:
  api:
    key: ${CLAUDE_API_KEY}
    base-url: https://api.anthropic.com
    model: claude-opus-4-7
    max-tokens: 4096

# Agent 配置
agent:
  max-steps: 15
  session-timeout: 3600
  task-expire-hours: 24
```

### 4. 编译运行

```bash
# 编译
mvn clean package

# 运行
java -jar target/doctor-assistant-agent-1.0.0-SNAPSHOT.jar
```

## 📡 API 接口

### 1. 执行 Agent 任务

```http
POST /api/agent/execute
Content-Type: application/json

{
  "customerId": "C001",
  "doctorId": "D001",
  "goal": "分析患者健康状况，识别风险因素"
}
```

**响应：**
```json
{
  "code": 200,
  "data": {
    "sessionId": "session_abc123",
    "status": "SUCCESS",
    "finalAnswer": "患者张三，35岁女性...",
    "executionHistory": [
      {
        "type": "THOUGHT",
        "content": "我需要先了解患者基本信息",
        "timestamp": "2026-05-19T10:00:00"
      },
      {
        "type": "ACTION",
        "action": {
          "toolName": "get_patient_profile",
          "input": {"customerId": "C001"}
        },
        "timestamp": "2026-05-19T10:00:01"
      },
      {
        "type": "OBSERVATION",
        "content": "{...}",
        "timestamp": "2026-05-19T10:00:02"
      }
    ],
    "totalSteps": 6
  }
}
```

### 2. 获取待确认任务

```http
GET /api/agent/pending-tasks?doctorId=D001
```

### 3. 审批任务

```http
POST /api/agent/pending-tasks/{taskId}/approve
Content-Type: application/json

{
  "approved": true,
  "comment": "同意执行"
}
```

### 4. 查询会话详情

```http
GET /api/agent/sessions/{sessionId}
```

## 🔧 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 3.2.5 | 应用框架 |
| MyBatis Plus | 3.5.5 | ORM 框架 |
| MySQL | 8.0 | 数据库 |
| Redis | 7.0 | 缓存 |
| Claude API | Opus 4.7 | AI 模型 |
| Fastjson | 2.0.43 | JSON 处理 |
| OkHttp | 4.12.0 | HTTP 客户端 |
| Micrometer | - | 监控指标 |

## 📈 性能指标

| 指标 | 目标值 | 说明 |
|------|--------|------|
| 平均步数 | 5-8步 | 完成一次分析的平均步数 |
| 总响应时间 | P95 < 15s | 包含所有 ReAct 循环 |
| 单步延迟 | P95 < 2s | 单次 Thought+Action 的延迟 |
| 并发能力 | 50 QPS | 单实例支持的并发 Agent 数 |

## 💰 成本估算

**假设：**
- 日活医生：100人
- 每医生每日分析：10次
- 平均每次：6步
- 每步消耗：1500 tokens

**月总成本：约 $5,500**（优化后）

## 📝 开发计划

### Phase 1: 核心引擎（当前阶段）
- [x] 项目结构搭建
- [x] 数据模型定义
- [x] 数据库设计
- [ ] ReActAgentEngine 实现
- [ ] ToolRegistry 实现
- [ ] ActionExecutor 实现
- [ ] Prompt 构建和解析

### Phase 2: 工具扩展
- [ ] 13个工具的完整实现
- [ ] Claude API 客户端
- [ ] 待确认任务流程
- [ ] 会话管理服务

### Phase 3: API 开发
- [ ] Controller 层实现
- [ ] Service 层实现
- [ ] Mapper 层实现
- [ ] 异常处理

### Phase 4: 测试部署
- [ ] 单元测试
- [ ] 集成测试
- [ ] Docker 部署
- [ ] 监控告警

## 📚 参考文档

- [架构设计文档](../Obsidian%20Vault/Learning-document/面试准备/医生助手agent设计方案/医生助手Agent开发方案.md)
- [代码实现文档](../Obsidian%20Vault/Learning-document/面试准备/医生助手agent设计方案/医生助手Agent开发方案-代码实现.md)
- [测试部署文档](../Obsidian%20Vault/Learning-document/面试准备/医生助手agent设计方案/医生助手Agent开发方案-测试部署监控.md)

## 🤝 贡献指南

欢迎贡献代码！请遵循以下步骤：

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 开启 Pull Request

## 📄 许可证

本项目采用 MIT 许可证。

## 📞 联系方式

- 项目负责人：[你的名字]
- 邮箱：[your.email@example.com]
- 项目地址：[GitHub 链接]

---

**最后更新：** 2026-05-19  
**项目版本：** v1.0.0-SNAPSHOT
