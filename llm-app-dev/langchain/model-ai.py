import os
from langchain_openai import ChatOpenAI
from langchain_core.messages import SystemMessage, HumanMessage

model = ChatOpenAI(model="deepseek-chat",
api_key=os.environ.get("DEEPSEEK_API_KEY"),
base_url="https://api.deepseek.com",
max_completion_tokens=200
)


system_prompt = "You are a helpful assistant."
human_prompt = "What is the capital of France?"

messages = [
    SystemMessage(content=system_prompt),
    HumanMessage(content=human_prompt)
]

response = model.invoke(messages)

print(response.content)