from baseChatModel import BaseChatModel
from langchain_core.prompts import FewShotChatMessagePromptTemplate, ChatPromptTemplate

chat_model = BaseChatModel()
few_shot_template = FewShotChatMessagePromptTemplate(
    example_prompt=ChatPromptTemplate.from_messages([
        ("system", "你是一位专业的翻译，能够将{input_language}翻译成{output_language}，并且输出文本会根据用户要求的任何语言风格进行调整。请只输出翻译后的文本，不要有任何其它内容。"),
        ("human", "你好！！我是{input_name}"),
    ]),
    examples=[
           {
            "input_language":"中文",
            "output_language":"法语",
            "input_name":"小明"
           }
    ]
)

few_shot_messages = few_shot_template.invoke(
        {"input_language":"中文","output_language":"德语","input_name":"小红"}
)
response = chat_model.invoke(few_shot_messages)
print(response.content)