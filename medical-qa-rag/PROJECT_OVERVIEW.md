# 项目说明

## 1. 项目定位

`medical-qa-rag` 是一个面向医疗政策与公开医学资料的 RAG 原型工程。

它当前的重点不是完整业务系统，也不是前后端一体化产品，而是把下面这条链路打通并持续优化：

`公开资料采集 -> 文本清洗 -> chunk 切分 -> 向量建库 -> 检索/重排 -> 基于证据问答 -> 评测回归`

这个项目适合用于：

- 验证医疗政策类文档在 RAG 场景下的检索效果
- 比较不同切分、检索、重排策略的效果差异
- 构建一个可持续迭代的医疗知识库实验底座

它当前不包含：

- 完整的 Web 服务层
- 前端交互页面
- 训练型模型微调流程
- 权限控制、多用户、在线运维能力

## 2. 当前目标

项目当前主要解决两个问题：

1. 怎么把分散的医疗公开资料整理成可检索知识库  
2. 怎么用稳定的评测与回归机制，判断检索改动到底是变好了还是变差了

所以现在的代码重点在三块：

- 数据采集与加工
- 检索问答链路
- 评测与回归闭环

## 3. 目录结构

### 根目录

- [README.md](/Users/lejinbo/LLM/code-snippets/medical-qa-rag/README.md)：快速上手与命令入口
- [PROJECT_OVERVIEW.md](/Users/lejinbo/LLM/code-snippets/medical-qa-rag/PROJECT_OVERVIEW.md)：当前这份完整项目说明
- [rag_pipeline.py](/Users/lejinbo/LLM/code-snippets/medical-qa-rag/rag_pipeline.py)：统一入口，封装抓取、建库、问答、回归
- [requirements.txt](/Users/lejinbo/LLM/code-snippets/medical-qa-rag/requirements.txt)：项目依赖
- [.env.example](/Users/lejinbo/LLM/code-snippets/medical-qa-rag/.env.example)：环境变量样例

### `crawler/`

负责抓取公开医疗资料。

- [MedicalCrawler.py](/Users/lejinbo/LLM/code-snippets/medical-qa-rag/crawler/MedicalCrawler.py)：主爬虫实现
- [config.py](/Users/lejinbo/LLM/code-snippets/medical-qa-rag/crawler/config.py)：爬虫相关配置样例
- [example_usage.py](/Users/lejinbo/LLM/code-snippets/medical-qa-rag/crawler/example_usage.py)：交互示例
- [test_crawler.py](/Users/lejinbo/LLM/code-snippets/medical-qa-rag/crawler/test_crawler.py)：爬虫测试/演示脚本
- [QUICKSTART.md](/Users/lejinbo/LLM/code-snippets/medical-qa-rag/crawler/QUICKSTART.md)：爬虫快速说明

当前抓取来源包括：

- 国家医保局
- 国家卫健委
- PMC OA
- LOINC
- 少量示例化医疗指南数据

### `knowledge-base/`

负责知识库构建、检索、问答和评测。

- [cleaning.py](/Users/lejinbo/LLM/code-snippets/medical-qa-rag/knowledge-base/cleaning.py)：清洗原始文本与 JSON
- [medical_chunk.py](/Users/lejinbo/LLM/code-snippets/medical-qa-rag/knowledge-base/medical_chunk.py)：chunk 切分
- [medical_embedding.py](/Users/lejinbo/LLM/code-snippets/medical-qa-rag/knowledge-base/medical_embedding.py)：embedding 与 FAISS 建库
- [medical_model_chat.py](/Users/lejinbo/LLM/code-snippets/medical-qa-rag/knowledge-base/medical_model_chat.py)：检索、重排、证据压缩、问答
- [reranker.py](/Users/lejinbo/LLM/code-snippets/medical-qa-rag/knowledge-base/reranker.py)：CrossEncoder / LLM reranker
- [eval_retrieval.py](/Users/lejinbo/LLM/code-snippets/medical-qa-rag/knowledge-base/eval_retrieval.py)：单次检索评测
- [report_eval.py](/Users/lejinbo/LLM/code-snippets/medical-qa-rag/knowledge-base/report_eval.py)：多次评测结果汇总
- [regression_eval.py](/Users/lejinbo/LLM/code-snippets/medical-qa-rag/knowledge-base/regression_eval.py)：批量回归与基线比较

