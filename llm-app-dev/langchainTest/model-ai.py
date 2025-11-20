from langchain_core.messages import SystemMessage, HumanMessage

from baseChatModel import BaseChatModel

chat_model = BaseChatModel()


system_prompt = "You are a helpful assistant."
human_prompt = "What is the capital of France?"

messages = [
    SystemMessage(content=system_prompt),
    HumanMessage(content=human_prompt)
]

response = chat_model.invoke(messages)

print(response.content)