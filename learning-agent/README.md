# 学习 Agent（最小可运行示例）

## 简介
学习 Agent 是一个最小可运行的学习型代理示例：它通过增量记录笔记（learn），并用轻量级 TF-IDF 检索（ask）来回答问题。

## 依赖
- Python 3.9+（仅使用标准库）

## 项目结构
- `knowledge-base/`：知识库与索引构建
  - `notes.jsonl`：样例笔记
  - `build_index.py`：构建 TF-IDF 索引
  - `query.py`：检索查询
  - `utils.py`：分词与向量工具
- `agent/`：学习型代理脚本
  - `learning_agent.py`：learn / ask CLI
  - `tools.py`：工具注册与执行

## 快速开始
1) 构建索引：
```bash
cd learning-agent
python knowledge-base/build_index.py
```

2) 提问检索：
```bash
python agent/learning_agent.py ask "如何提高检索命中率？"
```

3) 让 Agent 学习新笔记（写入 notes.jsonl）：
```bash
python agent/learning_agent.py learn \
  --text "学习型Agent应当在对话后总结关键事实，形成可检索的短笔记。" \
  --tags "agent,summary"
```

4) 重新构建索引后再提问：
```bash
python knowledge-base/build_index.py
python agent/learning_agent.py ask "如何让Agent学会总结？"
```

5) 查看与调用工具：
```bash
python agent/learning_agent.py tools
python agent/learning_agent.py tool calc --payload '{"expr": "1+2*3"}'
```

## 如何新增工具
在 `agent/tools.py` 中注册新工具：
1) 增加一个处理函数（接收 `args` 字典并返回字符串）。
2) 在 `get_tools()` 返回列表中加入工具定义（`name/description/args/handler`）。

## 输入/输出示例
- 输入：
  - `ask "如何提高检索命中率？"`
- 输出（示例）：
  - `Top result: note-002`
  - `Answer: 将知识拆成短句并打标签，有助于后续的检索与重用。`
