# 语句搜索
import os
import faiss
from pathlib import Path

from langchain_community.document_loaders import PyPDFLoader
from langchain_community.embeddings import DashScopeEmbeddings
from langchain_text_splitters import RecursiveCharacterTextSplitter
from langchain_community.docstore.in_memory import InMemoryDocstore
from langchain_community.vectorstores import FAISS
from openai import OpenAI


embeddings = DashScopeEmbeddings(
    model="text-embedding-v4",
)

base_model = OpenAI(
    api_key=os.getenv("DEEPSEEK_API_KEY"),
    base_url="https://api.deepseek.com",
)

BASE_DIR = Path(__file__).resolve().parent
PDF_PATH = BASE_DIR / "test.pdf"
if not PDF_PATH.exists():
    raise FileNotFoundError(f"未找到 PDF 文件：{PDF_PATH}")

# PyPDFloader loads one Document object per PDF page.
loader = PyPDFLoader(str(PDF_PATH))
docs = loader.load()
# print(len(docs))
# print(docs[0].page_content)
# print(docs[0].metadata)

# 拆分文本
# We use the RecursiveCharacterTextSplitter, 
# which will recursively split the document using common separators like new lines until each chunk is the appropriate size.
text_splitter = RecursiveCharacterTextSplitter(
    chunk_size=20, chunk_overlap=10,add_start_index=True
)
all_splits = text_splitter.split_documents(docs)

# embedding，获取向量
vector_1 = embeddings.embed_query(all_splits[0].page_content)
vector_2 = embeddings.embed_query(all_splits[1].page_content)

assert len(vector_1) == len(vector_2)
# print(f"Generated vectors of length {len(vector_1)}\n")
# print(vector_1[:10])

# 向量存储
index = faiss.IndexFlatL2(len(vector_1)) # 使用L2距离作为相似度度量
vector_store = FAISS(
    embedding_function=embeddings,
    index=index,
    docstore=InMemoryDocstore(),
    index_to_docstore_id={},
)
ids = vector_store.add_documents(documents=all_splits)
# 根据与字符串查询的相似性返回文档：返回最相似的k个文档
# results = vector_store.similarity_search(
#     "工作经历", k=2
# )

#返回分数(越接近0越相似)
# results = vector_store.similarity_search_with_score(
#     "工作经历"
# )
# doc, score = results[0]
# print(score)

# 检索器
retriever = vector_store.as_retriever(
    search_type="similarity",
    search_kwargs={"k": 1},
)
results = retriever.invoke("工作经历")
print(results)