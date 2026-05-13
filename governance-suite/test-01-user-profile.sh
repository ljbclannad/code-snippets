#!/bin/bash

echo "测试 1: 用户画像服务"
echo "====================="

# 发送 RabbitMQ 消息请求用户画像
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
response_queue_name = f'user.profile.response.{correlation_id}'
channel.queue_declare(queue=response_queue_name, exclusive=True, auto_delete=True)
channel.queue_bind(
    exchange='user.profile.exchange',
    queue=response_queue_name,
    routing_key=f'user.profile.response.{correlation_id}'
)

# 发送请求
request = {
    "correlationId": correlation_id,
    "customerId": "test-customer-001",
    "timestamp": int(time.time() * 1000)
}

channel.basic_publish(
    exchange='user.profile.exchange',
    routing_key='user.profile.request',
    body=json.dumps(request),
    properties=pika.BasicProperties(
        content_type='application/json',
        correlation_id=correlation_id
    )
)

print(f"✓ 已发送用户画像请求: {correlation_id}")

# 等待响应（设置超时）
response_received = False

def on_response(ch, method, props, body):
    global response_received
    response = json.loads(body)
    print(f"✓ 收到响应: success={response['success']}")
    if response['success']:
        print(f"  用户: {response['data']['basicInfo']['name']}")
        print(f"  年龄: {response['data']['basicInfo']['age']}")
        print(f"  BMI: {response['data']['healthProfile']['bmi']}")
        print(f"  全局记忆数: {len(response['data']['globalMemories'])}")
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
