# 减重管理医生助手 ReAct Agent

面向医生和健康管理团队的减重管理随访 Agent。当前项目聚焦：

- 体重进展分析
- 停滞期识别
- 饮食/运动依从性分析
- 结构化建议输出
- 高风险审批、证据链和降级治理

## 文档入口

- [产品导向说明](./PRODUCT_OVERVIEW.md)
- [工程导向说明](./ENGINEERING_GUIDE.md)
- [流式链路架构说明](./AGENT_STREAMING_ARCHITECTURE.md)

## 快速开始

### 环境要求

- Java 17+
- Maven 3.6+
- MySQL 8.0+
- Redis 7.0+

### 数据库初始化

```bash
mysql -u root -p < sql/schema.sql
```

如果本地数据库已是旧表结构，还需要执行迁移：

```bash
mysql -u root -p < sql/migrations/2026-05-30-add-agent-audit-columns.sql
```

### 编译运行

```bash
mvn -f pom.xml clean package
mvn -f pom.xml spring-boot:run
```

## 重要补充文档

- [API 使用指南](./docs/api/API_USAGE_GUIDE.md)
- [患者任务升级与真实 Agent 增强 TODO](./docs/PATIENT_TASK_AGENT_EXECUTION_TODO.md)
