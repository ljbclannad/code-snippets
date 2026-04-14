# LLM 应用开发实验场

## 项目简介

本项目是一个 LLM（大语言模型）应用开发的综合实验场，涵盖了从基础 API 调用到高级 RAG（检索增强生成）系统的完整实践。通过 LangChain 框架，展示了提示词工程、对话记忆、向量检索、Agent 等核心技术的实现。

## 技术栈

- **语言**: Python 3.8+
- **框架**: LangChain
- **大模型**: 
  - DeepSeek Chat (OpenAI 兼容接口)
  - 阿里云百炼 (DashScope)
- **向量数据库**: FAISS, Chroma
- **嵌入模型**: DashScope text-embedding-v4
- **其他**: OpenAI SDK, Pydantic

## 项目结构

```
llm-app-dev/
├── api/                        # 基础 API 调用
│   ├── test.py                     # DeepSeek API 测试
│   └── minimal_openai_request.py   # 最小 token 请求示例
├── langchainTest/              # LangChain 基础
│   ├── promptTemplate.py           # 提示词模板
│   ├── fewShotTemplate.py          # Few-shot 学习
│   ├── baseChatModel.py            # 基础对话模型
│   ├── OutputParse.py              # 输出解析
│   └── OutputParseJson.py          # JSON 输出解析
├── memoryTest/                 # 对话记忆
│   └── MessageHistory.py           # 消息历史管理
├── ragTest/                    # RAG 实验
│   ├── semanticSearchTest.py       # 语义搜索
│   ├── QAChunkTest.py              # 问答切片
│   ├── ragAgentTest.py             # RAG Agent
│   ├── multi_route_retriever.py    # 多路由检索
│   ├── understand_medical_query.py # 医疗查询理解
│   └── utils.py                    # 工具函数
└── video-script-generator/     # 视频脚本生成
    ├── requirements.txt
    └── utils.py
```

## 核心模块

### 1. API 基础调用

#### 基础对话

**文件**: `api/test.py`

**功能**: 使用 OpenAI SDK 调用 DeepSeek API

**核心代码**:
```python
import os
from openai import OpenAI

client = OpenAI(
    api_key=os.environ.get("DEEPSEEK_API_KEY"),
    base_url="https://api.deepseek.com",
)

def get_openai_response(client, system_prompt, prompt):
    response = client.chat.completions.create(
        model="deepseek-chat",
        messages=[
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": prompt}
        ],
    )
    return response.choices[0].message.content

# 使用示例
answer = get_openai_response(
    client,
    "你是一个有帮助的助手",
    "什么是机器学习？"
)
print(answer)
```

**运行示例**:
```bash
export DEEPSEEK_API_KEY="your-api-key"
python api/test.py
```

#### 最小 Token 请求

**文件**: `api/minimal_openai_request.py`

**功能**: 演示如何控制 token 使用和参数调优

**关键参数**:
```python
response = client.chat.completions.create(
    model="deepseek-chat",
    messages=[{"role": "user", "content": "Hello"}],
    max_tokens=100,           # 最大生成 token 数
    temperature=1,            # 温度（0-2），越高越随机
    frequency_penalty=0,      # 频率惩罚，减少重复
    presence_penalty=0,       # 存在惩罚，鼓励新话题
)
```

---

### 2. LangChain 基础

#### 提示词模板

**文件**: `langchainTest/promptTemplate.py`

**功能**: 使用模板化管理提示词

**核心代码**:
```python
from langchain.prompts import PromptTemplate

# 定义模板
template = """
你是一个{role}。
请回答以下问题：{question}
"""

prompt = PromptTemplate(
    input_variables=["role", "question"],
    template=template
)

# 格式化提示词
formatted_prompt = prompt.format(
    role="Python 专家",
    question="如何使用装饰器？"
)
```

**高级用法**:
```python
from langchain.prompts import ChatPromptTemplate

# 多轮对话模板
chat_template = ChatPromptTemplate.from_messages([
    ("system", "你是一个{role}"),
    ("human", "{question}"),
    ("ai", "{ai_response}"),
    ("human", "{follow_up}")
])
```

