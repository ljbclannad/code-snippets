from transformers import AutoTokenizer
import os

# 获取当前脚本所在目录的路径
current_dir = os.path.dirname(os.path.abspath(__file__))

# 加载本地的 tokenizer
tokenizer = AutoTokenizer.from_pretrained(current_dir)

# 示例文本
text = "We are very happy to show you the 🤗 Transformers library"

# 对文本进行编码
encoded = tokenizer(text)

print("Input IDs:", encoded["input_ids"])
print("Attention Mask:", encoded["attention_mask"])

# 解码每个 token
tokens = tokenizer.convert_ids_to_tokens(encoded["input_ids"])
print("\n解码后的 tokens:")
for i, (token_id, token) in enumerate(zip(encoded["input_ids"], tokens)):
    print(f"Token {i}: ID = {token_id}, Token = '{token}'")

# # 解码完整文本
# decoded_text = tokenizer.decode(encoded["input_ids"])
# print("\n解码后的完整文本:")
# print(decoded_text)