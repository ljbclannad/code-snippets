from baseChatModel import BaseChatModel
from langchain_core.output_parsers import JsonOutputParser
from langchain_core.prompts import ChatPromptTemplate
from pydantic import BaseModel, Field
from typing import List

chat_model = BaseChatModel()

class BookInfo(BaseModel):
    book_name: str = Field(description="书籍的名字", example="百年孤独")
    author_name: str = Field(description="书籍的作者", example="加西亚·马尔克斯")
    genres: List[str] = Field(description="书籍的体裁", example=["小说", "文学"])

# 定义输出解析器
output_parser = JsonOutputParser(pydantic_object=BookInfo)

# 获取输出解析器的格式化指令
print(output_parser.get_format_instructions())

prompt = ChatPromptTemplate.from_messages([
    ("system", "{parser_instructions} 你输出的结果请使用中文。"),
    ("human", "请你帮我从书籍概述中，提取书名、作者，以及书籍的体裁。书籍概述会被三个#符号包围。\n###{book_introduction}###")
])

book_introduction = """《明朝那些事儿》，作者是当年明月。2006年3月在天涯社区首次发表，2009年3月21日连载完毕，边写作边集结成书出版发行，一共7本。
《明朝那些事儿》主要讲述的是从1344年到1644年这三百年间关于明朝的一些故事。以史料为基础，以年代和具体人物为主线，并加入了小说的笔法，语言幽默风趣。对明朝十六帝和其他王公权贵和小人物的命运进行全景展示，尤其对官场政治、战争、帝王心术着墨最多，并加入对当时政治经济制度、人伦道德的演义。
它以一种网络语言向读者娓娓道出三百多年关于明朝的历史故事、人物。其中原本在历史中陌生、模糊的历史人物在书中一个个变得鲜活起来。《明朝那些事儿》为读者解读历史中的另一面，让历史变成一部活生生的生活故事。
"""
final_prompt = prompt.invoke({"book_introduction": book_introduction, 
                              "parser_instructions": output_parser.get_format_instructions()})

response = chat_model.invoke(final_prompt)
print(response.content)

