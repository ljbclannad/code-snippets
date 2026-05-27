#!/bin/bash

# 测试异步通知功能
# 使用方式：./test-async-notification.sh

GATEWAY_URL="http://localhost:8080"
USER_ID=2001
SCHEDULE_ID=1

echo "=========================================="
echo "异步通知功能测试"
echo "=========================================="

# 1. 获取认证 token
echo ""
echo "1. 获取认证 token"
echo "------------------------------------------"

curl -s -X POST "${GATEWAY_URL}/api/user/register" \
  -H "Content-Type: application/json" \
  -d "{\"username\": \"testuser${USER_ID}\", \"password\": \"password123\"}" > /dev/null

LOGIN_RESPONSE=$(curl -s -X POST "${GATEWAY_URL}/api/user/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\": \"testuser${USER_ID}\", \"password\": \"password123\"}")

TOKEN=$(echo $LOGIN_RESPONSE | grep -o '"data":"[^"]*"' | cut -d'"' -f4)

if [ -z "$TOKEN" ]; then
  echo "错误：无法获取 token"
  exit 1
fi

echo "Token 获取成功"

# 2. 准备测试数据
echo ""
echo "2. 准备测试数据"
echo "------------------------------------------"

redis-cli -h localhost -p 36379 SET "appointment:schedule:1" \
  '{"scheduleId":1,"deptId":101,"doctorId":1}' > /dev/null
redis-cli -h localhost -p 36379 SET "appointment:stock:1" "100" > /dev/null

echo "测试数据准备完成"

# 3. 抢号创建订单
echo ""
echo "3. 抢号创建订单"
echo "------------------------------------------"

REQUEST_ID="req-$(date +%s)-$$"

curl -s -X POST "${GATEWAY_URL}/api/appointment/grab" \
  -H "Content-Type: application/json" \
  -H "Authorization: ${TOKEN}" \
  -H "Idempotency-Key: ${REQUEST_ID}" \
  -d "{\"scheduleId\": ${SCHEDULE_ID}}" > /dev/null

sleep 2

ORDER_NO=$(mysql -h127.0.0.1 -P3306 -uroot -proot order_db -N -e \
  "SELECT order_no FROM t_appointment_idempotency WHERE idempotency_key='${REQUEST_ID}'" 2>/dev/null)

if [ -z "$ORDER_NO" ]; then
  echo "错误：未找到订单号"
  exit 1
fi

echo "订单号: ${ORDER_NO}"

# 4. 支付订单
echo ""
echo "4. 支付订单"
echo "------------------------------------------"

PAY_RESPONSE=$(curl -s -X POST "${GATEWAY_URL}/api/appointment/pay/${ORDER_NO}" \
  -H "Authorization: ${TOKEN}")

echo "支付响应: ${PAY_RESPONSE}"

# 5. 验证 Outbox 写入
echo ""
echo "5. 验证 Outbox 写入"
echo "------------------------------------------"

sleep 1

OUTBOX_COUNT=$(mysql -h127.0.0.1 -P3306 -uroot -proot order_db -N -e \
  "SELECT COUNT(*) FROM t_outbox WHERE aggregate_id='${ORDER_NO}' AND event_type='appointment.paid'" 2>/dev/null)

echo "Outbox 记录数: ${OUTBOX_COUNT}"

if [ "$OUTBOX_COUNT" -eq 0 ]; then
  echo "❌ Outbox 未写入"
  exit 1
else
  echo "✅ Outbox 写入成功"
fi

# 6. 验证 RabbitMQ 消息投递
echo ""
echo "6. 验证 RabbitMQ 消息投递（等待 outbox-consumer 处理）"
echo "------------------------------------------"

sleep 3

OUTBOX_STATUS=$(mysql -h127.0.0.1 -P3306 -uroot -proot order_db -N -e \
  "SELECT status FROM t_outbox WHERE aggregate_id='${ORDER_NO}' AND event_type='appointment.paid'" 2>/dev/null)

echo "Outbox 状态: ${OUTBOX_STATUS}"

if [ "$OUTBOX_STATUS" == "SENT" ]; then
  echo "✅ RabbitMQ 消息投递成功"
else
  echo "⚠️  Outbox 状态为 ${OUTBOX_STATUS}（可能需要等待 outbox-consumer 处理）"
fi

# 7. 验证消费者日志输出
echo ""
echo "7. 验证消费者日志输出"
echo "------------------------------------------"

echo "检查 appointment-service 日志中是否包含以下关键字："
echo "  - Received appointment paid event"
echo "  - Sending SMS notification"
echo "  - Sending in-app notification"
echo "  - Appointment paid event processed successfully"

# 8. 验证消费幂等
echo ""
echo "8. 验证消费幂等"
echo "------------------------------------------"

IDEMPOTENCY_KEY="consumed:appointment.paid:${ORDER_NO}"
CONSUMED=$(redis-cli -h localhost -p 36379 GET "${IDEMPOTENCY_KEY}" 2>/dev/null)

echo "幂等键: ${IDEMPOTENCY_KEY}"
echo "幂等值: ${CONSUMED}"

if [ "$CONSUMED" == "1" ]; then
  echo "✅ 消费幂等键已设置"
else
  echo "⚠️  消费幂等键未设置（可能消息尚未消费）"
fi

echo ""
echo "=========================================="
echo "测试完成"
echo "=========================================="
echo ""
echo "提示："
echo "1. 确保 outbox-consumer 服务已启动"
echo "2. 确保 RabbitMQ 服务已启动"
echo "3. 查看 appointment-service 日志验证通知发送"
