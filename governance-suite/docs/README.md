# governance-suite 文档中心

根级文档索引，按主题和用途分类。

## 📚 主题文档

### [AI 服务体系](ai-services/README.md)

AI 记忆系统、对话服务和智能助理相关文档。

- [服务状态概览](ai-services/status.md)
- [降级指南](ai-services/guides/degradation-guide.md)
- [测试报告](ai-services/reports/)

### [患者对话 Agent 协同系统](patient-dialog-agent/README.md)

患者对话系统与医生助理 Agent 协同架构改造专题。

- [演进计划](patient-dialog-agent/evolution-plan.md)
- [评测集](patient-dialog-agent/evals/)

## 📖 通用文档

### [架构文档](architecture/README.md)

- 系统架构说明
- [时序图](architecture/sequence-diagrams.md)

### [API 文档](api/README.md)

- REST API 规范
- Postman Collection

## 🔧 运维手册 (runbooks/)

运行、监控、验证、可观测性操作文档。

- [监控总览](runbooks/MONITORING.md)
- [Prometheus 实现说明](runbooks/PROMETHEUS-IMPLEMENTATION.md)
- [MySQL 读写分离验证](runbooks/MYSQL_READ_WRITE_SPLIT_VERIFICATION.md)
- [Kibana Dashboard 使用指南](runbooks/kibana-dashboard-guide.md)

## 📊 技术分析 (analysis/)

专题分析、设计说明、技术拆解。

### 数据库与性能

- [数据库读写分离配置分析](analysis/数据库读写分离配置分析.md)
- [热门科室缓存维护方案](analysis/热门科室缓存维护方案.md)
- [AppointmentRateLimitFilter 优化总结](analysis/AppointmentRateLimitFilter优化总结.md)

### AI 与记忆系统

- [动态 Token 预算](analysis/dynamic-token-budget.md)
- [记忆检索重排序](analysis/memory-retrieval-rerank.md)
- [记忆检索智能过滤](analysis/memory-retrieval-smart-filter.md)
- [记忆去重增强](analysis/memory-deduplication-enhancement.md)

## 📈 验证报告 (reports/)

实施验证、总结、结果说明。

- [记忆检索重排序实施总结](reports/memory-retrieval-rerank-summary.md)
- [热门科室缓存维护实施验证报告](reports/热门科室缓存维护实施验证报告.md)

## 🗂️ 历史归档 (superpowers/)

历史 specs / plans，作为设计和实施过程归档。

- [plans/](superpowers/plans/) - 历史实施计划

## 📋 文档组织原则

根据 [CLAUDE.md](../CLAUDE.md)：

- **根目录 README.md**：简洁导航，快速入口
- **docs/ 目录**：详细技术文档、分析、实施笔记
- **各模块 docs/**：模块特定文档保留在各自目录
- **分类清晰**：按用途和主题组织，避免扁平化堆积

## 🔍 快速查找

### 我想了解...

- **系统架构** → [architecture/README.md](architecture/README.md)
- **如何监控** → [runbooks/MONITORING.md](runbooks/MONITORING.md)
- **AI 服务** → [ai-services/README.md](ai-services/README.md)
- **Agent 协同** → [patient-dialog-agent/README.md](patient-dialog-agent/README.md)
- **性能优化** → [analysis/](analysis/)
- **API 接口** → [api/README.md](api/README.md)

### 我想解决...

- **服务挂了** → [runbooks/MONITORING.md](runbooks/MONITORING.md)
- **降级问题** → [ai-services/guides/degradation-guide.md](ai-services/guides/degradation-guide.md)
- **记忆检索慢** → [analysis/memory-retrieval-rerank.md](analysis/memory-retrieval-rerank.md)
- **缓存失效** → [analysis/热门科室缓存维护方案.md](analysis/热门科室缓存维护方案.md)
