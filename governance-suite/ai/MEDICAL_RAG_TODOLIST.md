# 医疗搜推 / RAG 打磨 TODOList

本文档面向 `governance-suite/ai` 里的医疗搜推能力建设，目标不是“尽快上线”，而是先把系统打磨到足够稳、可解释、可治理，再逐步放量。

## 1. 总体目标

- 建一个面向医疗/健康管理场景的搜推系统，支持：
  - 医疗知识检索
  - 个体化建议生成
  - 风险识别与人工复核
  - 患者侧安全回复
  - 医生侧证据与结构化结论
- 优先复用现有模块：
  - `knowledge-base-service`：文档治理、解析、切片、向量检索、重写、rerank
  - `ai-chat-service`：会话入口、上下文拼装、路由编排、降级
  - `doctor-assistant-agent`：任务处理、证据链、人工复核、安全治理

## 2. 推荐架构

### 2.1 主链路

1. 用户问题进入 `ai-chat-service`
2. 先做意图识别与路由：
   - 普通闲聊
   - 医疗知识问答
   - 随访分析
   - 下一步建议
   - 风险升级 / 转人工
3. 命中“医疗知识问答”时：
   - 调 `knowledge-base-service` 做检索
   - 用 `LLM + retrieved evidence` 总结患者可见回复
4. 命中“复杂分析/建议/高风险”时：
   - 升级到 `doctor-assistant-agent`
   - Agent 结合患者画像、聊天上下文、知识证据、规则工具生成结构化结果
5. 高风险内容进入人工复核闭环

### 2.2 推荐模块分工

- `knowledge-base-service`
  - 生产级文档抓取与导入
  - 文档治理与去重
  - 混合检索 / 查询改写 / rerank
  - 多知识域、多 trust level 管理
- `doctor-assistant-agent`
  - 使用 KB 证据进行任务分析
  - 生成患者安全回复、医生工作台结论、follow-up 建议
  - 风险分层、审批与证据归档
- `ai-chat-service`
  - 入口编排
  - direct chat / KB QA / Agent task 路由
  - 降级、熔断、推荐开关、指标打点

## 3. 近期优先级

### P0: 把知识搜的底座做扎实

- [x] 统一医疗文档元数据标准
  - `businessDomain`
  - `applicableScope`
  - `trustLevel`
  - `source`
  - `disease`
  - `population`
  - `publishDate`
  - `version`
  - `region`
- [x] 明确知识文档分层
  - 指南 / 共识
  - 药品说明
  - 患教材料
  - SOP / 随访话术
  - 风险预警规则
- [x] 明确 trust level 规范
  - `HIGH`：指南、共识、官方说明书、院内审核文档
  - `MEDIUM`：可靠医疗机构科普
  - `LOW`：开放互联网内容，仅做召回扩展，不直接用于患者回复
- [x] 补知识库字段映射策略
  - 标签归一
  - 疾病名同义词
  - 药品名/商品名/通用名映射
  - 检查项/症状词典

### P0: 把抓取导入流程产品化

- [x] 支持多来源抓取
  - 医疗指南官网
  - 学会/协会官网
  - 医院科普页面
  - 药品说明书页面
  - FAQ / 患教文档中心
- [x] 当前已补的网页抓取导入骨架继续完善
  - [x] 增加 robots.txt 感知
  - [x] 增加 sitemap 导入
  - [x] 增加增量抓取（基础 refreshMode 版本）
  - [x] 增加 canonical URL 去重
  - [x] 增加内容签名去重
  - [x] 增加页面发布时间提取
- [x] 支持多种导入模式
  - 单 URL 导入
  - 站点批量导入
  - [x] sitemap 批量导入
  - [x] 本地 PDF/Markdown 批量导入
- [x] 抓取结果要落“原文快照”
  - [x] 原始 HTML
  - [x] 标准化 Markdown
  - [x] 清洗后正文
  - [x] 提取出的结构化元数据

### P0: 先做可解释的召回

- [x] 检索策略分层
  - [x] BM25 / keyword recall
  - [x] embedding recall
  - [x] hybrid merge
  - [x] LLM rerank
- [x] 召回结果必须带证据字段
  - [x] 原始标题
  - [x] 片段内容
  - [x] 来源 URL / 文件
  - [x] trust level
  - [x] hit reason
  - [x] version / publish time
- [x] 患者回复里必须能回溯知识来源
- [x] 对低 trust level 结果做降权或默认不出现在患者答案中

## 4. 抓取与文档生产复现

### 4.1 目标

- 尽量复现生产里“文档从外部来源进入知识库”的真实过程，而不是只导入测试文本。
- 对同一来源保留：
  - 原始网页
  - 清洗后的 Markdown
  - 可入库的知识文档
  - 元数据
  - 处理日志

### 4.2 推荐开源组件

- HTML 抓取/解析
  - `jsoup`
  - 后续可选 `crawler4j`
