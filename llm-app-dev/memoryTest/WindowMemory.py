"""演示 ConversationBufferWindowMemory 只保留最近 k 轮对话的用法。"""

import sys
from pathlib import Path

from langchain_classic.memory import ConversationBufferWindowMemory
from langchain_classic.chains import ConversationChain

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from langchainTest.baseChatModel import BaseChatModel

model = BaseChatModel().client

memory = ConversationBufferWindowMemory(k=2,return_messages=True)
conversation = ConversationChain(llm=model,memory=memory)

conversation.invoke({"input": "你好！我是LJB"})
conversation.invoke({"input": "我出生在宁波"})
conversation.invoke({"input": "居住在杭州"})
print(conversation.invoke({"input": "我上一个跟你对话都说了些啥，请重复一遍"}))