# AI 记忆功能实施计划 - 总体索引

> **创建日期**: 2026-05-13  
> **项目**: governance-suite  
> **功能**: AI 聊天用户记忆功能

## 一、项目概述

本项目将为现有的微服务系统添加 AI 记忆功能，包括用户画像、向量记忆存储和 AI 聊天协调三个核心子系统。

**目标**：实现 AI 对用户的记忆功能，包括会话上下文记忆和长期用户档案。

**技术栈**：
- Spring Boot 3.2.5 + Spring Cloud 2023.0.1
- Spring AI 1.0.0-M3
- Milvus 2.3.4（向量数据库）
- 阿里云通义千问（Embedding）
- DeepSeek（信息提取）
- RabbitMQ（消息队列）

## 二、子系统划分

根据设计文档，本项目包含以下独立子系统，每个子系统有独立的实施计划：

### 2.1 基础设施和数据库（Plan 1）
**文件**: `2026-05-13-ai-memory-plan-01-infrastructure.md`

**范围**：
- 更新父 pom.xml 添加 Spring AI 依赖
- 扩展 docker-compose.yml 添加 Milvus、etcd、MinIO
- 创建数据库初始化脚本
- 配置环境变量和启动脚本

**预计时间**: 2-3 小时

---

### 2.2 user-profile-service（Plan 2）
**文件**: `2026-05-13-ai-memory-plan-02-user-profile-service.md`

**范围**：
- 创建 user-profile-service 模块
- 实现用户画像构建服务
- 实现 RabbitMQ 消息监听器
- 配置 Redis 缓存
- 实现 REST API（可选）

**预计时间**: 4-6 小时

---

### 2.3 vector-memory-service（Plan 3）
**文件**: `2026-05-13-ai-memory-plan-03-vector-memory-service.md`

**范围**：
- 创建 vector-memory-service 模块
- 集成 Spring AI（Milvus、通义千问、DeepSeek）
- 实现向量存储和检索服务
- 实现记忆提取服务
- 实现 RabbitMQ 消息监听器

**预计时间**: 6-8 小时

---

### 2.4 ai-chat-service（Plan 4）
**文件**: `2026-05-13-ai-memory-plan-04-ai-chat-service.md`

**范围**：
- 创建 ai-chat-service 模块
- 实现聊天流程协调服务
- 实现 Dify API 调用
- 实现 RabbitMQ 请求-响应模式
- 实现 REST API

**预计时间**: 4-6 小时

---

### 2.5 集成测试和部署（Plan 5）
**文件**: `2026-05-13-ai-memory-plan-05-integration.md`

**范围**：
- 端到端集成测试
- 性能测试
- 监控配置
- 部署验证

**预计时间**: 2-3 小时

---

## 三、实施顺序

建议按以下顺序实施：

```
Plan 1: 基础设施和数据库
   ↓
Plan 2: user-profile-service
   ↓
Plan 3: vector-memory-service
   ↓
Plan 4: ai-chat-service
   ↓
Plan 5: 集成测试和部署
```

**原因**：
1. 基础设施是所有服务的依赖
2. user-profile-service 最简单，可以先验证基础架构
3. vector-memory-service 最复杂，需要更多时间
4. ai-chat-service 依赖前两个服务
5. 最后进行集成测试

## 四、总体时间估算

| 子系统 | 预计时间 |
|--------|---------|
| Plan 1: 基础设施和数据库 | 2-3 小时 |
| Plan 2: user-profile-service | 4-6 小时 |
| Plan 3: vector-memory-service | 6-8 小时 |
| Plan 4: ai-chat-service | 4-6 小时 |
| Plan 5: 集成测试和部署 | 2-3 小时 |
| **总计** | **18-26 小时** |

## 五、前置条件

在开始实施前，确保：

1. ✅ 设计文档已完成并审查通过
2. ✅ 开发环境已准备（JDK 17、Maven、Docker）
3. ✅ 已获取必要的 API Key：
   - 阿里云通义千问 API Key
   - DeepSeek API Key
   - Dify API Key
4. ✅ 基础设施已启动（MySQL、Redis、RabbitMQ、Nacos）

## 六、执行方式

每个子计划都可以独立执行，推荐使用以下方式之一：

**方式 1：Subagent-Driven（推荐）**
- 使用 `superpowers:subagent-driven-development` 技能
- 每个任务由独立的 subagent 执行
- 任务间有 review checkpoint

**方式 2：Inline Execution**
- 使用 `superpowers:executing-plans` 技能
- 在当前会话中批量执行
- 定期 checkpoint 进行 review

## 七、下一步

请选择要开始的子计划：

1. **Plan 1: 基础设施和数据库** - 从这里开始
2. **Plan 2: user-profile-service** - 如果基础设施已完成
3. **Plan 3: vector-memory-service** - 如果 Plan 1-2 已完成
4. **Plan 4: ai-chat-service** - 如果 Plan 1-3 已完成
5. **Plan 5: 集成测试和部署** - 如果所有服务已完成

或者，如果你想一次性查看所有计划，我可以继续生成剩余的详细实施计划文档。