#### Few-Shot 学习

**文件**: `langchainTest/fewShotTemplate.py`

**功能**: 通过示例引导模型输出

**核心代码**:
```python
from langchain.prompts import FewShotPromptTemplate, PromptTemplate

# 定义示例
examples = [
    {"input": "happy", "output": "sad"},
    {"input": "tall", "output": "short"},
    {"input": "hot", "output": "cold"},
]

# 示例模板
example_template = PromptTemplate(
    input_variables=["input", "output"],
    template="Input: {input}\nOutput: {output}"
)

# Few-shot 模板
few_shot_prompt = FewShotPromptTemplate(
    examples=examples,
    example_prompt=example_template,
    prefix="给出以下词的反义词：",
    suffix="Input: {input}\nOutput:",
    input_variables=["input"]
)

# 使用
print(few_shot_prompt.format(input="big"))
```

#### 输出解析

**文件**: `langchainTest/OutputParse.py`, `langchainTest/OutputParseJson.py`

**功能**: 将模型输出解析为结构化数据

**核心代码**:
```python
from langchain.output_parsers import PydanticOutputParser
from pydantic import BaseModel, Field

# 定义输出结构
class Person(BaseModel):
    name: str = Field(description="人名")
    age: int = Field(description="年龄")
    occupation: str = Field(description="职业")

# 创建解析器
parser = PydanticOutputParser(pydantic_object=Person)

# 获取格式说明
format_instructions = parser.get_format_instructions()

# 在提示词中使用
prompt = f"""
提取以下文本中的人物信息：
文本：张三是一名30岁的软件工程师。

{format_instructions}
"""

# 解析输出
person = parser.parse(response)
print(person.name, person.age, person.occupation)
```

---

### 3. 对话记忆

**文件**: `memoryTest/MessageHistory.py`

**功能**: 使用 `RunnableWithMessageHistory` 管理多轮对话

**核心代码**:
```python
from langchain_core.runnables.history import RunnableWithMessageHistory
from langchain_community.chat_message_histories import ChatMessageHistory

# 存储会话历史
store = {}

def get_session_history(session_id: str):
    if session_id not in store:
        store[session_id] = ChatMessageHistory()
    return store[session_id]

# 创建带记忆的 Runnable
with_message_history = RunnableWithMessageHistory(
    runnable=chat_model,
    get_session_history=get_session_history,
    input_messages_key="input",
    history_messages_key="history"
)

# 多轮对话
response1 = with_message_history.invoke(
    {"input": "我叫张三"},
    config={"configurable": {"session_id": "user-001"}}
)

response2 = with_message_history.invoke(
    {"input": "我叫什么名字？"},
    config={"configurable": {"session_id": "user-001"}}
)
# 输出: "你叫张三"
```

---

### 4. RAG（检索增强生成）

#### 语义搜索

**文件**: `ragTest/semanticSearchTest.py`

**功能**: 使用向量数据库进行语义检索

**核心代码**:
```python
from langchain_community.vectorstores import FAISS
from langchain_community.embeddings import DashScopeEmbeddings

# 初始化嵌入模型
embeddings = DashScopeEmbeddings(
    model="text-embedding-v4",
    dashscope_api_key=os.environ.get("DASHSCOPE_API_KEY")
)

# 创建向量数据库
documents = [
    "机器学习是人工智能的一个分支",
    "深度学习使用神经网络",
    "自然语言处理处理文本数据"
]

vectorstore = FAISS.from_texts(documents, embeddings)

# 语义搜索
query = "什么是 AI？"
results = vectorstore.similarity_search(query, k=2)

for doc in results:
    print(doc.page_content)
```

#### 问答切片

**文件**: `ragTest/QAChunkTest.py`

**功能**: 文档切片和问答链构建

