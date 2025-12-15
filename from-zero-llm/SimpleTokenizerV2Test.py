from typing import Any
from SimpleTokenizer import SimpleTokenizerV2

import urllib.request
import re
url = ("https://raw.githubusercontent.com/rasbt/"
       "LLMs-from-scratch/main/ch02/01_main-chapter-code/"
       "the-verdict.txt")
file_path = "the-verdict.txt"
urllib.request.urlretrieve(url, file_path)

with open("the-verdict.txt", "r", encoding="utf-8") as f:
    raw_text = f.read()

preprocessed = re.split(r'([,.:;?_!"()\']|--|\s)', raw_text)
preprocessed = [item.strip() for item in preprocessed if item.strip()]
print(len(preprocessed))
print(preprocessed[:30])

# 创建词汇表(过滤相同单词)
all_words = sorted(set[str | Any](preprocessed))
all_words.append("<|endoftext|>") # 添加结束符
all_words.append("<|unk|>") # 添加未知符
vocab = {token:integer for integer, token in enumerate[str | Any](all_words)}
print(len(vocab.items()))

# 通过词汇表创建分词器
tokenizer = SimpleTokenizerV2(vocab)

# 分词器获取token列表
text1 = "Hello, do you like tea?"
text2 = "In the sunlit terraces of the palace."
text = " <|endoftext|> ".join((text1, text2))
print(text)
ids = tokenizer.encode(text)
print(ids)

# 分词器将token列表转换为文本
text = tokenizer.decode(ids)
print(text)