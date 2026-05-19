# 端到端测试指南

## 📋 概述

本文档说明如何进行医生助手 ReAct Agent 的端到端测试。

---

## 🚀 快速开始

### 1. 准备环境

#### 启动 MySQL
```bash
# 确保 MySQL 服务运行
mysql.server start  # macOS
# 或
sudo service mysql start  # Linux
```

#### 初始化数据库
```bash
cd /Users/lejinbo/LLM/code-snippets/governance-suite/doctor-assistant-agent

# 创建数据库和表结构
mysql -u root -p < sql/schema.sql

# 插入测试数据
mysql -u root -p < sql/test_data.sql
```

#### 配置应用
```yaml
# src/main/resources/application.yml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/doctor_assistant
    username: root
    password: your-password

agent:
  use-mock: true  # 使用 Mock 模式进行测试
```

### 2. 启动应用

```bash
mvn spring-boot:run
```

等待应用启动完成，看到以下日志：
```
Started DoctorAssistantAgentApplication in X.XXX seconds
```

### 3. 运行测试

#### 自动化测试脚本
```bash
cd test
chmod +x e2e-test.sh
./e2e-test.sh
```

#### 手动测试
参考下面的"测试场景"章节

---

## 📊 测试数据

### 患者数据

| 患者ID | 姓名 | 年龄 | 性别 | 目标 | 慢性病 |
|--------|------|------|------|------|--------|
| C001 | 张三 | 35 | 女 | 减重7kg | 高血压 |
| C002 | 李四 | 42 | 男 | 减重10kg | 糖尿病、高血脂 |
| C003 | 王五 | 28 | 女 | 减重6kg | 无 |

### 体重记录

- **张三**：30天内从 65kg 降至 62kg（进展良好）
- **李四**：30天内从 87kg 降至 85kg（进展缓慢）
- **王五**：30天内稳定在 58kg 左右

### 聊天记录

- **张三**：最近有焦虑情绪，睡眠质量不好
- **李四**：血糖偏高，体重减得慢
- **王五**：咨询减重计划

### 处方记录

- **张三**：厄贝沙坦片（降压药）、阿司匹林
- **李四**：二甲双胍（降糖药）、阿托伐他汀（降脂药）

---

## 🧪 测试场景

### 场景 1: 查询类操作（无需人工确认）

#### 目标
测试 Agent 执行查询类操作，自动完成任务

#### 步骤

**1. 执行 Agent 任务**
```bash
curl -X POST http://localhost:8088/api/agent/execute \
  -H "Content-Type: application/json" \
  -d '{
    "goal": "分析患者张三的健康状况，包括体重变化趋势和最近的情绪状态",
    "customerId": "C001",
    "doctorId": "D001"
  }'
```

**2. 预期响应**
```json
{
  "status": "SUCCESS",
  "sessionId": "1",
  "finalAnswer": "## 患者健康分析报告\n\n**基本信息**：\n- 姓名：张三\n- 年龄：35岁\n...",
  "executionHistory": [...],
  "totalSteps": 4
}
```

**3. 验证点**
- ✅ 状态为 SUCCESS
- ✅ 返回完整的健康分析报告
- ✅ 包含体重变化趋势
- ✅ 包含情绪状态分析
- ✅ 执行历史包含 THOUGHT、ACTION、OBSERVATION

---

### 场景 2: 高风险操作（需要人工确认）

#### 目标
测试 Agent 遇到高风险操作时暂停，等待人工确认

#### 步骤

**1. 执行高风险任务**
```bash
curl -X POST http://localhost:8088/api/agent/execute \
  -H "Content-Type: application/json" \
  -d '{
    "goal": "为患者张三开具降压药处方，厄贝沙坦片150mg，每日1次",
    "customerId": "C001",
    "doctorId": "D001"
  }'
```

**2. 预期响应（暂停）**
```json
{
  "status": "PENDING_APPROVAL",
  "sessionId": "2",
  "pendingTask": {
    "id": 1,
    "toolName": "draft_prescription",
    "inputJson": "{\"customerId\":\"C001\",\"medication\":\"厄贝沙坦片\",\"dosage\":\"150mg\"}",
    "status": "PENDING"
  },
  "executionHistory": [...],
  "totalSteps": 3
}
```

**3. 查询待确认任务**
```bash
curl http://localhost:8088/api/agent/pending-tasks?doctorId=D001
```

**4. 批准任务**
```bash
curl -X POST http://localhost:8088/api/agent/pending-tasks/1/approve \
  -H "Content-Type: application/json" \
  -d '{
    "reviewComment": "处方合理，批准开具"
  }'
```

**5. 恢复执行**
```bash
curl -X POST http://localhost:8088/api/agent/resume \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "2",
    "approvedTaskId": 1
  }'
```

**6. 预期响应（完成）**
```json
{
  "status": "SUCCESS",
  "sessionId": "2",
  "finalAnswer": "已为患者张三开具降压药处方...",
  "executionHistory": [...],
  "totalSteps": 5
}
```

**7. 验证点**
- ✅ 第一次返回 PENDING_APPROVAL
- ✅ 创建了待确认任务
- ✅ 会话状态为 PAUSED
- ✅ 批准后任务状态变为 APPROVED
- ✅ 恢复后继续执行
- ✅ 最终返回 SUCCESS

---

### 场景 3: 拒绝高风险操作