- PDF / Office / 富文本抽取
  - `Apache PDFBox`
  - 后续可选 `Apache Tika`
- Markdown 处理
  - `commonmark`
- 文本清洗
  - `Apache Commons Text`
- 中文分词 / 检索增强
  - 后续可选 `HanLP`
  - 后续可选 `jieba-analysis` / `Lucene SmartCN`
- Rerank / embedding / LLM
  - 当前可延续 `DashScope + DeepSeek`
  - 后续可抽象 provider 接口，支持 BGE / BCE / Qwen / OpenAI compatible

### 4.3 抓取增强 TODO

- [x] `crawl -> normalize -> uploadDocument` 之外，增加原始 HTML 存档
- [x] 支持页面正文抽取规则集
  - `article`
  - `main`
  - `.content`
  - `.markdown-body`
  - 自定义 selector 配置
- [x] 增加黑白名单
  - [x] 允许域名
  - [x] 禁止路径
  - [x] 只抓取某类文档 URL
- [x] 增加抓取作业状态
  - [x] `PENDING`
  - [x] `RUNNING`
  - [x] `PARTIAL_SUCCESS`
  - [x] `FAILED`
  - [x] `COMPLETED`
- [x] 增加抓取作业明细表
  - jobId
  - seedUrl
  - fetchedCount
  - importedCount
  - failedCount
  - operator
  - lastError
- [x] 支持“抓取但不入库”的 dry-run 模式
- [x] 支持“只更新已存在文档”的 refresh 模式

## 5. 检索增强 TODO

### 5.1 查询理解

- [x] 为医疗问句增加 rewrite 模板
  - 症状 -> 疾病 / 分诊 / 处理建议
  - 药品 -> 适应症 / 用法 / 禁忌 / 注意事项
  - 检查指标 -> 含义 / 风险 / 下一步
  - 慢病 -> 生活方式 / 随访 / 监测
- [x] 建同义词表
  - [x] 疾病别名
  - [x] 药品商品名 / 通用名
  - [x] 检查项俗称 / 规范名
  - [x] 症状口语化表达
- [x] 做 query classification
  - fact query
  - advice query
  - emergency risk
  - follow-up recommendation
  - medication safety

### 5.2 召回

- [x] 区分不同知识库 / collection
  - [x] 患教知识
  - [x] 医生工作流知识
  - [x] 药品知识
  - [x] 风险规则知识
- [x] 增加 metadata filter 策略
  - [x] by disease
  - [x] by scope
  - [x] by trust
  - [x] by source type
  - [x] by recency/version
- [x] 增加多路召回
  - [x] 向量召回
  - [x] 关键词召回
  - [x] 规则词表召回
  - [x] FAQ 精准召回

### 5.3 重排

- [x] 引入跨编码器 rerank
- [x] 对结果按场景重排
  - [x] 患者回答优先 high trust + patient scope
  - [x] 医生工作台优先 guideline + review scope
- [x] 做 safety rerank
  - [x] 对涉及用药/禁忌/急症的片段提升优先级
- [x] 对低质量抓取片段做惩罚

## 6. Agent 协同 TODO

- [x] 把 `doctor-assistant-agent` 的 `get_health_knowledge` 从本地表查询升级为：
  - [x] 优先查 `knowledge-base-service`
  - [x] 本地 `health_knowledge` 兜底
- [x] 新增工具：
  - [x] `search_medical_knowledge`
  - [x] `search_medication_guidance`
  - [x] `search_follow_up_protocol`
  - [x] `search_red_flag_guideline`
- [x] Agent 输出中显式带 evidence
  - [x] `title`
  - [x] `snippet`
  - [x] `source`
  - [x] `trustLevel`
  - [x] `score`
- [x] 患者可见答案和医生工作台答案分层
  - 患者侧保守、安全、可读
  - 医生侧详细、结构化、带证据和不确定性

## 7. ai-chat-service 路由 TODO

- [x] 在 `TaskTriggerRouter` 中增加“医疗知识检索问答”意图
- [x] direct chat 与 KB QA 分离
- [x] 非流式/流式响应透传 `patientVisibleEvidence`
- [x] direct chat 命中高风险关键词时强制升级 Agent
- [x] simple fact query 也要区分：
  - [x] 普通知识解释
  - [x] 医疗安全敏感问题
- [x] 支持 direct chat 后追加知识证据卡片
- [x] 支持 direct chat -> KB QA -> Agent 的三级渐进式升级

## 8. 知识治理 TODO

- [x] 建立文档审核机制
  - [x] 自动抓取文档先入候选区
  - [x] 审核通过后再变为 `ACTIVE`
- [x] 建立文档版本机制
- [x] 建立文档失效机制
  - [x] 过期指南
  - [x] 被新版替代
  - [x] 来源失效
- [x] 对“生产文档复现”做审计
  - [x] 抓取时间
  - [x] 来源站点
  - [x] 清洗规则版本
  - [x] 上传人 / 作业 ID

