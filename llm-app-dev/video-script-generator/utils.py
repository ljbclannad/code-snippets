import sys
from pathlib import Path
from langchain_community.utilities import WikipediaAPIWrapper

from langchain_core.prompts import ChatPromptTemplate

# 允许直接从 repo 根目录外执行脚本时解析到内部模块
PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from langchainTest.baseChatModel import BaseChatModel


def generate_script(subject,video_length):
    tiltle_prompt = ChatPromptTemplate.from_messages([
        ("system", "你是一位专业的视频脚本生成器，请根据用户输入的主题、视频长度、创造力和API密钥生成一个视频脚本。"),
        ("human", "请问'{subject}'这个主题的视频想一个吸引人的标题")
    ])

    script_prompt = ChatPromptTemplate.from_messages([
        ("human",
             """你是一位短视频频道的博主。根据以下标题和相关信息，为短视频频道写一个视频脚本。
             视频标题：{title}，视频时长：{duration}分钟，生成的脚本的长度尽量遵循视频时长的要求。
             要求开头抓住限球，中间提供干货内容，结尾有惊喜，脚本格式也请按照【开头、中间，结尾】分隔。
             整体内容的表达方式要尽量轻松有趣，吸引年轻人。
             脚本内容可以结合以下维基百科搜索出的信息，但仅作为参考，只结合相关的即可，对不相关的进行忽略：
             ```{wikipedia_search}```""")
    ])

    wikipedia = WikipediaAPIWrapper(lang="zh")

    chat_model = BaseChatModel().client
    title_chain = tiltle_prompt | chat_model
    script_chain = script_prompt | chat_model

    title = title_chain.invoke({"subject": subject}).content
    wikipedia_search = wikipedia.run(subject) if subject else ""
    script = script_chain.invoke(
        {
            "title": title,
            "duration": video_length,
            "wikipedia_search": wikipedia_search,
        }
    ).content

    return {
        "title": title,
        "script": script,
        "wikipedia_search": wikipedia_search,
    }

print(generate_script("sora模型",1))