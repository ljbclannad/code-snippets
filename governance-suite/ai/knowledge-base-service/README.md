# 医疗知识库服务

`knowledge-base-service` 现在不只是“文档上传 + 语义检索”服务，而是医疗 RAG 的后端知识生产与治理底座，覆盖：

- 生产文档抓取、快照归档、标准化 Markdown 入库
- 本地 Markdown/PDF 种子库批量导入
- 医疗元数据标准化、同义词归一、查询理解
- 多路召回：`vector + keyword + lexicon + FAQ exact`
- 检索增强：rewrite、hybrid merge、rerank、场景重排、安全重排、低质量片段惩罚
- 知识治理：审核、版本、失效、审计、候选区过滤
- 在线审计日志与离线评测样本资产

## 当前医疗 RAG 能力

### 1. 文档生产链

- `POST /api/kb/{kbId}/documents`
  - 单文件上传，复用原有解析/切片/向量化链路
- `POST /api/kb/{kbId}/documents/crawl`
  - 支持单 URL、站点批量、sitemap 导入
  - 支持 `dryRun`、`refreshMode`
  - 支持 `robots.txt` 感知、canonical 去重、内容签名去重
  - 落盘 `raw html + normalized markdown + crawl manifest`
- `POST /api/kb/{kbId}/documents/local-batch-import`
  - 从本地 `manifest.json` 导入 Markdown/PDF 批量种子

### 2. 检索增强

搜索入口：

- `POST /api/kb/{kbId}/search`

搜索请求除了原有字段外，新增支持：

- `retrievalProfile`
  - `patient_education`
  - `doctor_workflow`
  - `medication`
  - `risk_triage`
- `documentTypes`
- `diseases`
- `populations`
- `regions`
- `sourceTypes`
- `version`
- `publishDateFrom`

当前检索链路：

1. 医疗术语归一与同义词扩展
2. query classification 与模板扩写
3. retrieval profile 默认过滤条件注入
4. 多路召回
   - vector recall
   - keyword recall
   - lexicon recall
   - FAQ exact recall
5. hybrid merge
6. rerank
   - DeepSeek rerank
   - 场景重排
   - safety rerank
   - 低质量片段惩罚
7. 治理后过滤
   - 待审文档不默认返回
   - 已失效文档不默认返回
   - 低 trust 结果在患者链路默认降权

### 3. 治理与审计

- `POST /api/kb/{kbId}/documents/{docId}/governance/review`
  - 写入 `review_status / reviewer / review_comment / version / publish_date`
- `POST /api/kb/{kbId}/documents/{docId}/governance/invalidate`
  - 写入 `invalidated / invalid_reason / replacement_version / expires_at`

在线落盘文件：

- `data/medical-audit/retrieval-log.ndjson`
  - 检索 query、命中文档、最终链路去向
- `data/medical-governance/document-governance-audit.ndjson`
  - 审核/失效动作审计

## 医疗元数据约定

推荐标签与字段：

- `businessDomain`
- `applicableScope`
- `trustLevel`
- `source`
- `tags`
  - `doc_type:*`
  - `disease:*`
  - `population:*`
  - `region:*`
  - `source_type:*`
  - `version:*`
  - `publish_date:*`
  - `review_status:*`

信任等级：

- `HIGH`
  - 指南、共识、官方说明书、审核通过 SOP
- `MEDIUM`
  - 可靠医院/机构患教
- `LOW`
  - 开放互联网补充内容，仅做扩召回，不默认面向患者输出

## 主要 API

### 知识库

- `POST /api/kb`
- `GET /api/kb/{kbId}`
- `DELETE /api/kb/{kbId}`

### 文档

- `POST /api/kb/{kbId}/documents`
- `GET /api/kb/{kbId}/documents`
- `GET /api/kb/{kbId}/documents/{docId}`
- `GET /api/kb/{kbId}/documents/{docId}/parse-result`
- `GET /api/kb/{kbId}/documents/{docId}/chunks`
- `POST /api/kb/{kbId}/documents/{docId}/archive`
- `POST /api/kb/{kbId}/documents/{docId}/activate`
- `POST /api/kb/{kbId}/documents/{docId}/reprocess`
- `POST /api/kb/{kbId}/documents/crawl`
- `POST /api/kb/{kbId}/documents/local-batch-import`
- `GET /api/kb/{kbId}/documents/crawl/jobs`
- `GET /api/kb/{kbId}/documents/crawl/jobs/{jobId}`
- `POST /api/kb/{kbId}/documents/crawl/jobs/{jobId}/retry`
- `POST /api/kb/{kbId}/documents/{docId}/governance/review`
- `POST /api/kb/{kbId}/documents/{docId}/governance/invalidate`
- `DELETE /api/kb/{kbId}/documents/{docId}`

### 搜索

- `POST /api/kb/{kbId}/search`

## 示例

### 1. 本地种子库导入

先自行准备可供服务进程读取的 manifest 和 Markdown/PDF 文件。仓库当前未附带旧示例中的 `docs/medical-seed-library/manifest.json`；下面的路径是占位，需替换为实际绝对路径。导入前核对本模块的本地批量导入请求和 manifest 解析实现。

```bash
curl -X POST "http://localhost:8103/api/kb/<kbId>/documents/local-batch-import" \
  -H "Content-Type: application/json" \
  -d '{
    "manifestPath": "/替换为实际目录/manifest.json",
    "createdBy": "medical-rag-bootstrap"
  }'
```

### 2. 医疗检索

```bash
curl -X POST "http://localhost:8103/api/kb/<kbId>/search" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "高血压患者运动后头晕怎么办",
    "topK": 5,
    "hybridSearch": true,
    "rerank": true,
    "useRewrite": true,
    "retrievalProfile": "patient_education",
    "trustLevel": "HIGH",
    "sourceTypes": ["guideline", "hospital"]
  }'
```

### 3. 网页抓取导入

```bash
curl -X POST "http://localhost:8103/api/kb/<kbId>/documents/crawl" \
  -H "Content-Type: application/json" \
  -d '{
    "seedUrl": "https://example-hospital.org/education/",
    "sitemapUrl": "https://example-hospital.org/sitemap.xml",
    "maxPages": 20,
    "titlePrefix": "医疗搜推",
    "source": "web_crawler",
    "businessDomain": "medical",
    "applicableScope": "patient_education",
    "trustLevel": "MEDIUM"
  }'
```

## 本地运行与验证

所有命令从 `governance-suite` 根目录执行；环境准备见[运行指南](../本地运行与排障.md)。

```bash
mvn -q -pl ai/knowledge-base-service spring-boot:run
```

```bash
mvn -f ai/knowledge-base-service/pom.xml test
```

可按变更范围选择以下现有测试（此处列出入口，不表示本轮已执行）：

- `MedicalRetrievalProfileServiceTest`
- `KnowledgeSearchEnhancementServiceTest`
- `SearchControllerDegradationTest`
- `DocumentControllerCrawlIntegrationTest`

## 说明

- 模块结构参考 [init_kb_service.sql](src/main/resources/db/init_kb_service.sql)，初始化与已有表升级的区别见[数据库指南](../数据库初始化说明.md)。
- 这一版已经能支撑 `ai-chat-service` 的 KB QA 路由和 `doctor-assistant-agent` 的证据检索工具。