**核心代码**:
```python
from langchain.text_splitter import RecursiveCharacterTextSplitter
from langchain.chains import RetrievalQA

# 文档切片
text_splitter = RecursiveCharacterTextSplitter(
    chunk_size=500,
    chunk_overlap=50,
    separators=["\n\n", "\n", "。", "！", "？", ".", "!", "?", " ", ""]
)

chunks = text_splitter.split_text(long_document)

# 创建向量库
vectorstore = FAISS.from_texts(chunks, embeddings)

# 构建 QA 链
qa_chain = RetrievalQA.from_chain_type(
    llm=chat_model,
    chain_type="stuff",
    retriever=vectorstore.as_retriever(search_kwargs={"k": 3})
)

# 提问
answer = qa_chain.invoke({"query": "文档的主要内容是什么？"})
print(answer)
```

#### RAG Agent

**文件**: `ragTest/ragAgentTest.py`

**功能**: 结合工具调用的 RAG Agent

**核心代码**:
```python
from langchain.agents import create_react_agent, AgentExecutor
from langchain.tools.retriever import create_retriever_tool

# 创建检索工具
retriever_tool = create_retriever_tool(
    retriever=vectorstore.as_retriever(),
    name="knowledge_base",
    description="搜索知识库中的相关信息"
)

# 创建 Agent
agent = create_react_agent(
    llm=chat_model,
    tools=[retriever_tool],
    prompt=agent_prompt
)

agent_executor = AgentExecutor(
    agent=agent,
    tools=[retriever_tool],
    verbose=True
)

# 执行
result = agent_executor.invoke({
    "input": "根据知识库，解释什么是机器学习？"
})
```

#### 多路由检索

**文件**: `ragTest/multi_route_retriever.py`

**功能**: 根据查询类型路由到不同的检索器

**核心代码**:
```python
from langchain.retrievers import MultiQueryRetriever

# 创建多个检索器
tech_retriever = tech_vectorstore.as_retriever()
business_retriever = business_vectorstore.as_retriever()

# 路由逻辑
def route_query(query: str):
    if "技术" in query or "代码" in query:
        return tech_retriever
    elif "业务" in query or "流程" in query:
        return business_retriever
    else:
        return default_retriever

# 使用
retriever = route_query(user_query)
results = retriever.get_relevant_documents(user_query)
```

#### 医疗查询理解

**文件**: `ragTest/understand_medical_query.py`

**功能**: 医疗领域的查询意图识别和实体提取

**核心代码**:
```python
from langchain.chains import LLMChain

# 查询理解提示词
query_understanding_prompt = """
分析以下医疗查询，提取关键信息：
查询：{query}

请提取：
1. 疾病名称
2. 症状
3. 查询意图（诊断/治疗/预防）
4. 相关科室

以 JSON 格式返回。
"""

# 创建链
understanding_chain = LLMChain(
    llm=chat_model,
    prompt=PromptTemplate.from_template(query_understanding_prompt)
)

# 分析查询
result = understanding_chain.invoke({
    "query": "我最近头痛，可能是什么原因？"
})
```

---

### 5. 视频脚本生成

**文件**: `video-script-generator/`

**功能**: 自动生成视频脚本的工具集

**核心功能**:
- 主题扩展
- 大纲生成
- 脚本撰写
- 分镜头设计

---

## 快速开始

### 安装依赖

```bash
cd llm-app-dev
pip install -r requirements.txt
```

### 环境配置

创建 `.env` 文件：

```bash
# DeepSeek API
DEEPSEEK_API_KEY=your-deepseek-api-key

# 阿里云百炼
DASHSCOPE_API_KEY=your-dashscope-api-key
```

### 运行示例

#### 1. 基础 API 调用

```bash
python api/test.py
```

#### 2. 提示词模板

```bash
python langchainTest/promptTemplate.py
```

#### 3. Few-Shot 学习

```bash
python langchainTest/fewShotTemplate.py
```

#### 4. 对话记忆

```bash
python memoryTest/MessageHistory.py
```

#### 5. 语义搜索

```bash
python ragTest/semanticSearchTest.py
```

#### 6. RAG Agent

```bash
python ragTest/ragAgentTest.py
```

## 核心概念