### `eval/`

负责评测集、回归配置和基线样例。

- [eval/datasets/README.md](/Users/lejinbo/LLM/code-snippets/medical-qa-rag/eval/datasets/README.md)：评测集格式说明
- [eval/datasets/policy_eval_template.json](/Users/lejinbo/LLM/code-snippets/medical-qa-rag/eval/datasets/policy_eval_template.json)：评测集模板
- [eval/regression_config.example.json](/Users/lejinbo/LLM/code-snippets/medical-qa-rag/eval/regression_config.example.json)：回归配置模板
- [eval/baselines/example_baseline.json](/Users/lejinbo/LLM/code-snippets/medical-qa-rag/eval/baselines/example_baseline.json)：基线文件样例

### `tests/`

目前以轻量逻辑测试为主。

- [test_rag_pipeline.py](/Users/lejinbo/LLM/code-snippets/medical-qa-rag/tests/test_rag_pipeline.py)：覆盖证据判断、metadata 保留、baseline/回归逻辑

## 4. 核心数据流

### 阶段 1：采集

爬虫把公开网页抓下来，保存成结构化 JSON 和便于人工阅读的文本文件。

单篇文档的基本字段通常包括：

- `title`
- `url`
- `source`
- `content`
- `crawl_time`

### 阶段 2：清洗

[cleaning.py](/Users/lejinbo/LLM/code-snippets/medical-qa-rag/knowledge-base/cleaning.py) 会做轻量清洗，主要删除：

- 页脚地址与联系方式
- 版权与备案信息
- 分隔符
- 重复口号
- 无意义提示语

清洗后会新增：

- `cleaned_content`

### 阶段 3：切分

[medical_chunk.py](/Users/lejinbo/LLM/code-snippets/medical-qa-rag/knowledge-base/medical_chunk.py) 支持四类切分：

- `fixed`：固定字符长度
- `structured`：按标题和编号切
- `semantic`：按句子组合
- `semantic_meta`：对 JSON 按记录切，并保留原始 metadata

这是当前最推荐的生产实验策略，因为它兼顾了：

- 文本可读性
- chunk 连贯性
- metadata 可追溯性

### 阶段 4：向量建库

[medical_embedding.py](/Users/lejinbo/LLM/code-snippets/medical-qa-rag/knowledge-base/medical_embedding.py) 会把 chunk 转成 `Document`，再写入 FAISS。

当前会尽量保留这些 metadata：

- `chunk_id`
- `source`
- `source_name`
- `source_url`
- `source_file`
- `title`
- `pub_year`
- `crawl_time`

这些字段后面会直接影响：

- 引用展示
- 检索评测
- 过滤与重排
- 回归标注稳定性

### 阶段 5：检索与重排

[medical_model_chat.py](/Users/lejinbo/LLM/code-snippets/medical-qa-rag/knowledge-base/medical_model_chat.py) 目前支持：

- `dense`：纯向量检索
- `bm25`：稀疏检索
- `hybrid_rrf`：稠密 + BM25 的 RRF 融合
- `hybrid_weighted`：归一化加权融合

可选增强：

- 硬过滤
- 软重排
- CrossEncoder rerank
- LLM rerank
- 上下文压缩

### 阶段 6：问答

问答阶段的核心原则是：

- 先检索证据
- 再基于证据回答
- 证据不足时拒答
- 返回引用与风险提示

当前回答支持两种形式：

- 普通文本回答
- 结构化 JSON 输出

### 阶段 7：评测与回归

这是现在项目里非常关键的一层。

单次评测脚本 [eval_retrieval.py](/Users/lejinbo/LLM/code-snippets/medical-qa-rag/knowledge-base/eval_retrieval.py) 输出：

- `Recall@K`
- `MRR@K`
- `Hit@K`

批量回归脚本 [regression_eval.py](/Users/lejinbo/LLM/code-snippets/medical-qa-rag/knowledge-base/regression_eval.py) 支持：

- 一次跑多组方案
- 生成多份评测报告
- 汇总成 `summary.csv/md`
- 与已有 baseline 比较
- 在指标回退时失败退出

这意味着以后改下面这些东西时，都可以量化判断是不是退化：

- 切分方式
- embedding 模型
- hybrid 参数
- rerank 策略
- 过滤逻辑

