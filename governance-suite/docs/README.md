# docs

根级文档索引。

## 分类

### `api/`

- API 文档
- Postman Collection

### `architecture/`

- 系统架构说明
- 时序图

### `runbooks/`

- 运行、监控、验证、可观测性操作文档

包含：

- `MONITORING.md`
- `PROMETHEUS-IMPLEMENTATION.md`
- `MYSQL_READ_WRITE_SPLIT_VERIFICATION.md`
- `kibana-dashboard-guide.md`

### `analysis/`

- 专题分析、设计说明、技术拆解

包含：

- `数据库读写分离配置分析.md`
- `dynamic-token-budget.md`
- `memory-retrieval-rerank.md`
- `memory-retrieval-smart-filter.md`
- `memory-deduplication-enhancement.md`
- `AppointmentRateLimitFilter优化总结.md`
- `热门科室缓存维护方案.md`

### `reports/`

- 验证报告、总结、结果说明

包含：

- `memory-retrieval-rerank-summary.md`
- `热门科室缓存维护实施验证报告.md`

### `superpowers/`

- 历史 specs / plans
- 主要作为设计和实施过程归档

## 说明

- 根目录详细文档统一放在 `docs/`
- 各子项目自己的 `docs/` 保持在各自模块下
- `README.md` 只保留导航，详细内容优先下沉到这里
