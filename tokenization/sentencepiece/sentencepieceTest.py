import sentencepiece as spm

# 1. 准备训练数据（写入临时文件）
with open("spm_input.txt", "w", encoding="utf-8") as f:
    f.write("我爱自然语言处理。\n")
    f.write("自然语言处理是人工智能的一个分支。\n")
    f.write("深度学习推动了自然语言处理的发展。\n")

# 2. 训练 SentencePiece 模型（Unigram 模型）
spm.SentencePieceTrainer.train(
    input='spm_input.txt',
    model_prefix='spm_model',          # 输出文件前缀：spm_model.model, spm_model.vocab
    vocab_size=36,                     # 词表大小，
    model_type='bpe',              # 可选：unigram, bpe, char, word
    character_coverage=1.0,            # 中文设为 1.0
    pad_id=0,
    unk_id=1,
    bos_id=2,
    eos_id=3,
    user_defined_symbols=["[CLS]", "[SEP]"]  # 可选添加自定义 token
)

# 3. 加载模型
sp = spm.SentencePieceProcessor()
sp.load("spm_model.model")

# 4. 测试文本编码
text = "我爱自然语言处理"
print("原始文本:", text)

# 编码成子词
tokens = sp.encode(text, out_type=str)
print("子词编码:", tokens)

# 编码成ID
token_ids = sp.encode(text, out_type=int)
print("Token IDs:", token_ids)

# 解码回原文
decoded_text = sp.decode(token_ids)
print("解码回原文:", decoded_text)

# 打印词表前10个
print("\n词表前10个词：")
for i in range(10):
    print(f"{i}: {sp.id_to_piece(i)}")