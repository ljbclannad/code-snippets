# Learning Agent（最小可运行示例）

## 简介
Learning Agent 是一个最小可运行的学习型代理示例：它通过增量记录笔记（learn），并用轻量级 TF-IDF 检索（ask）来回答问题。

## 依赖
- Python 3.9+（仅使用标准库）

## 项目结构
- `learning_agent/knowledge_base/`：知识库与索引构建
  - `notes.jsonl`：样例笔记
  - `mock_data.json`：业务数据 mock 测试库（供工具查询）
  - `build_index.py`：构建 TF-IDF 索引
  - `query.py`：检索查询
  - `utils.py`：分词与向量工具
- `learning_agent/agent/`：学习型代理脚本
  - `learning_agent.py`：learn / ask CLI
  - `tools.py`：工具注册与执行

## 快速开始
1) 构建索引：
```bash
cd learning-agent
python -m learning_agent.knowledge_base.build_index
```

2) 提问检索：
```bash
python -m learning_agent.agent.learning_agent ask "如何提高检索命中率？"
```

3) 让 Agent 学习新笔记（写入 notes.jsonl）：
```bash
python -m learning_agent.agent.learning_agent learn \
  --text "学习型Agent应当在对话后总结关键事实，形成可检索的短笔记。" \
  --tags "agent,summary"
```

4) 重新构建索引后再提问：
```bash
python -m learning_agent.knowledge_base.build_index
python -m learning_agent.agent.learning_agent ask "如何让Agent学会总结？"
```

5) 查看与调用工具：
```bash
python -m learning_agent.agent.learning_agent tools
python -m learning_agent.agent.learning_agent tool calc --payload '{"expr": "1+2*3"}'
python -m learning_agent.agent.learning_agent tool biz_query --payload '{"entity": "orders", "filters": {"customer_id": "c-1001"}, "limit": 2}'
```
查看 JSON Schema：
```bash
python -m learning_agent.agent.learning_agent tools --json
```

## 如何新增工具
在 `learning_agent/agent/tools.py` 中注册新工具：
1) 增加一个处理函数（接收 `args` 字典并返回字符串）。
2) 在 `_register_tools()` 中注册 `ToolSpec`，并定义 `parameters` 的 JSON Schema。

示例（JSON Schema 片段）：
```json
{
  "type": "object",
  "properties": {
    "expr": { "type": "string", "description": "Math expression" }
  },
  "required": ["expr"]
}
```

## 输入/输出示例
- 输入：
  - `ask "如何提高检索命中率？"`
  - `tool biz_query --payload '{"entity": "customers", "filters": {"region": "apac"}}'`
- 输出（示例）：
  - `Top result: note-002`
  - `Answer: 将知识拆成短句并打标签，有助于后续的检索与重用。`
  - `Matched 2 customers. [{"id":"c-1001","name":"Alice Chen","tier":"gold","region":"apac"}, ...]`
