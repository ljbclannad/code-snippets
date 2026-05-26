# AI 记忆功能实施计划 - Plan 5: 集成测试和部署

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 完成端到端集成测试，验证所有服务协同工作，部署到完整环境

**Architecture:** 完整的 3 服务协同测试，包括 RabbitMQ 消息流、Milvus 向量检索、Dify API 调用

**预计时间**: 2-3 小时

---

## Task 1: 准备测试环境

**Files:**
- Update: `docker-compose.yml`
- Create: `test-data.sql`

- [ ] **Step 1: 更新 docker-compose.yml**

确保包含所有必需的基础设施：
- MySQL
- Redis
- RabbitMQ
- Nacos
- Milvus (Standalone)

- [ ] **Step 2: 启动所有基础设施**

```bash
docker-compose up -d
```

- [ ] **Step 3: 等待服务就绪**

```bash
# 等待 MySQL
until docker exec governance-mysql mysqladmin ping -h localhost --silent; do
    echo "等待 MySQL..."
    sleep 2
done

# 等待 Milvus
until curl -s http://localhost:9091/healthz | grep -q "OK"; do
    echo "等待 Milvus..."
    sleep 2
done

# 等待 RabbitMQ
until curl -s http://localhost:15672/api/health/checks/alarms | grep -q "ok"; do
    echo "等待 RabbitMQ..."
    sleep 2
done
```

- [ ] **Step 4: 创建测试数据**

```bash
cat > test-data.sql << 'SQLEOF'
-- 插入测试用户
INSERT INTO t_customer (customer_id, name, age, gender, phone, email, create_time, update_time, is_deleted)
VALUES ('test-customer-001', '张三', 35, '男', '13800138000', 'zhangsan@example.com', NOW(), NOW(), 0);

-- 插入健康评估数据
INSERT INTO t_health_assessment (customer_id, bmi, blood_pressure, blood_sugar, assessment_date, health_goals, create_time, is_deleted)
VALUES ('test-customer-001', 24.5, '120/80', 5.2, '2026-05-01', '减重5kg,改善睡眠', NOW(), 0);

-- 插入全局记忆
INSERT INTO b_ai_user_memory (customer_id, memory_type, memory_key, memory_content, confidence_score, source, verified, create_time, update_time, is_deleted)
VALUES 
('test-customer-001', 'preference', 'diet', '喜欢清淡饮食，不吃辣', 0.95, 'chat', 1, NOW(), NOW(), 0),
('test-customer-001', 'health_condition', 'allergy', '对海鲜过敏', 0.98, 'chat', 1, NOW(), NOW(), 0);
SQLEOF

docker exec -i governance-mysql mysql -uroot -proot governance < test-data.sql
```

---

## Task 2: 启动所有微服务

**Files:**
- Create: `start-ai-services.sh`

- [ ] **Step 1: 创建启动脚本**

```bash
cat > start-ai-services.sh << 'BASHEOF'
#!/bin/bash

echo "启动 AI 记忆功能相关服务..."

# 启动 user-profile-service
echo "启动 user-profile-service (端口 8101)..."
mvn -pl user-profile-service spring-boot:run > logs/user-profile-service.log 2>&1 &
USER_PROFILE_PID=$!
echo "user-profile-service PID: $USER_PROFILE_PID"

# 等待 5 秒
sleep 5

# 启动 vector-memory-service
echo "启动 vector-memory-service (端口 8102)..."
mvn -pl vector-memory-service spring-boot:run > logs/vector-memory-service.log 2>&1 &
VECTOR_MEMORY_PID=$!
echo "vector-memory-service PID: $VECTOR_MEMORY_PID"

# 等待 5 秒
sleep 5

# 启动 ai-chat-service
echo "启动 ai-chat-service (端口 8103)..."
mvn -pl ai-chat-service spring-boot:run > logs/ai-chat-service.log 2>&1 &
AI_CHAT_PID=$!
echo "ai-chat-service PID: $AI_CHAT_PID"

echo ""
echo "所有服务已启动，PID 信息："
echo "  user-profile-service: $USER_PROFILE_PID"
echo "  vector-memory-service: $VECTOR_MEMORY_PID"
echo "  ai-chat-service: $AI_CHAT_PID"
echo ""
echo "等待服务完全启动（约 30 秒）..."
sleep 30

echo "检查服务健康状态..."
curl -s http://localhost:8101/actuator/health | grep -q "UP" && echo "✓ user-profile-service 健康" || echo "✗ user-profile-service 异常"
curl -s http://localhost:8102/actuator/health | grep -q "UP" && echo "✓ vector-memory-service 健康" || echo "✗ vector-memory-service 异常"
curl -s http://localhost:8103/actuator/health | grep -q "UP" && echo "✓ ai-chat-service 健康" || echo "✗ ai-chat-service 异常"

echo ""
echo "服务启动完成！"
BASHEOF

chmod +x start-ai-services.sh
```

- [ ] **Step 2: 执行启动脚本**

```bash
./start-ai-services.sh
```

