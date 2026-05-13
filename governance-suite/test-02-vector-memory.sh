#!/bin/bash

echo "测试 2: 向量记忆服务"
echo "====================="

# 测试记忆提取（通过 RabbitMQ）
echo "发送记忆提取请求..."
python3 << 'PYEOF'
import pika
import json
import uuid
import time

connection = pika.BlockingConnection(pika.ConnectionParameters('localhost'))
channel = connection.channel()

correlation_id = str(uuid.uuid4())

request = {
    "correlationId": correlation_id,
    "groupId": "test-group-001",
    "customerId": "test-customer-001",
    "userMessage": "我最近血压有点高，想控制一下饮食",
    "aiResponse": "建议您减少盐分摄入，多吃蔬菜水果，避免高脂肪食物",
    "timestamp": int(time.time() * 1000)
}

channel.basic_publish(
    exchange='memory.extraction.exchange',
    routing_key='memory.extraction',
    body=json.dumps(request),
    properties=pika.BasicProperties(
        content_type='application/json',
        correlation_id=correlation_id
    )
)

print(f"✓ 已发送记忆提取请求: {correlation_id}")
connection.close()
PYEOF

echo "✓ 记忆提取请求已发送（异步处理）"
echo ""

# 等待 10 秒让记忆提取完成
echo "等待记忆提取完成..."
sleep 10

# 测试记忆检索
echo "测试记忆检索..."
python3 << 'PYEOF'
import pika
import json
import uuid
import time
import sys

connection = pika.BlockingConnection(pika.ConnectionParameters('localhost'))
channel = connection.channel()

correlation_id = str(uuid.uuid4())

# 声明响应队列，绑定到 exchange
response_queue_name = f'vector.memory.response.{correlation_id}'
channel.queue_declare(queue=response_queue_name, exclusive=True, auto_delete=True)
channel.queue_bind(
    exchange='vector.memory.exchange',
    queue=response_queue_name,
    routing_key=f'vector.memory.response.{correlation_id}'
)

request = {
    "correlationId": correlation_id,
    "groupId": "test-group-001",
    "customerId": "test-customer-001",
    "query": "饮食建议",
    "topK": 5,
    "timestamp": int(time.time() * 1000)
}

channel.basic_publish(
    exchange='vector.memory.exchange',
    routing_key='vector.memory.request',
    body=json.dumps(request),
    properties=pika.BasicProperties(
        content_type='application/json',
        correlation_id=correlation_id
    )
)

print(f"✓ 已发送记忆检索请求: {correlation_id}")

response_received = False

def on_response(ch, method, props, body):
    global response_received
    response = json.loads(body)
    print(f"✓ 收到响应: success={response['success']}")
    if response['success']:
        print(f"  检索到 {len(response['data'])} 条记忆")
        for i, memory in enumerate(response['data'][:3]):
            print(f"  [{i+1}] {memory['content']} (相似度: {memory['similarity']:.3f})")
    else:
        print(f"  错误: {response.get('errorMessage', 'Unknown error')}")
    response_received = True
    ch.stop_consuming()

channel.basic_consume(queue=response_queue_name, on_message_callback=on_response, auto_ack=True)

# 设置12秒超时
start_time = time.time()
while not response_received and (time.time() - start_time) < 12:
    connection.process_data_events(time_limit=1)

if not response_received:
    print("✗ 超时：12秒内未收到响应")
    sys.exit(1)

connection.close()
PYEOF

echo ""
