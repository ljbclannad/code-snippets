import os
import json
import re
from typing import Any, Dict, List, Tuple, Optional

import faiss
import bs4
from langchain_openai.chat_models import ChatOpenAI
from langchain_community.embeddings import DashScopeEmbeddings
from langchain_community.vectorstores import FAISS
from langchain_community.docstore.in_memory import InMemoryDocstore
from langchain_text_splitters import RecursiveCharacterTextSplitter
from langchain_community.document_loaders import WebBaseLoader
from langchain_core.tools import tool
from langchain.agents import create_agent


# ----------------------------
# 0) Embedding / LLM (same style as your code)
# ----------------------------
embeddings = DashScopeEmbeddings(model="text-embedding-v4")

chat_model = ChatOpenAI(
    api_key=os.getenv("DEEPSEEK_API_KEY"),
    model="deepseek-chat",
    base_url="https://api.deepseek.com",
)

# ----------------------------
# 1) Build demo vector store (same data source as your code)
# ----------------------------
bs4_strainer = bs4.SoupStrainer(class_=("post-title", "post-header", "post-content"))
loader = WebBaseLoader(
    web_paths=("https://lilianweng.github.io/posts/2023-06-23-agent/",),
    bs_kwargs={"parse_only": bs4_strainer},
)
docs = loader.load()

text_splitter = RecursiveCharacterTextSplitter(
    chunk_size=1000, chunk_overlap=200, add_start_index=True
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
print("Indexed:", document_ids[:3])


# ----------------------------
# 2) Tool: retrieve_context (same idea as your code)
# ----------------------------
@tool(response_format="content_and_artifact")
def retrieve_context(query: str):
    """Retrieve information to help answer a query."""
    retrieved_docs = vector_store.similarity_search(query, k=3)
    serialized = "\n\n".join(
        (f"Source: {doc.metadata}\nContent: {doc.page_content}") for doc in retrieved_docs
    )
    return serialized, retrieved_docs


# ----------------------------
# 3) Tool: understand_medical_query (Query Understanding for medical policy QA)
# ----------------------------
def _safe_json_loads(s: str) -> Optional[Dict[str, Any]]:
    try:
        return json.loads(s)
    except Exception:
        return None

def _extract_codes(q: str) -> List[str]:
    """
    Very small heuristic: extract ICD-like and long numeric codes.
    - ICD-ish: E11, E11.9, A00.1
    - long numeric: 8-20 digits
    """
    patterns = [
        r"\b[A-Z][0-9]{2}(?:\.[0-9A-Z]{1,3})?\b",
        r"\b\d{8,20}\b",
    ]
    found: List[str] = []
    for p in patterns:
        found.extend(re.findall(p, q.upper()))
    # dedup preserve order
    seen, out = set(), []
    for x in found:
        if x not in seen:
            seen.add(x)
            out.append(x)
    return out

@tool(response_format="content_and_artifact")
def understand_medical_query(query: str):
    """
    Turn a medical/insurance question into a structured retrieval plan.
    Output: (json_string, artifact_dict)
    """
    code_hints = _extract_codes(query)

    system_prompt = (
        "你是医疗医保政策RAG系统的“查询理解(Query Understanding)”模块。"
        "你只做结构化，不要直接回答问题。"
        "必须输出严格JSON，不能带markdown、不能带多余解释。"
        "对缺失信息不要瞎编，constraints 可留空。"
    )

    user_prompt = f"""
用户问题：
{query}

请输出 JSON，字段必须齐全：
- intent: 只能取 [rule_check, process, troubleshooting, compare, definition, other]
- entities: string[]（疾病/药品/项目/机构/险种等关键实体）
- codes: string[]（ICD/收费项目编码/药品编码等；可结合 code_hints）
- constraints: object（可包含：region/city, hospital, insurance_type(职工/居民/商保), visit_type(门诊/住院/急诊),
                      population(儿童/孕产/慢病/特殊病种), policy_date(YYYY-MM或YYYY), doc_authority）
- keywords: string[]（用于BM25/关键词检索：如 支付范围/限定支付/不予支付/适应症/报销比例/起付线/封顶线/门慢门特 等）
- rewrites: string[]（3~6条，尽量补齐约束词，更适合检索）
- sub_questions: string[]（若复合问题则拆解，否则空数组）

code_hints: {code_hints}
""".strip()

    resp = chat_model.invoke(
        [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ]
    )

    raw = (resp.content or "").strip()
    data = _safe_json_loads(raw)

    # fallback to something safe if model returned non-json
    if not data:
        fallback = {
            "intent": "other",
            "entities": [],
            "codes": code_hints,
            "constraints": {},
            "keywords": [],
            "rewrites": [query],
            "sub_questions": [],
        }
        return raw, fallback

    # normalize: merge code hints
    codes = data.get("codes") if isinstance(data.get("codes"), list) else []
    merged, seen = [], set()
    for x in (codes or []) + code_hints:
        if isinstance(x, str) and x and x not in seen:
            seen.add(x)
            merged.append(x)
    data["codes"] = merged

    # ensure required fields exist
    data.setdefault("intent", "other")
    data.setdefault("entities", [])
    data.setdefault("constraints", {})
    data.setdefault("keywords", [])
    data.setdefault("rewrites", [query])
    data.setdefault("sub_questions", [])

    return json.dumps(data, ensure_ascii=False), data


def pick_retrieval_queries(uq: Dict[str, Any], max_q: int = 4) -> List[str]:
    """
    Simple strategy:
    1) use rewrites first
    2) add one keywords query
    3) add one codes query
    """
    queries: List[str] = []
    for q in uq.get("rewrites", [])[:max_q]:
        if q and q not in queries:
            queries.append(q)

    kw = uq.get("keywords", [])
    if kw:
        q = " ".join(kw[:10])
        if q and q not in queries and len(queries) < max_q:
            queries.append(q)

    codes = uq.get("codes", [])
    if codes:
        q = " ".join(codes[:6])
        if q and q not in queries and len(queries) < max_q:
            queries.append(q)

    return queries[:max_q]


# ----------------------------
# 4) Agent wiring: enforce "understand -> retrieve -> answer"
# ----------------------------
tools = [understand_medical_query, retrieve_context]

prompt = (
    "你是医疗政策问答RAG助手。"
    "流程必须是：先调用 understand_medical_query 获取结构化结果；"
    "再根据 rewrites/keywords 选择合适查询调用 retrieve_context 获取证据；"
    "最后基于证据回答。"
    "如果证据不足，明确说明“证据不足/需要补充地区/版本/门诊住院等约束”。"
)

agent = create_agent(
    model=chat_model,
    tools=tools,
    system_prompt=prompt,
)


# ----------------------------
# 5) Demo query (medical style)
# ----------------------------
query = (
    "上海职工医保，门诊慢病糖尿病患者开二甲双胍是否能报销？"
    "如果结算失败，常见原因有哪些？"
)

# (A) You can also run Query Understanding standalone:
print("\n--- Standalone Query Understanding ---")
_, uq = understand_medical_query(query)
print(json.dumps(uq, ensure_ascii=False, indent=2))

print("\n--- Standalone Retrieval with rewrites/keywords ---")
for q in pick_retrieval_queries(uq):
    ctx, _docs = retrieve_context(q)
    print(f"\n[Retrieval Query] {q}\n{ctx[:600]}...\n")

# (B) Agent streaming (tool calling)
print("\n--- Agent Stream ---")
for event in agent.stream(
    {"messages": [{"role": "user", "content": query}]},
    stream_mode="values",
):
    event["messages"][-1].pretty_print()