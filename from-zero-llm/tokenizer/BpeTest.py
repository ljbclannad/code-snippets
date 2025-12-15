import tiktoken

# 获取tiktoken分词器
tokenizer = tiktoken.get_encoding("gpt2")

# 测试文本
text = (
    "Hello, do you like tea? <|endoftext|> In the sunlit terraces"
     "of someunknownPlace."
)
integers = tokenizer.encode(text, allowed_special={"<|endoftext|>"}) # 允许特殊token
print(integers)

text = tokenizer.decode(integers)
print(text)