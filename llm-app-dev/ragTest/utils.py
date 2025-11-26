import os
from dotenv import load_dotenv

from langchain_openai import ChatOpenAI, OpenAIEmbeddings
from langchain_community.document_loaders import PyPDFLoader
from langchain_community.vectorstores import FAISS
from langchain_text_splitters import RecursiveCharacterTextSplitter

# 这里改成 classic
from langchain_classic.chains import (
    create_history_aware_retriever,
    create_retrieval_chain,
)
from langchain_classic.chains.combine_documents import create_stuff_documents_chain

from langchain_core.prompts import ChatPromptTemplate, MessagesPlaceholder
from langchain_core.runnables.history import RunnableWithMessageHistory
from langchain_core.chat_history import InMemoryChatMessageHistory

load_dotenv()

# 全局会话历史存储：session_id -> ChatHistory
_session_store = {}


def _get_session_history(session_id: str) -> InMemoryChatMessageHistory:
    """给 RunnableWithMessageHistory 用的会话历史工厂。"""
    if session_id not in _session_store:
        _session_store[session_id] = InMemoryChatMessageHistory()
    return _session_store[session_id]


def qa_agent(session_id: str, upload_file, question: str):
    """
    使用最新 LangChain 写法的 PDF RAG + 对话历史。
    session_id：用来区分不同用户/会话
    upload_file：前端传进来的 PDF 文件对象
    question：当前问题
    """

    # 1. DeepSeek LLM（OpenAI 兼容）
    llm = ChatOpenAI(
        api_key=os.getenv("DEEPSEEK_API_KEY"),
        model="deepseek-chat",
        base_url="https://api.deepseek.com",  # 官方推荐 base_url [oai_citation:0‡api-docs.deepseek.com](https://api-docs.deepseek.com/?utm_source=chatgpt.com)
        temperature=0,
    )

    # 2. 保存上传 PDF 到临时文件
    file_content = upload_file.read()
    temp_file_path = f"temp_{upload_file.name}"
    with open(temp_file_path, "wb") as f:
        f.write(file_content)

    # 3. 加载 & 切分文档
    loader = PyPDFLoader(temp_file_path)
    docs = loader.load()

    text_splitter = RecursiveCharacterTextSplitter(
        chunk_size=1000,
        chunk_overlap=200,
    )
    splits = text_splitter.split_documents(docs)

    # 4. DeepSeek embedding + FAISS 向量库
    embeddings = OpenAIEmbeddings(
        api_key=os.getenv("DEEPSEEK_API_KEY"),
        model="deepseek-embedding-v2",      # DeepSeek 官方 embedding 模型 [oai_citation:1‡api.python.langchain.com](https://api.python.langchain.com/en/latest/langchain/chains/langchain.chains.conversational_retrieval.base.ConversationalRetrievalChain.html?utm_source=chatgpt.com)
        base_url="https://api.deepseek.com",
    )

    vectorstore = FAISS.from_documents(splits, embeddings)
    retriever = vectorstore.as_retriever()

    # 5. 历史感知检索器（推荐替代 ConversationalRetrievalChain 内部逻辑） [oai_citation:2‡api.python.langchain.com](https://api.python.langchain.com/en/latest/langchain/chains/langchain.chains.history_aware_retriever.create_history_aware_retriever.html?utm_source=chatgpt.com)
    contextualize_system_prompt = (
        "你是一个负责生成检索查询的助手。\n"
        "给定聊天历史 chat_history 和用户当前问题 input，"
        "生成一个独立、完整的检索 query。\n"
        "如果当前问题已经是完整问题，就直接返回。"
    )

    contextualize_prompt = ChatPromptTemplate.from_messages(
        [
            ("system", contextualize_system_prompt),
            MessagesPlaceholder("chat_history"),
            ("human", "{input}"),
        ]
    )

    history_aware_retriever = create_history_aware_retriever(
        llm=llm,
        retriever=retriever,
        prompt=contextualize_prompt,
    )

    # 6. 回答 Prompt：把检索结果 + 历史 + 当前问题一起喂给 LLM
    qa_system_prompt = (
        "你是一个基于文档回答问题的助手。\n"
        "请严格根据提供的 context 回答问题，"
        "如果 context 中没有答案，就说“我在文档里没有找到相关信息”。\n\n"
        "{context}"
    )

    qa_prompt = ChatPromptTemplate.from_messages(
        [
            ("system", qa_system_prompt),
            MessagesPlaceholder("chat_history"),
            ("human", "{input}"),
        ]
    )

    question_answer_chain = create_stuff_documents_chain(
        llm=llm,
        prompt=qa_prompt,
    )

    # 7. 组装成 RAG 链（不带 memory 的版本）
    rag_chain = create_retrieval_chain(
        history_aware_retriever,
        question_answer_chain,
    )

    # 8. 用 RunnableWithMessageHistory 给链外挂“对话记忆” [oai_citation:3‡api.python.langchain.com](https://api.python.langchain.com/en/latest/character/langchain_text_splitters.character.RecursiveCharacterTextSplitter.html?utm_source=chatgpt.com)
    conversational_rag_chain = RunnableWithMessageHistory(
        rag_chain,
        _get_session_history,
        input_messages_key="input",        # 输入 dict 里问题的字段名
        history_messages_key="chat_history",
    )

    # 9. 调用：通过 config 传 session_id，history 自动管理
    config = {"configurable": {"session_id": session_id}}

    result = conversational_rag_chain.invoke(
        {"input": question},
        config=config,
    )

    # result 里一般包含：
    # - "answer": 最终回答
    # - "context": 检索到的 Document 列表
    return result