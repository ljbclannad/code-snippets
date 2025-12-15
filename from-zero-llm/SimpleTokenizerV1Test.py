from typing import Any
from SimpleTokenizer import SimpleTokenizerV1

import urllib.request
import re
url = ("https://raw.githubusercontent.com/rasbt/"
       "LLMs-from-scratch/main/ch02/01_main-chapter-code/"
       "the-verdict.txt")
file_path = "the-verdict.txt"
urllib.request.urlretrieve(url, file_path)

with open("the-verdict.txt", "r", encoding="utf-8") as f:
    raw_text = f.read()
print("Total number of character:", len(raw_text))
print(raw_text[:99])

# 预处理，对文件内容进行分割，分割成单词
preprocessed = re.split(r'([,.:;?_!"()\']|--|\s)', raw_text)
preprocessed = [item.strip() for item in preprocessed if item.strip()]
print(len(preprocessed))
print(preprocessed[:30])

# 创建词汇表(过滤相同单词)
all_words = sorted(set[str | Any](preprocessed))
vocab = {token:integer for integer, token in enumerate[str | Any](all_words)}

# 通过词汇表创建分词器
tokenizer = SimpleTokenizerV1(vocab)

# 分词器获取token列表
text = """"It's the last he painted, you know,"
       Mrs. Gisburn said with pardonable pride."""
ids = tokenizer.encode(text)
print(ids)

# 分词器将token列表转换为文本
text = tokenizer.decode(ids)
print(text)

# 报错，提示KeyError: 'Hello'，因为Hello不在词汇表中
text = "Hello,do you like tea"
ids = tokenizer.encode(text)
print(ids)