#### 目标
测试医生拒绝高风险操作的流程

#### 步骤

**1. 执行高风险任务**
```bash
curl -X POST http://localhost:8088/api/agent/execute \
  -H "Content-Type: application/json" \
  -d '{
    "goal": "为患者李四开具降糖药处方",
    "customerId": "C002",
    "doctorId": "D001"
  }'
```

**2. 拒绝任务**
```bash
curl -X POST http://localhost:8088/api/agent/pending-tasks/2/reject \
  -H "Content-Type: application/json" \
  -d '{
    "reviewComment": "剂量需要调整，暂不批准"
  }'
```

**3. 验证点**
- ✅ 任务状态变为 REJECTED
- ✅ 保存了拒绝原因
- ✅ 会话保持 PAUSED 状态

---

### 场景 4: 查询接口测试

#### 测试会话查询

**1. 查询会话详情**
```bash
curl http://localhost:8088/api/agent/sessions/1
```

**2. 查询医生的会话列表**
```bash
curl http://localhost:8088/api/agent/sessions?doctorId=D001
```

**3. 查询患者的会话历史**
```bash
curl http://localhost:8088/api/agent/sessions/customer/C001?limit=5
```

#### 测试任务查询

**1. 查询任务详情**
```bash
curl http://localhost:8088/api/agent/pending-tasks/1
```

**2. 查询医生的待确认任务**
```bash
curl http://localhost:8088/api/agent/pending-tasks?doctorId=D001
```

**3. 查询会话的待确认任务**
```bash
curl http://localhost:8088/api/agent/sessions/2/pending-tasks
```

---

### 场景 5: 错误处理测试

#### 测试不存在的资源

**1. 查询不存在的会话**
```bash
curl http://localhost:8088/api/agent/sessions/99999
```

**预期响应**: 404 Not Found

**2. 查询不存在的任务**
```bash
curl http://localhost:8088/api/agent/pending-tasks/99999
```

**预期响应**: 404 Not Found

#### 测试无效的操作

**1. 恢复未暂停的会话**
```bash
curl -X POST http://localhost:8088/api/agent/resume \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "1",
    "approvedTaskId": 1
  }'
```

**预期响应**: 400 Bad Request
```json
{
  "code": "AGENT_ERROR",
  "message": "会话状态不正确，当前状态: COMPLETED"
}
```

---

## 📈 测试检查清单

### 功能测试

- [ ] Agent 执行查询类操作成功
- [ ] Agent 遇到高风险操作时正确暂停
- [ ] 创建待确认任务成功
- [ ] 批准任务成功
- [ ] 拒绝任务成功
- [ ] 恢复执行成功
- [ ] 多次暂停恢复成功

### API 测试

- [ ] POST /execute 正常工作
- [ ] POST /resume 正常工作
- [ ] GET /sessions/{sessionId} 正常工作
- [ ] GET /sessions 正常工作
- [ ] GET /sessions/customer/{customerId} 正常工作
- [ ] GET /pending-tasks/{taskId} 正常工作
- [ ] GET /pending-tasks 正常工作
- [ ] GET /sessions/{sessionId}/pending-tasks 正常工作
- [ ] POST /pending-tasks/{taskId}/approve 正常工作
- [ ] POST /pending-tasks/{taskId}/reject 正常工作

### 数据持久化测试

- [ ] 会话数据正确保存到数据库
- [ ] 任务数据正确保存到数据库
- [ ] 执行历史正确保存
- [ ] 对话消息正确保存
- [ ] 状态变更正确更新

### 错误处理测试

- [ ] 404 错误正确返回
- [ ] 400 错误正确返回
- [ ] 500 错误正确返回
- [ ] 错误消息清晰明确

---

## 🐛 常见问题

### 1. 应用启动失败

**问题**: 数据库连接失败
```
Could not open JDBC Connection
```

**解决方案**:
- 检查 MySQL 是否运行
- 检查数据库连接配置
- 确认数据库已创建

### 2. 测试数据不存在

**问题**: 查询患者数据返回空
```
{"error": "患者档案不存在"}
```

**解决方案**:
```bash
mysql -u root -p < sql/test_data.sql
```

### 3. Mock 模式未启用

**问题**: 实际调用了 DeepSeek API

**解决方案**:
```yaml
agent:
  use-mock: true
```

### 4. 端口被占用

**问题**: 
```
Port 8088 is already in use
```

**解决方案**:
```bash
# 查找占用端口的进程
lsof -i :8088

# 杀死进程
kill -9 <PID>
```

---

## 📊 测试报告模板

```
测试日期: 2026-05-19
测试人员: XXX
测试环境: 本地开发环境

测试结果:
- 总测试数: 10
- 通过: 10
- 失败: 0

详细结果:
✓ 场景1: 查询类操作 - 通过
✓ 场景2: 高风险操作 - 通过
✓ 场景3: 拒绝操作 - 通过
✓ 场景4: 查询接口 - 通过
✓ 场景5: 错误处理 - 通过

问题记录:
无

建议:
1. 添加性能测试
2. 添加并发测试
3. 添加压力测试
```

---

## 🚀 下一步

测试通过后，可以进行：

1. **性能测试**: 测试系统在高负载下的表现
2. **集成测试**: 与前端集成测试
3. **生产部署**: 部署到生产环境
4. **监控配置**: 配置 Prometheus 和 Grafana
