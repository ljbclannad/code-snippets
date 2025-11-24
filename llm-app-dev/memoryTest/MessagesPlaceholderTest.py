import sys
from pathlib import Path
from langchain_classic.memory import ConversationBufferMemory
from langchain_core.prompts import ChatPromptTemplate,MessagesPlaceholder

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from langchainTest.baseChatModel import BaseChatModel


memory = ConversationBufferMemory(return_messages=True)
memory.save_context({"input": "我的名字是LJB"}, {"output": "好的！"})

prompt = ChatPromptTemplate.from_messages([
    ("system", "你是一个乐于助人的助手"),
    MessagesPlaceholder(variable_name="chat_history"),
    ("human", "{input}"),
])

chain = prompt |  BaseChatModel().client

chat_history = memory.load_memory_variables({})["history"]
input = "你好！把我的名字转换成中文"
response = chain.invoke({"input": input, "chat_history": chat_history})
print(response.content)
memory.save_context({"input": input}, {"output": response.content})

#再次对话，回顾上一次对话内容
input = "我上一个跟你对话都说了些啥，请重复一遍"
chat_history = memory.load_memory_variables({})["history"]
response = chain.invoke({"input": input, "chat_history": chat_history})
print(response.content)
