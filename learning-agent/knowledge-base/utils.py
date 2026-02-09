import json
import math
import re
from typing import Dict, Iterable, List


TOKEN_RE = re.compile(r"[a-z0-9\u4e00-\u9fff]+", re.IGNORECASE)


def tokenize(text: str) -> List[str]:
    return TOKEN_RE.findall(text.lower())


def load_notes(path: str) -> List[dict]:
    notes: List[dict] = []
    with open(path, "r", encoding="utf-8") as file:
        for line in file:
            line = line.strip()
            if not line:
                continue
            notes.append(json.loads(line))
    return notes


def build_tfidf(notes: List[dict]) -> tuple:
    tokenized: List[List[str]] = []
    doc_freq: Dict[str, int] = {}
    for note in notes:
        tokens = tokenize(note.get("text", ""))
        tokenized.append(tokens)
        for token in set(tokens):
            doc_freq[token] = doc_freq.get(token, 0) + 1

    doc_count = max(len(notes), 1)
    idf = {
        token: math.log((1 + doc_count) / (1 + freq)) + 1.0
        for token, freq in doc_freq.items()
    }

    vectors: List[Dict[str, float]] = []
    norms: List[float] = []
    for tokens in tokenized:
        tf: Dict[str, int] = {}
        for token in tokens:
            tf[token] = tf.get(token, 0) + 1
        vec = {token: tf[token] * idf[token] for token in tf}
        norm = math.sqrt(sum(value * value for value in vec.values()))
        vectors.append(vec)
        norms.append(norm)

    return idf, vectors, norms, tokenized


def build_query_vector(tokens: Iterable[str], idf: Dict[str, float]) -> Dict[str, float]:
    tf: Dict[str, int] = {}
    for token in tokens:
        if token in idf:
            tf[token] = tf.get(token, 0) + 1
    return {token: tf[token] * idf[token] for token in tf}


def sparse_dot(vec_a: Dict[str, float], vec_b: Dict[str, float]) -> float:
    if len(vec_a) > len(vec_b):
        vec_a, vec_b = vec_b, vec_a
    return sum(value * vec_b.get(token, 0.0) for token, value in vec_a.items())


def vector_norm(vec: Dict[str, float]) -> float:
    return math.sqrt(sum(value * value for value in vec.values()))
