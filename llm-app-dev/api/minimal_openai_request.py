from openai import OpenAI

client = OpenAI(
  base_url="https://openrouter.ai/api/v1",
  api_key="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",
)

completion = client.chat.completions.create(
  extra_body={},
  model="openai/gpt-4.1-mini",
  messages=[
    {
      "role": "user",
      "content": [
        {
          "type": "text",
          "text": "What is in this image?"
        },
        {
          "type": "image_url",
          "image_url": {
            "url": "https://live.staticflickr.com/3851/14825276609_098cac593d_b.jpg"
          }
        }
      ]
    }
  ]
)
print(completion.choices[0].message.content)