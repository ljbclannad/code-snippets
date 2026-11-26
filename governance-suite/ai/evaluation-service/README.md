# 可重复评测系统

第一版提供独立的评测闭环：版本化样本集 → 导入运行观测 → 确定性评分 → 保存报告 → 基线/候选对比。

服务不依赖 MySQL、Redis、Milvus、Nacos 或模型凭证，默认只监听 `127.0.0.1:8104`。它不会重新调用模型、执行医疗工具、审批任务或自动发布候选。与现有服务通过 JSON 协议衔接，不改变患者对话主链路。

## 启动与验证

从 `governance-suite` 目录执行：

```bash
mvn -f ai/evaluation-service/pom.xml test
python3 -m unittest discover -s ai/evaluation-service -p 'test_*.py'
mvn -f ai/evaluation-service/pom.xml spring-boot:run
```

环境变量：

| 配置 | 默认值 | 用途 |
|---|---|---|
| `EVALUATION_PORT` | `8104` | HTTP 端口 |
| `EVALUATION_DATA_DIR` | `./data/evaluation` | 样本集与报告目录，相对服务进程工作目录 |

数据目录应固定到本地绝对路径，以便重启后继续使用。目录采用单实例文件存储，依赖本地文件系统支持硬链接；先写临时文件，再原子创建正式文件。同版本拒绝覆盖，报告按 UUID 保存。服务遵循 AI 项目的 [单实例规范](../AGENTS.md#部署范围单实例)；分页和长期存储治理尚未提供。

## 固定业务回归

新增版本化的离线业务回归清单，统一执行路由、幂等、降级和审批相关测试，生成分组报告并显式保留未完成场景。运行方式与门禁语义见 [固定业务回归集](regression/README.md)。它不替代下面的真实回答评测。

## 跑通示例

示例完全使用合成结果，只验证评测系统自身；不代表真实模型质量。以下命令仍从 `governance-suite` 目录执行，需要 Python 3（仅标准库）。

```bash
python3 ai/evaluation-service/run_evaluation.py \
  --dataset ai/evaluation-service/examples/dataset.json \
  --run ai/evaluation-service/examples/baseline.json
```

基线故意包含虚构结论、虚构发送成功和无效引用，因此会生成报告并以退出码 `2` 结束。记下输出中的 `runId`，再运行候选：

```bash
python3 ai/evaluation-service/run_evaluation.py \
  --dataset ai/evaluation-service/examples/dataset.json \
  --run ai/evaluation-service/examples/candidate.json \
  --baseline <基线runId>
```

成功后退出码为 `0`；规则失败、缺失观测或对比门禁未通过时为 `2`；请求/输入故障为 `1`。本地输出位于 `target/evaluation-reports/<runId>/`：

- `report.json`：完整输入、观测、评分明细、版本和快照摘要。
- `report.md`：可阅读的汇总和失败规则，不展示原始回答、轨迹或错误正文。
- `comparison.json`：指定基线时生成的改善、退步、安全退步及门禁结果。

Markdown 中仍包含自定义场景名和规则说明，分享前应检查其内容。JSON 报告保存完整数据，应仅导入合成或已脱敏样本，不把本地运行报告纳入代码仓库。

## 样本与观测协议

完整示例见 [样本集](examples/dataset.json) 和 [运行请求](examples/candidate.json)。

- 样本集有独立 `id/version`，每条样本包含 `id/scenario/input/rules`。
- `input` 是 JSON 对象，可以保存多轮上下文、画像、工具返回等固定测试条件。
- `repetitions` 为 1～10，每条样本预期具有对应的 `trial`（从 1 开始）。
- 每条 `observation` 提交相同的 `input`、实际 `output`、可选 `latencyMs/totalTokens/error`。
- 重复观测、未知样本、输入与固定样本不符直接拒绝；缺失观测或显式执行错误计入 `ERROR`，不会从分母排除。
- 实验必须记录 `name/model/promptVersion/knowledgeVersion/source/configuration`。未知版本显式填 `unknown`，未知耗时和 Token 填 `null`；不推算未采集的费用。
- `source` 为 `MOCK` 或 `RECORDED_REAL`，由导入方声明，系统不能独立证明录制结果确实来自指定模型。

最多 500 条样本，每条最多 50 条规则。评分为同步本地操作，适合当前实验规模。

## 评分规则

`path` 使用 JSON Pointer，针对 `observation.output` 求值，例如 `/response/message`。对象键中 `/` 写为 `~1`，`~` 写为 `~0`；数组可以通过数字索引访问。第一版没有通配符、任意脚本或正则表达式执行。

| operator | 判断方式 |
|---|---|
| `EXISTS` | 字段存在且不是 null |
| `EQUALS` | 与 `expected` 做 JSON 值比较 |
| `CONTAINS` | 文本包含 `expected` 字符串 |
| `NOT_CONTAINS` | 文本不包含 `expected` 字符串 |
| `EQUALS_FIELD` | 与同一 output 内 `referencePath` 的值比较 |
| `SUBSET_OF_FIELD` | 当前数组的元素都在 `referencePath` 数组中 |

缺失、null 或不匹配的字段类型不能通过规则，包括 `NOT_CONTAINS`。空数组可以是合法子集；要求至少一条引用时，应额外对 `/citations/0` 添加 `EXISTS`。

规则 `severity` 为 `QUALITY` 或 `SAFETY`。每个样本所有规则都通过才计为 `PASS`。安全失败数按样本/轮次计数，不是失败规则总数。

文本包含只是确定性的回归约束：例如“不能自行减药”与“可以自行减药”不能仅依靠关键词区分。关键样本应结合结构化动作、固定证据和专业审核。`EQUALS_FIELD` 与引用子集检查仅验证导入字段一致性；参考证据和执行回执必须由可信采集方提供，不能让待评模型自行填写后作为事实。

第一版未提供语义忠实度、医学正确性、模型裁判或专业审核评分。现有 `ai-chat-service` 的 `requiredAnswerSignals` 是语义要求，不能直接原样当作 `CONTAINS`；需人工转换成确定性规则，或后续由语义评分器消费。

## 版本对比与门禁

只有样本快照 SHA-256、评分器版本、重复次数和来源模式相同的运行才能对比。快照来自注册后的完整样本序列化内容，规则修改也必须注册新版本；旧报告携带原始快照，不随样本变化。

按 `caseId + trial` 对齐结果。已通过规则变成失败，即使该样本新旧总体均为 `FAIL`，仍记为退步。重复轮次用于观察变化，不表示模型随机状态完全相同；第一版不做统计显著性判断。

候选有任意安全失败、执行错误、确定性规则失败或退步，`gatePassed` 都为 false。通过只表示此规则集内通过，不代表生产发布批准，也不会修改在线配置。

## API

| 方法与路径 | 用途 |
|---|---|
| `GET /api/evaluation/health` | 服务存活及评分器版本 |
| `POST /api/evaluation/datasets` | 创建不可覆盖的样本版本，成功 201 |
| `GET /api/evaluation/datasets` | 查看样本集 |
| `GET /api/evaluation/datasets/{id}/{version}` | 读取指定版本 |
| `POST /api/evaluation/runs` | 提交录制观测并立即评分，成功 201 |
| `GET /api/evaluation/runs` | 查看运行汇总 |
| `GET /api/evaluation/runs/{id}` | 读取完整运行报告 |
| `GET /api/evaluation/runs/{id}/report` | 获取 Markdown 报告 |
| `GET /api/evaluation/comparisons?baseline={id}&candidate={id}` | 对比两次运行 |

输入错误返回 400，记录不存在返回 404，版本冲突返回 409，存储故障返回 500。本地服务没有身份认证，不应直接改为公网监听；外部接入需另行设计认证和数据访问边界。

## 接入现有 Agent 流程

采集方将执行结果组装为 `output`，服务只评测，不触发业务动作：

```json
{
  "response": {"patientSafeReply": "等待医生复核", "needsHumanReview": true},
  "replay": {"runId": "录制的运行标识"},
  "executionReceipt": {"sent": false}
}
```

- 普通对话：把 `POST /api/chat` 响应放入 `response`，例如检查 `/response/message`。
- 医生任务：把 `/api/patient-tasks/analyze` 响应放入 `response`，从其 `traceContext.runId` 关联 `GET /api/runs/{runId}/replay`，放入 `replay`。
- 普通 `ChatResponse` 没有 `runId`，不能自行推断其对应回放。
- 平台回放的 `outcome` 是结果元数据，不是患者回答；需同时保留任务响应。
- 验证实际发送时，用测试接收端的真实记录填 `executionReceipt`；不能以工具调用摘要代替发送事实。

显式导入接口继续保留；`ai-chat-service` 现已支持绑定测试批次后的异步采集，覆盖同步与流式调用。
`collect_chat_evaluation.py` 可从聊天入口执行固定测试问题、等待批次收齐并导出报告，无需手工整理回答 JSON。
配置、示例、脱敏与验证边界见 [真实调用采集说明](../真实调用采集说明.md)。语义评分器与候选优化器尚未接入。

## 真实调用采集批次

批次允许 `ai-chat-service` 将测试调用的观测异步逐条提交，无需手工组装完整运行 JSON。应先登记固定的合成或脱敏样本，再创建采集批次。`RECORDED_REAL` 表示声明采集自实际调用，并不证明指定模型被真实调用或通过质量认证。

| 接口 | 行为 |
|---|---|
| `POST /api/evaluation/batches` | 输入 `datasetId/datasetVersion/repetitions/experiment`，实验来源仅接受 `RECORDED_REAL` |
| `GET /api/evaluation/batches/{id}` | 返回样本快照、预期/已收数量、观测及 `COLLECTING/READY/FINALIZED` 状态 |
| `POST /api/evaluation/batches/{id}/observations` | 提交 `captureId/caseId/trial/input/output/latencyMs/totalTokens/error` |
| `GET /api/evaluation/batches/{id}/report` | 预览 JSON 报告；缺失观测计入 `ERROR`，不创建正式运行 |
| `POST /api/evaluation/batches/{id}/finalize` | 收齐后保存正式报告，返回的 `id` 可用于原有 Markdown 报告与版本对比接口 |

创建示例：

```json
{
  "datasetId": "patient-chat",
  "datasetVersion": "v1",
  "repetitions": 1,
  "experiment": {
    "name": "患者入口采集基线",
    "model": "unknown",
    "promptVersion": "unknown",
    "knowledgeVersion": "unknown",
    "source": "RECORDED_REAL",
    "configuration": {}
  }
}
```

`input` 必须与指定样本完全一致，`trial` 从 1 开始。同一 `captureId` 的相同内容重试不重复计数；不同内容或同一 `caseId/trial` 使用不同 `captureId` 返回 `409`。未知样本、输入不匹配、次数越界返回 `400`，不存在的批次返回 `404`。失败调用也应提交 `error`，收齐不等于评测通过；缺失观测时 finalize 返回 `409`。

完成批次后禁止新增观测，但相同内容重试仍接受。重复 finalize 返回同一正式报告。批次与观测保存于 `EVALUATION_DATA_DIR/batches`，使用单实例锁和同目录原子替换；重启后可继续采集。最终报告先固化在批次快照，再发布到运行目录，若发布中断，重试 finalize 可恢复。文件系统需支持原子移动，数据目录由本环境唯一的评测服务进程写入。预览与批次 JSON 包含原始观测，仅用于受控测试环境。
