import os
from typing import Optional

from langchain_openai import ChatOpenAI


class BaseChatModel:
    """
    负责初始化 LangChain ChatOpenAI 客户端的基础类，可在本目录的其他模块中复用。

    Attributes
    ----------
    client : ChatOpenAI
        预配置好的 LangChain ChatOpenAI 对象。
    """

    def __init__(
        self,
        model_name: str = "deepseek-chat",
        api_key: Optional[str] = os.environ.get("DEEPSEEK_API_KEY"),
        base_url: str = "https://api.deepseek.com",
        max_completion_tokens: int = 200,
    ) -> None:
        self.client = ChatOpenAI(
            model=model_name,
            api_key=api_key,
            base_url=base_url,
            max_completion_tokens=max_completion_tokens,
        )

    def invoke(self, messages, **kwargs):
        """
        直接调用底层 ChatOpenAI 对象，保持与 LangChain 接口兼容。

        Parameters
        ----------
        messages : Sequence[BaseMessage]
            LangChain 消息列表。
        kwargs : dict
            透传给 ChatOpenAI.invoke 的其他参数。
        """
        return self.client.invoke(messages, **kwargs)
