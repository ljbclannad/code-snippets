import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from langchainTest.baseChatModel import BaseChatModel

from langchain_core.prompts import ChatPromptTemplate, MessagesPlaceholder
from langchain_core.output_parsers import StrOutputParser
from langchain_core.runnables.history import RunnableWithMessageHistory
from langchain_core.chat_history import InMemoryChatMessageHistory

# 1. 定义基本链：prompt -> llm -> parser
llm = BaseChatModel().client

prompt = ChatPromptTemplate.from_messages([
    ("system", "你是一个记忆力不错的 AI 助手。"),
    MessagesPlaceholder(variable_name="history"),  # 历史消息放这里
    ("human", "{input}"),
])

base_chain = prompt | llm | StrOutputParser()

# 2. 定义一个 session -> ChatHistory 的存储（可以换成 Redis/File/DB）
store = {}

def get_session_history(session_id: str):
    if session_id not in store:
        store[session_id] = InMemoryChatMessageHistory()
    return store[session_id]

# 3. 用 RunnableWithMessageHistory 包装
chain_with_history = RunnableWithMessageHistory(
    base_chain,
    get_session_history,
    input_messages_key="input",     # 输入中哪一个字段是“本轮用户输入”
    history_messages_key="history", # prompt 中 history 的变量名
)

# 4. 调用：通过 config 传 session_id
config = {"configurable": {"session_id": "user-1"}}

print(chain_with_history.invoke(
    {"input": "记住：我是一名 Java 后端工程师，对大模型开发很感兴趣。"},
    config=config,
))

print(chain_with_history.invoke(
    {"input": "一句话描述我是什么背景？"},
    config=config,
))