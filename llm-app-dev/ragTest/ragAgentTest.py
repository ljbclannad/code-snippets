import os
import faiss
import bs4
from langchain_core import tools
from langchain_openai.chat_models import ChatOpenAI
from langchain_community.embeddings import DashScopeEmbeddings
from langchain_community.vectorstores import FAISS
from langchain_community.docstore.in_memory import InMemoryDocstore
from langchain_text_splitters import RecursiveCharacterTextSplitter
from langchain_community.document_loaders import WebBaseLoader
from langchain_core.tools import tool
from langchain.agents import create_agent


embeddings = DashScopeEmbeddings(
    model="text-embedding-v4",
)

chat_model = ChatOpenAI(
    api_key=os.getenv("DEEPSEEK_API_KEY"),
    model="deepseek-chat",
    base_url="https://api.deepseek.com",
)

bs4_strainer = bs4.SoupStrainer(class_=("post-title", "post-header", "post-content"))
loader = WebBaseLoader(
    web_paths=("https://lilianweng.github.io/posts/2023-06-23-agent/",),
    bs_kwargs={"parse_only": bs4_strainer},
)
docs = loader.load()
# print(docs[0].page_content[:500])

text_splitter = RecursiveCharacterTextSplitter(
    chunk_size=1000, chunk_overlap=200,add_start_index=True
)
all_splits = text_splitter.split_documents(docs)
vector_1 = embeddings.embed_query(all_splits[0].page_content)

index = faiss.IndexFlatL2(len(vector_1))
vector_store = FAISS(
    embedding_function=embeddings,
    index=index,
    docstore=InMemoryDocstore(),
    index_to_docstore_id={},
)
document_ids = vector_store.add_documents(documents=all_splits)
print(document_ids[:3])

retriever = vector_store.as_retriever(
    search_type="similarity",
    search_kwargs={"k": 1},
)

''' 
1.	定义一个工具 retrieve_context：
	•	输入：用户 query
	•	行为：从向量库检索相关博客内容（k=1）
	•	输出：
	•	序列化的文本（给模型当上下文）
	•	原始文档对象列表（artifact，程序自己用）
'''
@tool(response_format="content_and_artifact")
def retrieve_context(query: str):
    """Retrieve information to help answer a query."""
    retrieved_docs = vector_store.similarity_search(query, k=1)
    serialized = "\n\n".join(
        (f"Source: {doc.metadata}\nContent: {doc.page_content}")
        for doc in retrieved_docs
    )
    return serialized, retrieved_docs

''' 2. 定义工具列表 tools '''
tools = [retrieve_context]    

''' 3. 定义系统提示 prompt '''
prompt = (
    "You have access to a tool that retrieves context from a blog post. "
    "Use the tool to help answer user queries."
)
''' 4. 创建一个agent '''
agent = create_agent(
    model=chat_model,
    tools=tools,
    system_prompt=prompt,
)

''' 5. 构建用户问题 '''
query = (
    "What is the standard method for Task Decomposition?\n\n"
    "Once you get the answer, look up common extensions of that method."
)

''' 6. 流式调用agent '''
for event in agent.stream(
    {"messages": [{"role": "user", "content": query}]},
    stream_mode="values",
):
    event["messages"][-1].pretty_print()