# 评测集说明

这个目录放人工标注或半自动整理后的检索评测集。

## 推荐格式

每条样本至少包含：

- `query` 或 `question`
- `relevant_doc_ids`

推荐优先使用 `relevant_doc_ids`，格式为：

`来源标识::chunk_id`

例如：

```json
[
  {
    "query": "新增药品挂网工作需要在什么时候之前完成？",
    "relevant_doc_ids": [
      "国家医保局::3-0"
    ]
  }
]
```

## 标注建议

- 同一个问题允许对应多个正确 chunk
- 问题尽量覆盖期限、适用范围、排除条件、机构责任、地区差异这几类
- 每次变更知识库来源或切分策略后，都要重新抽样检查一批 `relevant_doc_ids`

## 文件建议

- `policy_eval_v1.json`: 主评测集
- `policy_eval_smoke.json`: 小规模 smoke 集，10 到 20 条
- `policy_eval_hard.json`: 容易混淆或多跳的问题集合