- [ ] **Step 3: 验证服务注册**

```bash
# 检查 Nacos 服务列表
curl -s "http://localhost:8848/nacos/v1/ns/instance/list?serviceName=user-profile-service" | jq .
curl -s "http://localhost:8848/nacos/v1/ns/instance/list?serviceName=vector-memory-service" | jq .
curl -s "http://localhost:8848/nacos/v1/ns/instance/list?serviceName=ai-chat-service" | jq .
```

---

## Task 3: 单元测试

**Files:**
- Create: `test-01-user-profile.sh`
- Create: `test-02-vector-memory.sh`
- Create: `test-03-ai-chat.sh`

- [ ] **Step 1: 测试用户画像服务**

```bash
cat > test-01-user-profile.sh << 'BASHEOF'
#!/bin/bash

echo "测试 1: 用户画像服务"
echo "====================="

# 发送 RabbitMQ 消息请求用户画像
python3 << 'PYEOF'
import pika
import json
import uuid
import time

connection = pika.BlockingConnection(pika.ConnectionParameters('localhost'))
channel = connection.channel()

correlation_id = str(uuid.uuid4())

# 声明响应队列
result = channel.queue_declare(queue='', exclusive=True)
callback_queue = result.method.queue

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
        reply_to=callback_queue,
        correlation_id=correlation_id
    )
)

print(f"✓ 已发送用户画像请求: {correlation_id}")

# 等待响应
def on_response(ch, method, props, body):
    if props.correlation_id == correlation_id:
        response = json.loads(body)
        print(f"✓ 收到响应: success={response['success']}")
        if response['success']:
            print(f"  用户: {response['data']['basicInfo']['name']}")
            print(f"  年龄: {response['data']['basicInfo']['age']}")
            print(f"  BMI: {response['data']['healthProfile']['bmi']}")
            print(f"  全局记忆数: {len(response['data']['globalMemories'])}")
        ch.stop_consuming()

channel.basic_consume(queue=callback_queue, on_message_callback=on_response, auto_ack=True)
channel.start_consuming()

connection.close()
PYEOF

echo ""
BASHEOF

chmod +x test-01-user-profile.sh
./test-01-user-profile.sh
```

- [ ] **Step 2: 测试向量记忆服务**

```bash
cat > test-02-vector-memory.sh << 'BASHEOF'
#!/bin/bash

echo "测试 2: 向量记忆服务"
echo "====================="

# 测试记忆提取
curl -X POST http://localhost:8102/api/memory/extract \
  -H "Content-Type: application/json" \
  -d '{
    "groupId": "test-group-001",
    "customerId": "test-customer-001",
    "userMessage": "我最近血压有点高，想控制一下饮食",
    "aiResponse": "建议您减少盐分摄入，多吃蔬菜水果，避免高脂肪食物"
  }' | jq .

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

connection = pika.BlockingConnection(pika.ConnectionParameters('localhost'))
channel = connection.channel()

correlation_id = str(uuid.uuid4())

result = channel.queue_declare(queue='', exclusive=True)
callback_queue = result.method.queue

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
        reply_to=callback_queue,
        correlation_id=correlation_id
    )
)

print(f"✓ 已发送记忆检索请求: {correlation_id}")

def on_response(ch, method, props, body):
    if props.correlation_id == correlation_id:
        response = json.loads(body)
        print(f"✓ 收到响应: success={response['success']}")
        if response['success']:
            print(f"  检索到 {len(response['data'])} 条记忆")
            for i, memory in enumerate(response['data'][:3]):
                print(f"  [{i+1}] {memory['content']} (相似度: {memory['similarity']:.3f})")
        ch.stop_consuming()

channel.basic_consume(queue=callback_queue, on_message_callback=on_response, auto_ack=True)
channel.start_consuming()

connection.close()
PYEOF

echo ""
BASHEOF

chmod +x test-02-vector-memory.sh
./test-02-vector-memory.sh
```

- [ ] **Step 3: 测试 AI 聊天服务（端到端）**

```bash
cat > test-03-ai-chat.sh << 'BASHEOF'
#!/bin/bash

echo "测试 3: AI 聊天服务（端到端）"
echo "============================="

curl -X POST http://localhost:8103/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "groupId": "test-group-001",
    "customerId": "test-customer-001",
    "message": "我想了解一下如何改善我的血压问题"
  }' | jq .

echo ""
echo "✓ 端到端测试完成"
BASHEOF

chmod +x test-03-ai-chat.sh
./test-03-ai-chat.sh
```

---

## Task 4: 性能测试

**Files:**
- Create: `performance-test.sh`

- [ ] **Step 1: 创建性能测试脚本**

