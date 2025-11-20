from langchain_core.prompts import SystemMessagePromptTemplate, HumanMessagePromptTemplate, ChatPromptTemplate,AIMessagePromptTemplate
from baseChatModel import BaseChatModel

chat_model = BaseChatModel()

system_template_text="你是一位专业的翻译，能够将{input_language}翻译成{output_language}，并且输出文本会根据用户要求的任何语言风格进行调整。请只输出翻译后的文本，不要有任何其它内容。"
human_template_text="你好！！我是{input_name}"
"""
system_prompt_template = SystemMessagePromptTemplate.from_template(system_template_text)
system_prompt = system_prompt_template.format(input_language="中文", output_language="法语")

human_prompt_template = HumanMessagePromptTemplate.from_template(human_template_text)
human_prompt = human_prompt_template.format(input_name="小明")

input_variables = [
    {"input_language":"中文",
    "output_language":"法语",
    "input_name":"小明"},
    {"input_language":"中文",
    "output_language":"英语",
    "input_name":"小明"}
]

for input in input_variables:
    system_prompt = system_prompt_template.format(input_language=input["input_language"],output_language=input["output_language"])
    human_prompt = human_prompt_template.format(input_name=input["input_name"])
    messages = [
        system_prompt,
        human_prompt
    ]
    response = chat_model.invoke(messages)
    print(response.content)
"""

prompt_template = ChatPromptTemplate.from_messages([
    ("system", system_template_text),
    ("human", human_template_text)
])
prompt_messages = prompt_template.invoke(
    [
        ("input_language","中文"),
        ("output_language","法语"),
        ("input_name","小明")
    ]
)
response = chat_model.invoke(prompt_messages)
print(response.content)