## 9. 评测 TODO

### 9.1 离线评测

- [x] 建医疗问答评测集
  - [x] 症状问答
  - [x] 药品问答
  - [x] 指标解读
  - [x] 慢病管理
  - [x] 高风险分诊
- [x] 建检索评测指标
  - [x] Recall@K
  - [x] MRR
  - [x] nDCG
  - [x] citation coverage
- [x] 建生成评测指标
  - [x] factuality
  - [x] safety
  - [x] evidence grounding
  - [x] helpfulness

### 9.2 在线评测

- [x] 记录检索日志
- [x] 记录命中文档与最终答案的关联
- [x] 记录人工纠偏结果
- [x] 记录患者追问/不满意率
- [x] 记录高风险升级命中率

## 10. 安全与合规 TODO

- [x] 对患者侧输出加统一 disclaimer 模板
- [x] 对用药调整、停药、急症建议加严格拦截
- [x] 对低置信度答案明确表达“不确定”
- [x] 对涉及线下就医的场景优先输出保守建议
- [x] 做 prompt injection 防护
  - [x] 对抓取网页中的恶意指令清洗
  - [x] 对知识片段和系统提示隔离
- [x] 做数据脱敏
  - [x] 患者个人信息
  - [x] 医生内部备注

## 11. 工程 TODO

- [x] 为 crawler 增加独立测试
- [x] 为 HTML -> Markdown 清洗增加快照测试
- [x] 为 crawl import 增加集成测试
- [x] 为 KB search -> Agent answer 增加链路测试
- [x] 为 `patientVisibleEvidence` 透传增加 ai-chat / agent 侧测试
- [x] 指标补齐
  - [x] crawl jobs total
  - [x] crawl pages total
  - [x] crawl import success/failure
  - [x] kb retrieval latency
  - [x] rerank latency
  - [x] evidence usage rate
- [x] 降级策略补齐
  - [x] 抓取失败不影响已有知识库
  - [x] rerank 挂掉时回退 hybrid recall
  - [x] embedding 服务异常时回退 keyword recall

## 12. 建议实施顺序

### Phase 1

- [x] 打磨 `knowledge-base-service` 的网页抓取导入
- [x] 补抓取作业状态、原文快照、元数据标准化
- [x] 构建首批医疗知识样本库

### Phase 2

- [x] 接 `doctor-assistant-agent` 的知识查询工具
- [x] 输出 evidence-aware 的结构化建议
- [x] 打通患者侧安全回复与医生工作台证据

### Phase 3

- [x] 接 `ai-chat-service` 的 KB QA 路由
- [x] 加 direct chat / KB QA / Agent 的三级分流
- [x] 完成评测闭环和灰度策略

### Phase 4

- [x] 多知识域、多 collection、多策略召回
- [x] 精细化 rerank
- [x] 长期运营、版本管理、知识审计

## 13. 当前已落地的起点

- `knowledge-base-service` 已有：
  - 文档上传
  - 文档解析
  - 切片
  - 语义去重
  - 向量化
  - hybrid 检索
  - query rewrite
  - rerank
- 本轮新增了网页抓取导入骨架，方向是：
  - `crawl`
  - `normalize to markdown`
  - `reuse existing upload pipeline`
- 当前已完成的增强包括：
  - `jsoup` 抓取与正文提取
  - `crawl` 接口入库复用现有 `uploadDocument` 流程
  - `sitemap` 批量导入
  - `dry-run` 抓取不入库
  - `refreshMode` 基础增量刷新
  - canonical URL 去重
  - 标准化 Markdown 内容签名去重
  - 路径/URL 级黑白名单过滤
  - `robots.txt` 感知
  - 抓取网络读取重试
  - 抓取作业 manifest 落盘
  - 抓取作业列表 / 详情查询接口
  - 抓取作业 retry / replay 入口
  - Micrometer crawler 指标打点
  - `PARTIAL_SUCCESS` / `FAILED` / `COMPLETED` 作业终态
  - 原始 HTML / 标准化 Markdown 快照归档
  - 页面发布时间提取
  - crawler 单测与 HTML -> Markdown 提取测试
  - doctor-assistant-agent 侧 `knowledge-base-service` 检索客户端
  - `HealthKnowledgeService` 的 KB 优先 / 本地兜底查询路径
  - `get_health_knowledge` 与患者宣教点生成逻辑复用 KB 查询结果
  - KB 检索证据字段下传到工具输出（`trustLevel` / `hitReason` / `score` / `source`）
  - 医生工作台与结构化结果透传 `evidenceDetails`（含 `snippet` / `trustLevel` / `score` / `hitReason`）
  - 患者侧返回 `patientVisibleEvidence`，并与工作台审阅视图对齐
  - `ai-chat-service` 非流式/流式响应透传 `patientVisibleEvidence`

下一步最值得继续做的是：把“抓取导入”做成稳定的数据生产链，然后再把 Agent 和 chat 的搜推协同接上。