```bash
cat > performance-test.sh << 'BASHEOF'
#!/bin/bash

echo "性能测试"
echo "========"

# 测试用户画像服务响应时间
echo "1. 用户画像服务响应时间（10次）"
for i in {1..10}; do
    start=$(date +%s%3N)
    # 这里需要实际的测试逻辑
    end=$(date +%s%3N)
    echo "  请求 $i: $((end - start))ms"
done

# 测试向量检索响应时间
echo ""
echo "2. 向量检索响应时间（10次）"
for i in {1..10}; do
    start=$(date +%s%3N)
    curl -s -X POST http://localhost:8102/api/memory/search \
      -H "Content-Type: application/json" \
      -d '{"groupId":"test-group-001","customerId":"test-customer-001","query":"健康建议","topK":5}' > /dev/null
    end=$(date +%s%3N)
    echo "  请求 $i: $((end - start))ms"
done

# 测试端到端响应时间
echo ""
echo "3. 端到端聊天响应时间（10次）"
for i in {1..10}; do
    start=$(date +%s%3N)
    curl -s -X POST http://localhost:8103/api/chat \
      -H "Content-Type: application/json" \
      -d '{"groupId":"test-group-001","customerId":"test-customer-001","message":"测试消息"}' > /dev/null
    end=$(date +%s%3N)
    echo "  请求 $i: $((end - start))ms"
done

echo ""
echo "性能测试完成"
BASHEOF

chmod +x performance-test.sh
```

- [ ] **Step 2: 执行性能测试**

```bash
./performance-test.sh
```

- [ ] **Step 3: 分析结果**

预期指标：
- 用户画像服务: < 100ms
- 向量检索: < 200ms
- 端到端聊天: < 2000ms (包含 Dify API 调用)

---

## Task 5: 监控和日志验证

**Files:**
- None (verification only)

- [ ] **Step 1: 检查 RabbitMQ 队列状态**

```bash
curl -u guest:guest http://localhost:15672/api/queues | jq '.[] | select(.name | contains("profile") or contains("memory")) | {name: .name, messages: .messages, consumers: .consumers}'
```

- [ ] **Step 2: 检查 Milvus Collection**

```bash
# 使用 Milvus CLI 或 Attu 查看
docker exec milvus-standalone milvus-cli show collections
```

- [ ] **Step 3: 查看服务日志**

```bash
tail -f logs/user-profile-service.log
tail -f logs/vector-memory-service.log
tail -f logs/ai-chat-service.log
```

- [ ] **Step 4: 检查 Prometheus 指标**

```bash
curl -s http://localhost:8101/actuator/prometheus | grep "http_server_requests"
curl -s http://localhost:8102/actuator/prometheus | grep "http_server_requests"
curl -s http://localhost:8103/actuator/prometheus | grep "http_server_requests"
```

---

## Task 6: 故障恢复测试

**Files:**
- Create: `failure-test.sh`

- [ ] **Step 1: 测试 RabbitMQ 重连**

```bash
# 停止 RabbitMQ
docker stop governance-rabbitmq

# 等待 10 秒
sleep 10

# 重启 RabbitMQ
docker start governance-rabbitmq

# 验证服务自动重连
sleep 5
./test-03-ai-chat.sh
```

- [ ] **Step 2: 测试 Milvus 重连**

```bash
# 停止 Milvus
docker stop milvus-standalone

# 等待 10 秒
sleep 10

# 重启 Milvus
docker start milvus-standalone

# 验证服务自动重连
sleep 10
./test-02-vector-memory.sh
```

- [ ] **Step 3: 测试服务降级**

模拟 Dify API 不可用，验证服务是否能正常降级处理

---

## Task 7: 清理和文档

**Files:**
- Create: `stop-ai-services.sh`
- Update: `README.md`

- [ ] **Step 1: 创建停止脚本**

```bash
cat > stop-ai-services.sh << 'BASHEOF'
#!/bin/bash

echo "停止 AI 记忆功能相关服务..."

pkill -f "user-profile-service"
pkill -f "vector-memory-service"
pkill -f "ai-chat-service"

echo "所有服务已停止"
BASHEOF

chmod +x stop-ai-services.sh
```

- [ ] **Step 2: 更新 README**

添加 AI 记忆功能的使用说明

- [ ] **Step 3: 提交所有测试脚本**

```bash
git add test-*.sh start-ai-services.sh stop-ai-services.sh performance-test.sh
git commit -m "test: add integration tests for AI memory feature"
```

---

## 验证清单

- [ ] 所有基础设施正常运行
- [ ] 3 个微服务成功启动
- [ ] 服务注册到 Nacos
- [ ] 用户画像服务测试通过
- [ ] 向量记忆服务测试通过
- [ ] AI 聊天服务端到端测试通过
- [ ] 性能指标符合预期
- [ ] RabbitMQ 队列正常
- [ ] Milvus Collection 正常
- [ ] 日志输出正常
- [ ] 故障恢复测试通过
- [ ] 所有测试脚本已提交

---

## 完成标志

当所有验证清单项都通过时，AI 记忆功能实施完成！

**总耗时估算**: 18-26 小时
- Plan 1: 基础设施 (3-4h)
- Plan 2: user-profile-service (4-6h)
- Plan 3: vector-memory-service (6-8h)
- Plan 4: ai-chat-service (4-6h)
- Plan 5: 集成测试 (2-3h)
