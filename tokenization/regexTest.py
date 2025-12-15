import regex as re

"""
    合并一些输入,比如dog. dog! dog?
"""
text = "Hello've, world123! This is a test."
gpt2pat = re.compile(r"""'s|'t|'re|'ve|'m|'ll|'d| ?\p{L}+| ?\p{N}+| ?[^\s\p{L}\p{N}]+|\s+(?!\S)|\s+""")

result = re.findall(gpt2pat,text)
#将result里面的每个元素进行Bpe操作
print(result)
 