## 5. 统一入口设计

当前推荐通过 [rag_pipeline.py](/Users/lejinbo/LLM/code-snippets/medical-qa-rag/rag_pipeline.py) 使用项目。

它提供四个命令：

- `crawl`
- `build-index`
- `answer`
- `regression`

这四个命令分别对应：

1. 抓取资料  
2. 清洗 + 切分 + 建索引  
3. 基于索引问答  
4. 跑回归评测并与基线比较

这样做的好处是：

- 避免直接记多个子脚本命令
- 降低新成员理解成本
- 方便未来接入 CI

## 6. 当前依赖与外部能力

### 基础依赖

- `requests`
- `beautifulsoup4`
- `lxml`

### RAG 相关依赖

- `langchain-core`
- `langchain-community`
- `langchain-openai`
- `faiss-cpu`
- `rank-bm25`

### 可选增强

- `sentence-transformers`

### 外部服务

- `DASHSCOPE_API_KEY`：向量 embedding
- `DEEPSEEK_API_KEY`：问答与 LLM rerank

## 7. 目前已经补强过的点

相对最早的脚本状态，当前结构已经做过这些工程化改造：

- 去掉多个脚本中的硬编码绝对路径
- 增加统一 pipeline 入口
- 统一 metadata 透传
- 修正证据不足判断逻辑
- 给 BM25 检索增加缓存
- 把评测脚本抽成可复用函数
- 增加 baseline 与 regression 闭环
- 补充环境变量样例与测试

## 8. 当前仍然存在的边界

虽然项目已经能形成实验闭环，但它仍然有一些明确边界：

- 还没有真实线上 API 层
- 还没有异步任务与索引版本管理
- 评测集仍需要人工持续建设
- 医疗语义相关性判断仍偏工程启发式
- 没有完整的端到端集成测试
- 爬虫仍会受目标站点结构变化影响

## 9. 推荐使用方式

如果是第一次接手这个项目，建议按这个顺序理解：

1. 先读 [README.md](/Users/lejinbo/LLM/code-snippets/medical-qa-rag/README.md)
2. 再看 [rag_pipeline.py](/Users/lejinbo/LLM/code-snippets/medical-qa-rag/rag_pipeline.py)
3. 然后按数据流读：
   [MedicalCrawler.py](/Users/lejinbo/LLM/code-snippets/medical-qa-rag/crawler/MedicalCrawler.py) ->
   [cleaning.py](/Users/lejinbo/LLM/code-snippets/medical-qa-rag/knowledge-base/cleaning.py) ->
   [medical_chunk.py](/Users/lejinbo/LLM/code-snippets/medical-qa-rag/knowledge-base/medical_chunk.py) ->
   [medical_embedding.py](/Users/lejinbo/LLM/code-snippets/medical-qa-rag/knowledge-base/medical_embedding.py) ->
   [medical_model_chat.py](/Users/lejinbo/LLM/code-snippets/medical-qa-rag/knowledge-base/medical_model_chat.py)
4. 最后再看：
   [eval_retrieval.py](/Users/lejinbo/LLM/code-snippets/medical-qa-rag/knowledge-base/eval_retrieval.py)、
   [regression_eval.py](/Users/lejinbo/LLM/code-snippets/medical-qa-rag/knowledge-base/regression_eval.py)

## 10. 下一步最值得继续完善的方向

如果继续往前做，优先级建议是：

### P1

- 补一套真实 `policy_eval_v1.json`
- 生成首版正式 baseline
- 跑一遍真实索引上的 dense / hybrid / rerank 对比

### P2

- 增加索引版本标识与元数据
- 增加面向知识库构建的 smoke test
- 增加问答层的引用一致性校验

### P3

- 增加 API 服务层
- 增加前端查询界面
- 增加更严格的医疗问答安全策略

## 11. 总结

从当前结构看，这个项目已经不是“零散脚本集合”，而是一套偏实验型、但具备持续优化能力的医疗 RAG 工程骨架。

它最有价值的地方不只是“能回答问题”，而是已经逐步具备：

- 可追溯的数据处理链路
- 可比较的检索策略实验能力
- 可持续的回归评测机制

后续无论是继续强化检索质量，还是补服务化能力，都可以在这个基础上继续演进。
