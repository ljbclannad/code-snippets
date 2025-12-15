import os,json

with open("encoder.json","r") as f:
    encoder = json.load(f)

with open("vocab.bpe","r",encoding="utf-8") as f:
    bpe_data = f.read()
bpe_merge = [tuple(merge.split()) for merge in bpe_data.split("\n")[1:-1]]
print(bpe_merge)

#提供了特殊的文件分隔符
"""
<|endoftext|>
<|startoftext|>
<|unk|>
<|pad|>
<|mask|>
"""



