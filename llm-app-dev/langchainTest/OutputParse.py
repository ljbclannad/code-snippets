from baseChatModel import BaseChatModel
from langchain_core.output_parsers import CommaSeparatedListOutputParser
from langchain_core.prompts import ChatPromptTemplate


chat_model = BaseChatModel()
prompt = ChatPromptTemplate.from_messages([
    ("system", "{parser_instructions}"),
    ("human", "列出5个{subject}色系的十六进制颜色码。")
])

output_parser = CommaSeparatedListOutputParser()
parser_instructions = output_parser.get_format_instructions()
print(parser_instructions)

prompt_messages = prompt.invoke(
    {"parser_instructions": parser_instructions, "subject": "红色"}
)
response = chat_model.invoke(prompt_messages)
print(response.content)