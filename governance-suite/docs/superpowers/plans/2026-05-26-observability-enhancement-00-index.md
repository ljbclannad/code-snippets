# 可观测性增强实施计划 - 主索引

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 为 governance-suite 微服务项目实现完整的可观测性方案（Metrics + Tracing）

**Architecture:** 
- Micrometer Metrics + Prometheus + Grafana 实现指标监控
- Micrometer Tracing + OpenTelemetry + Jaeger 实现分布式追踪
- 混合埋点策略（AOP 自动 + 手动埋点）
- 按服务拆分的 Grafana 仪表板和告警规则

**Tech Stack:**
- Micrometer 1.2.5
- OpenTelemetry 1.36.0
- Prometheus + Grafana
- Jaeger 1.55
- Spring Boot 3.2.5

---

## 实施计划结构

本实施计划分为 4 个部分，按顺序执行：

### Part 1: 依赖和基础配置
**文件**: `2026-05-26-observability-enhancement-01-dependencies.md`

**内容**:
- Task 1-2: 父 POM 和 Common 模块依赖配置
- Task 3-8: 所有服务的 application.yml 配置
- Task 9: 构建验证

**预计时间**: 1 小时

**完成标准**:
- ✅ 所有模块包含 Micrometer 依赖
- ✅ 所有服务配置 management 端点
- ✅ 项目可以成功构建

---

### Part 2: 监控组件实现
**文件**: `2026-05-26-observability-enhancement-02-monitoring-components.md`

**内容**:
- Task 1: 创建 @Monitored 注解
- Task 2: 创建 MonitoringAspect 切面
- Task 3: 改造 Trace 类支持 OpenTelemetry
- Task 4-5: 简化 Gateway 和下游服务的 TraceIdFilter
- Task 6: 创建 MetricsConfig 配置类
- Task 7: 集成测试

**预计时间**: 1.5 小时

**完成标准**:
- ✅ @Monitored 注解和切面可用
- ✅ Trace 类从 Micrometer TraceContext 获取 ID
- ✅ Trace ID 在响应头中正确返回

---

### Part 3: 基础设施部署
**文件**: `2026-05-26-observability-enhancement-03-infrastructure.md`

**内容**:
- Task 1: 添加 Jaeger 到 docker-compose
- Task 2-8: 创建 6 个告警规则文件
- Task 9: 更新 Prometheus 配置加载规则
- Task 10: 基础设施集成测试

**预计时间**: 1 小时

**完成标准**:
- ✅ Jaeger 部署并可访问
- ✅ 6 个告警规则文件已创建
- ✅ Prometheus 加载所有告警规则

---

### Part 4: 业务指标埋点和 Grafana 仪表板
**文件**: `2026-05-26-observability-enhancement-04-metrics-dashboards.md`（待创建）

**内容**:
- 各服务的业务指标埋点（@Monitored 注解 + 手动埋点）
- 创建 6 个服务的 Grafana 仪表板
- 端到端验证

**预计时间**: 2.5 小时

**完成标准**:
- ✅ 所有服务的关键业务方法添加监控
- ✅ 6 个 Grafana 仪表板已创建
- ✅ 端到端验证通过

---

## 执行顺序

**必须按顺序执行**，每个 Part 完成后再进入下一个：

```
Part 1 (依赖配置)
    ↓
Part 2 (监控组件)
    ↓
Part 3 (基础设施)
    ↓
Part 4 (业务埋点和仪表板)
```

## 总预计时间

**6 小时**（分 4 个阶段完成）

## 验证检查点

每个 Part 结束时都有验证步骤，确保：
1. 代码编译成功
2. 配置格式正确
3. 服务可以启动
4. 端点可以访问

## 回滚策略

如果某个 Part 失败：
1. 检查该 Part 的完成标准
2. 查看 git log 找到失败前的最后一次成功提交
3. 使用 `git revert` 回滚有问题的提交
4. 重新执行该 Part 的步骤

## 注意事项

1. **频繁提交**: 每个 Task 完成后都要提交，便于回滚
2. **验证优先**: 每个步骤都有验证命令，必须执行
3. **依赖顺序**: Part 1-3 必须按顺序完成，Part 4 可以并行处理各服务
4. **测试环境**: 在开发环境执行，不要直接在生产环境操作

## 下一步

开始执行 Part 1: 依赖和基础配置

```bash
# 查看 Part 1 计划
cat docs/superpowers/plans/2026-05-26-observability-enhancement-01-dependencies.md
```

---

## 设计文档参考

完整的设计文档位于：`docs/superpowers/specs/2026-05-26-observability-enhancement-design.md`

如需了解设计细节、架构决策、指标定义等，请参考设计文档。