### 1. 提示词工程

**目的**: 通过精心设计的提示词引导模型输出

**技巧**:
- **角色设定**: "你是一个 Python 专家"
- **Few-Shot**: 提供示例引导输出格式
- **思维链 (CoT)**: "让我们一步步思考"
- **格式约束**: 指定 JSON、Markdown 等格式

### 2. RAG 工作流

```
用户查询
    ↓
查询改写/扩展
    ↓
向量检索（Top-K）
    ↓
重排序（可选）
    ↓
上下文构建
    ↓
LLM 生成答案
    ↓
后处理/引用
```

### 3. 对话记忆类型

| 类型 | 说明 | 适用场景 |
|------|------|---------|
| **Buffer Memory** | 保存所有历史消息 | 短对话 |
| **Window Memory** | 保存最近 N 轮对话 | 中等长度对话 |
| **Summary Memory** | 总结历史对话 | 长对话 |
| **Entity Memory** | 提取和记忆实体信息 | 需要记住关键信息 |

### 4. 文档切片策略

```python
# 按字符数切片
RecursiveCharacterTextSplitter(
    chunk_size=500,
    chunk_overlap=50
)

# 按语义切片
SemanticChunker(
    embeddings=embeddings,
    breakpoint_threshold_type="percentile"
)

# 按 Markdown 结构切片
MarkdownHeaderTextSplitter(
    headers_to_split_on=[
        ("#", "Header 1"),
        ("##", "Header 2"),
    ]
)
```

## 最佳实践

### 1. API 调用优化

```python
# 使用流式输出
for chunk in client.chat.completions.create(
    model="deepseek-chat",
    messages=[{"role": "user", "content": "讲个故事"}],
    stream=True
):
    print(chunk.choices[0].delta.content, end="")

# 批量处理
from concurrent.futures import ThreadPoolExecutor

def process_query(query):
    return client.chat.completions.create(...)

with ThreadPoolExecutor(max_workers=5) as executor:
    results = executor.map(process_query, queries)
```

### 2. 向量检索优化

```python
# 混合检索（Dense + Sparse）
from langchain.retrievers import EnsembleRetriever

ensemble_retriever = EnsembleRetriever(
    retrievers=[dense_retriever, bm25_retriever],
    weights=[0.7, 0.3]
)

# 重排序
from langchain.retrievers import ContextualCompressionRetriever
from langchain.retrievers.document_compressors import LLMChainExtractor

compressor = LLMChainExtractor.from_llm(llm)
compression_retriever = ContextualCompressionRetriever(
    base_compressor=compressor,
    base_retriever=vectorstore.as_retriever()
)
```

### 3. 提示词优化

```python
# 结构化提示词
prompt = """
# 角色
你是一个专业的数据分析师。

# 任务
分析以下数据并给出洞察。

# 数据
{data}

# 输出格式
1. 关键发现（3-5 条）
2. 趋势分析
3. 建议行动

# 注意事项
- 使用数据支撑结论
- 避免主观臆断
"""
```

## 常见问题

### Q: 如何减少 API 调用成本？

A: 
1. 使用缓存避免重复调用
2. 批量处理请求
3. 选择合适的模型（小模型处理简单任务）
4. 控制 `max_tokens` 参数

### Q: RAG 检索效果不好怎么办？

A:
1. 优化文档切片策略
2. 使用更好的嵌入模型
3. 增加检索数量（Top-K）
4. 添加重排序步骤
5. 查询改写/扩展

### Q: 如何处理长对话的上下文限制？

A:
1. 使用滑动窗口保留最近对话
2. 总结历史对话
3. 提取关键实体和事实
4. 使用长上下文模型

## 相关资源

- [LangChain 官方文档](https://python.langchain.com/)
- [DeepSeek API 文档](https://platform.deepseek.com/docs)
- [阿里云百炼文档](https://help.aliyun.com/zh/dashscope/)
- [OpenAI Cookbook](https://github.com/openai/openai-cookbook)

## 许可证

本项目仅供学习和演示使用。
