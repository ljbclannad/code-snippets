import os

from openai import OpenAI

client = OpenAI(
    api_key=os.environ.get("DEEPSEEK_API_KEY"),
    base_url="https://api.deepseek.com",
)
# response = client.chat.completions.create(
#     model="deepseek-chat",
#     messages=[{"role": "user", "content": "What is the capital of France?"}],
#     max_tokens=100,
#     temperature=1,
#     frequency_penalty=0,  # 根据 token 已出现的次数动态扣分，减轻重复措辞
#     presence_penalty=0,   # 只要 token 出现过就扣分，鼓励模型引入新主题
# )
# print(response.choices[0].message.content)

# 获取openai响应
def get_openai_response(client,system_prompt,prompt):
    response = client.chat.completions.create(
        model="deepseek-chat",
        messages=
        [{"role": "system", "content": system_prompt},
        {"role": "user", "content": prompt}
        ],
    )
    return response.choices[0].message.content

print(get_openai_response(client,"What is the capital of France?")) 